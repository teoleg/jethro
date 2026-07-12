package io.jethro.app.strategy;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.order.NewOrder;
import io.jethro.order.OrderService;
import io.jethro.domain.OrderType;
import io.jethro.trading.algo.strategy.MomentumStrategy;
import io.jethro.trading.algo.strategy.TradeSignal;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Runs the toy momentum strategy (step 8) on a cadence and turns its signals into
 * advisory suggestions on the attention feed (ADR-0018 candidate → guardrail → surface):
 * read marks → strategy signals → size to target notional → deterministic pre-trade
 * guardrail against the configured book → surface the admissible ones. Never routes an
 * order (invariant 7); a human acts on the suggestion from the Orders ticket.
 */
public final class StrategyLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StrategyLifecycle.class);

    private final MomentumStrategy strategy;
    private final TradingCoreLifecycle tradingCore;
    private final InstrumentRefSource refs;
    private final PreTradeGuardrail guardrail;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;
    private final StrategyProperties props;
    private final OrderService orderService; // nullable: null → suggestions only

    private static final long HEARTBEAT_CYCLES = 24; // ~2 min at a 5s cadence

    private final Set<String> active = new HashSet<>();
    private final Map<String, Long> lastAutoExec = new ConcurrentHashMap<>();
    private long cycles;
    private boolean throttledActive;
    private volatile ScheduledExecutorService scheduler;

    public StrategyLifecycle(MomentumStrategy strategy, TradingCoreLifecycle tradingCore,
                             InstrumentRefSource refs, PreTradeGuardrail guardrail,
                             AttentionFeed feed, SseBroadcaster sse, StrategyProperties props,
                             OrderService orderService) {
        this.strategy = strategy;
        this.tradingCore = tradingCore;
        this.refs = refs;
        this.guardrail = guardrail;
        this.feed = feed;
        this.sse = sse;
        this.props = props;
        this.orderService = orderService;
    }

    private boolean autoExecuting() {
        return props.autoExecute() && orderService != null;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "strategy");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce,
                props.intervalSeconds(), props.intervalSeconds(), TimeUnit.SECONDS);
        log.info("momentum strategy started: every {}s, lookback {}, threshold {}bps, book {}",
                props.intervalSeconds(), props.lookback(), props.thresholdBps(), props.book());
        if (autoExecuting()) {
            log.warn("AUTO-EXECUTE ON (ADR-0019): strategy signals auto-submit SIMULATED orders "
                    + "to book {} (cooldown {}s). Never enable against a real broker.",
                    props.book(), props.autoCooldownSeconds());
        } else if (props.autoExecute()) {
            log.warn("jethro.strategy.auto-execute=true but no order service available — suggestions only");
        }
    }

    private void runOnce() {
        try {
            var runtime = tradingCore.runtime();
            if (runtime == null) {
                return;
            }
            List<MomentumStrategy.Observation> observations = new ArrayList<>();
            int stale = 0;
            for (var mark : runtime.markCache().snapshot()) {
                observations.add(new MomentumStrategy.Observation(mark.instrumentId(), mark.price(), mark.stale()));
                if (mark.stale()) {
                    stale++;
                }
            }
            int fresh = observations.size() - stale;

            long now = System.currentTimeMillis();
            Set<String> current = new HashSet<>();
            int signals = 0;
            int suppressed = 0;
            int executed = 0;
            String sampleReason = null;
            for (TradeSignal signal : strategy.evaluate(observations)) {
                signals++;
                BigDecimal quantity = size(signal);
                BigDecimal signed = signal.side().signed(quantity);
                Optional<String> rejection =
                        guardrail.rejectionReason(props.book(), signal.instrumentId(), signed);
                if (rejection.isPresent()) {
                    suppressed++;
                    sampleReason = rejection.get();
                    continue; // not admissible under the book's limits — don't suggest it
                }
                boolean traded = autoExecuting() && maybeAutoExecute(signal, quantity, now);
                if (traded) {
                    executed++;
                }
                String id = "signal:" + signal.instrumentId();
                current.add(id);
                feed.upsert(toItem(signal, quantity, now, traded));
            }
            boolean changed = false;
            for (String id : Set.copyOf(active)) {
                if (!current.contains(id)) {
                    feed.resolve(id);
                    active.remove(id);
                    changed = true;
                }
            }
            for (String id : current) {
                if (active.add(id)) {
                    changed = true;
                }
            }

            // Surface "why did it go quiet" on the feed the user already watches: every
            // signal blocked by the guardrail (e.g. the book hit an exposure limit).
            if (suppressed > 0 && current.isEmpty()) {
                if (!throttledActive) {
                    feed.upsert(new AttentionFeed.AttentionItem("strategy-throttled", now,
                            AttentionFeed.Severity.WARN, "strategy-throttled",
                            "Strategy idling — " + props.book() + " at a risk limit",
                            suppressed + " signal(s) blocked by the pre-trade guardrail: " + sampleReason
                                    + ". Auto-trading resumes when the book's exposure frees up.", "/books.html"));
                    throttledActive = true;
                    changed = true;
                }
            } else if (throttledActive) {
                feed.resolve("strategy-throttled");
                throttledActive = false;
                changed = true;
            }
            if (changed) {
                sse.broadcast("attention", feed.snapshot());
            }

            // Heartbeat: the log always shows the strategy is alive and why it is/ isn't trading.
            if (++cycles % HEARTBEAT_CYCLES == 0 || signals > 0 || suppressed > 0) {
                log.info("strategy: {} fresh marks ({} stale), {} signals, {} suppressed by limits, {} auto-executed{}",
                        fresh, stale, signals, suppressed, executed,
                        fresh == 0 ? "  — NO FRESH MARKS (feed may be stale)" : "");
            }
        } catch (Throwable t) {
            // Never let a Throwable silently cancel the scheduled task — that would stop the
            // strategy for good with no further logging. Catch, log, keep the cadence alive.
            log.warn("strategy run failed: {}", t.toString());
        }
    }

    /** Sizes a suggestion to the target notional: qty = targetNotional / (price × multiplier), min 1. */
    private BigDecimal size(TradeSignal signal) {
        BigDecimal multiplier = refs.find(signal.instrumentId())
                .map(InstrumentRef::multiplier).orElse(BigDecimal.ONE);
        BigDecimal notionalPerUnit = signal.price().multiply(multiplier);
        BigDecimal qty = props.targetNotional().divide(notionalPerUnit, 0, RoundingMode.DOWN);
        return qty.signum() > 0 ? qty : BigDecimal.ONE;
    }

    /**
     * Auto-submits a signal as a simulated MARKET order through the normal order path
     * (ADR-0019: sim only, guardrail re-checked in OrderService), throttled by cooldown.
     * @return true if an order was submitted this cycle.
     */
    private boolean maybeAutoExecute(TradeSignal signal, BigDecimal qty, long now) {
        long cooldownMillis = props.autoCooldownSeconds() * 1_000;
        Long last = lastAutoExec.get(signal.instrumentId());
        if (last != null && now - last < cooldownMillis) {
            return false; // still cooling down for this instrument
        }
        try {
            var command = new NewOrder("auto:" + signal.instrumentId() + ":" + UUID.randomUUID(),
                    props.book(), signal.instrumentId(), signal.side(), OrderType.MARKET, qty, null);
            var order = orderService.submit(command);
            lastAutoExec.put(signal.instrumentId(), now);
            log.info("auto-executed {} {} {} → {} ({})",
                    signal.side(), qty.toPlainString(), signal.instrumentId(), order.status(), order.orderId());
            return true;
        } catch (Exception e) {
            log.warn("auto-execute of {} failed: {}", signal.instrumentId(), e.getMessage());
            return false;
        }
    }

    private AttentionFeed.AttentionItem toItem(TradeSignal s, BigDecimal qty, long now, boolean executed) {
        String action = s.side() + " " + qty.toPlainString() + " " + s.instrumentId();
        String title = (executed ? "Auto-traded: " : "Signal: ") + action;
        String tail = executed
                ? "Auto-submitted a SIMULATED order (ADR-0019)."
                : "Pre-trade risk check passed — review on the Orders ticket.";
        String body = (executed ? "Auto-" + s.side() + " " : "Suggested " + s.side() + " ")
                + qty.toPlainString() + " " + s.instrumentId()
                + " @ " + s.price().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " → " + props.book() + ". " + s.rationale() + ". " + tail;
        return new AttentionFeed.AttentionItem("signal:" + s.instrumentId(), now,
                AttentionFeed.Severity.INFO, "strategy-signal", title, body, "/orders.html");
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}

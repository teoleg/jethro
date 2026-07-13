package io.jethro.app.hypothesis;

import io.jethro.app.backtest.BacktestResult;
import io.jethro.app.backtest.BacktestService;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.order.NewOrder;
import io.jethro.order.OrderService;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.hypothesis.HypothesisContext;
import io.jethro.trading.algo.hypothesis.NarrativeItem;
import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the hypothesis pipeline (ADR-0022) on a cadence: gather the context (marks +
 * narrative + portfolio), ask the LLM for structured theses, run each through the
 * deterministic quant evaluator, and surface the admissible candidates on the attention
 * feed for human execution (ADR-0018 — no auto-submit in this slice). The market path never
 * depends on this; a model outage just means no new hypotheses (invariant 7).
 */
public final class HypothesisLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HypothesisLifecycle.class);

    private final io.jethro.trading.algo.hypothesis.HypothesisGenerator generator;
    private final HypothesisEvaluator evaluator;
    private final NarrativeFeed narrativeFeed;
    private final BacktestService backtest;
    private final TradingCoreLifecycle tradingCore;
    private final RiskProjection risk;
    private final InstrumentRefSource refs;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;
    private final HypothesisProperties props;
    private final AutonomyEnvelope envelope;
    private final OrderService orderService; // nullable: null → human-in-loop only
    private final HypothesisRecordStore recordStore;

    private static final int EXECUTED_CAP = 50;
    private final Deque<HypothesisRecord> executed = new ArrayDeque<>(); // sticky, newest first
    private final Set<String> active = new HashSet<>();
    private final Map<String, Long> lastAutoExec = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private volatile List<HypothesisEvaluator.Evaluated> latest = List.of();
    private volatile Set<String> autoTradedIds = Set.of();
    private volatile ScheduledExecutorService scheduler;

    public HypothesisLifecycle(io.jethro.trading.algo.hypothesis.HypothesisGenerator generator,
                               HypothesisEvaluator evaluator, NarrativeFeed narrativeFeed,
                               BacktestService backtest, TradingCoreLifecycle tradingCore, RiskProjection risk,
                               InstrumentRefSource refs, AttentionFeed feed, SseBroadcaster sse,
                               HypothesisProperties props, OrderService orderService,
                               HypothesisRecordStore recordStore) {
        this.generator = generator;
        this.evaluator = evaluator;
        this.narrativeFeed = narrativeFeed;
        this.backtest = backtest;
        this.envelope = new AutonomyEnvelope(props.autonomyOrDefault());
        this.orderService = orderService;
        this.recordStore = recordStore;
        this.tradingCore = tradingCore;
        this.risk = risk;
        this.refs = refs;
        this.feed = feed;
        this.sse = sse;
        this.props = props;
    }

    /** The latest evaluated hypotheses (all verdicts), for the /api/hypotheses surface. */
    public List<HypothesisEvaluator.Evaluated> latest() {
        return latest;
    }

    @Override
    public void start() {
        // Reload persisted executed hypotheses so they stay on the page across restarts.
        synchronized (executed) {
            for (HypothesisRecord r : recordStore.recent(EXECUTED_CAP)) {
                executed.addLast(r); // recent() is newest-first
            }
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hypothesis");
            t.setDaemon(true);
            return t;
        });
        long interval = props.intervalSecondsOrDefault();
        scheduler.scheduleWithFixedDelay(this::runOnce, interval, interval, TimeUnit.SECONDS);
        var auto = props.autonomyOrDefault();
        if (auto.enabledOrDefault() && orderService != null) {
            log.warn("BOUNDED AUTONOMY ON (ADR-0022): admissible, backtest-supported theses with conviction ≥ {} "
                            + "and notional ≤ {} auto-submit SIMULATED orders (cooldown {}s, whitelist {}). "
                            + "Never against a real broker.",
                    auto.minConvictionOrDefault(), auto.maxOrderNotionalOrDefault().toPlainString(),
                    auto.cooldownSecondsOrDefault(),
                    auto.whitelistOrEmpty().isEmpty() ? "(all)" : auto.whitelistOrEmpty());
        }
        log.info("hypothesis layer started: every {}s, up to {} per cycle (local SLM, {})",
                interval, props.maxPerCycleOrDefault(),
                auto.enabledOrDefault() && orderService != null ? "bounded autonomy" : "human-in-loop");
    }

    private void runOnce() {
        long startNanos = System.nanoTime();
        try {
            var runtime = tradingCore.runtime();
            if (runtime == null) {
                return;
            }
            long now = System.currentTimeMillis();

            List<HypothesisContext.MarkView> markViews = new ArrayList<>();
            Map<String, BigDecimal> priceMap = new HashMap<>();
            Set<String> tradable = new HashSet<>();
            List<String> singleNames = new ArrayList<>();
            for (var mark : runtime.markCache().snapshot()) {
                Optional<InstrumentRef> ref = refs.find(mark.instrumentId());
                if (ref.isEmpty()) {
                    continue; // curve pseudo-instruments (USD.SOFR.*) aren't tradable — skip
                }
                tradable.add(mark.instrumentId());
                priceMap.put(mark.instrumentId(), mark.price());
                String assetClass = ref.get().assetClass();
                // Tell the model WHAT each instrument is (asset class + description), so it reasons
                // about the real instrument instead of inventing an issuer for the ticker.
                markViews.add(new HypothesisContext.MarkView(
                        mark.instrumentId(), assetClass, ref.get().currency(),
                        InstrumentDescriptions.of(mark.instrumentId(), assetClass),
                        mark.price().toPlainString(), mark.stale()));
                if ("EQUITY".equals(assetClass) || "FUTURE".equals(assetClass)) {
                    singleNames.add(mark.instrumentId());
                }
            }
            if (tradable.isEmpty()) {
                return; // nothing to reason about yet
            }

            List<NarrativeItem> narrative = narrativeFeed.poll(tradingCore.regime(), singleNames, now);
            List<HypothesisContext.PortfolioLine> portfolio = new ArrayList<>();
            for (var p : risk.snapshot(now).positions()) {
                if (p.quantity().signum() != 0) {
                    portfolio.add(new HypothesisContext.PortfolioLine(p.bookId(), p.instrumentId(),
                            p.quantity().toPlainString(), p.unrealizedPnl().toPlainString()));
                }
            }

            List<Hypothesis> hypotheses;
            try {
                hypotheses = generator.generate(
                        new HypothesisContext(markViews, narrative, portfolio, tradable));
                consecutiveFailures.set(0);
            } catch (InferenceException e) {
                long failures = consecutiveFailures.incrementAndGet();
                if (failures == 1 || failures % 10 == 0) {
                    log.warn("hypothesis generation inference failed ({} consecutive): {}",
                            failures, e.getMessage());
                }
                return;
            }

            // Backtest the strategy on the current universe once per cycle, so each thesis
            // carries the measured edge on its instrument (the bounded-autonomy gate, ADR-0022).
            // Cheap and only when there's something to evaluate; a failure just omits the annotation.
            Map<String, BacktestResult.InstrumentResult> backtestByInstrument = new HashMap<>();
            if (!hypotheses.isEmpty()) {
                try {
                    for (var ir : backtest.run(null, props.backtestTicksOrDefault(), null, null).byInstrument()) {
                        backtestByInstrument.put(ir.instrumentId(), ir);
                    }
                } catch (Exception e) {
                    log.debug("hypothesis backtest annotation skipped: {}", e.toString());
                }
            }

            List<HypothesisEvaluator.Evaluated> evaluated = new ArrayList<>(hypotheses.size());
            for (Hypothesis h : hypotheses) {
                evaluated.add(evaluator.evaluate(h, priceMap, backtestByInstrument));
            }
            latest = List.copyOf(evaluated);
            Set<String> autoTraded = runAutonomy(evaluated, now);
            autoTradedIds = autoTraded;
            surface(evaluated, now, autoTraded);

            int admissible = (int) evaluated.stream()
                    .filter(e -> e.verdict() == HypothesisEvaluator.Verdict.ADMISSIBLE).count();
            // Log every cycle so "no hypotheses" is explained: the model ran (Ollama up) but
            // returned 0 usable structured theses, vs the layer being disabled or the model down.
            // The elapsed time is dominated by the local SLM inference — it's the real floor on how
            // often the cycle can run (scheduleWithFixedDelay waits interval AFTER this completes).
            long tookMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("hypotheses: model proposed {}, {} admissible, {} auto-traded (regime {}) — took {}ms",
                    evaluated.size(), admissible, autoTraded.size(), tradingCore.regime(), tookMs);
        } catch (Throwable t) {
            log.warn("hypothesis cycle failed: {}", t.toString());
        }
    }

    /**
     * Bounded-autonomy pass (ADR-0022): auto-executes admissible, backtest-supported theses
     * that fit the deterministic risk envelope, as SIMULATED orders (ADR-0019). Off unless
     * autonomy is enabled and an order path exists. @return instruments auto-traded this cycle.
     */
    private Set<String> runAutonomy(List<HypothesisEvaluator.Evaluated> evaluated, long now) {
        Set<String> traded = new HashSet<>();
        var auto = props.autonomyOrDefault();
        if (!auto.enabledOrDefault() || orderService == null) {
            return traded;
        }
        long cooldownMillis = auto.cooldownSecondsOrDefault() * 1_000;
        for (HypothesisEvaluator.Evaluated e : evaluated) {
            BigDecimal multiplier = refs.find(e.hypothesis().instrumentId())
                    .map(InstrumentRef::multiplier).orElse(BigDecimal.ONE);
            if (envelope.rejectionReason(e, multiplier).isPresent()) {
                continue; // outside the envelope — stays a human-review card
            }
            String instrument = e.hypothesis().instrumentId();
            Long last = lastAutoExec.get(instrument);
            if (last != null && now - last < cooldownMillis) {
                continue;
            }
            if (submitAuto(e, now)) {
                traded.add(instrument);
            }
        }
        return traded;
    }

    private boolean submitAuto(HypothesisEvaluator.Evaluated e, long now) {
        Hypothesis h = e.hypothesis();
        try {
            var command = new NewOrder("hypo:" + h.instrumentId() + ":" + UUID.randomUUID(),
                    e.book(), h.instrumentId(), h.direction(), OrderType.MARKET, e.quantity(), null);
            var order = orderService.submit(command);
            lastAutoExec.put(h.instrumentId(), now);
            recordExecuted(e, now, order.orderId(), String.valueOf(order.status()));
            log.warn("AUTONOMY auto-executed {} {} {} → {} on {} ({}) — thesis: {}",
                    h.direction(), e.quantity().toPlainString(), h.instrumentId(), order.status(),
                    e.book(), order.orderId(), h.thesis());
            return true;
        } catch (Exception ex) {
            log.warn("autonomy auto-execute of {} failed: {}", h.instrumentId(), ex.getMessage());
            return false;
        }
    }

    /** Instruments auto-traded by autonomy in the last cycle (for the UI). */
    public Set<String> autoTradedIds() {
        return autoTradedIds;
    }

    /** Persists an executed hypothesis and pins it to the sticky list (newest first, capped). */
    private void recordExecuted(HypothesisEvaluator.Evaluated e, long now, String orderId, String orderStatus) {
        Hypothesis h = e.hypothesis();
        Boolean backtestSupported = e.backtest() != null ? e.backtest().supports() : null;
        HypothesisRecord record = new HypothesisRecord(orderId, now, h.instrumentId(),
                h.direction().name(), h.horizon().name(), h.conviction().name(), h.thesis(),
                e.book(), e.quantity(), backtestSupported, orderId, orderStatus);
        try {
            recordStore.save(record); // durable; NOOP when persistence is off
        } catch (Exception ex) {
            log.warn("could not persist executed hypothesis {}: {}", orderId, ex.toString());
        }
        synchronized (executed) {
            executed.addFirst(record);
            while (executed.size() > EXECUTED_CAP) {
                executed.removeLast();
            }
        }
    }

    /** Executed (autonomy) hypotheses, newest first — persisted, sticky across cycles/restarts. */
    public List<HypothesisRecord> executed() {
        synchronized (executed) {
            return List.copyOf(executed);
        }
    }

    /** Admissible candidates become INFO cards on the attention feed; stale ones resolve. */
    private void surface(List<HypothesisEvaluator.Evaluated> evaluated, long now, Set<String> autoTraded) {
        Set<String> current = new HashSet<>();
        for (HypothesisEvaluator.Evaluated e : evaluated) {
            if (e.verdict() != HypothesisEvaluator.Verdict.ADMISSIBLE) {
                continue;
            }
            String id = "hypothesis:" + e.hypothesis().instrumentId();
            current.add(id);
            feed.upsert(card(id, e, now, autoTraded.contains(e.hypothesis().instrumentId())));
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
        if (changed) {
            sse.broadcast("attention", feed.snapshot());
        }
    }

    private static AttentionFeed.AttentionItem card(String id, HypothesisEvaluator.Evaluated e, long now,
                                                    boolean autoTraded) {
        Hypothesis h = e.hypothesis();
        String dir = h.direction() == Side.BUY ? "LONG" : "SHORT";
        String title = (autoTraded ? "Auto-traded (hypothesis): " : "Hypothesis: ")
                + dir + " " + h.instrumentId() + " (" + h.conviction() + ")";
        String backtestNote = "";
        if (e.backtest() != null) {
            backtestNote = e.backtest().supports()
                    ? " Backtest supports it (+" + e.backtest().pnl().toPlainString()
                        + " on " + e.backtest().trades() + " trades)."
                    : " Backtest does NOT support it (strategy not profitable on this name).";
        }
        String tail = autoTraded
                ? " Auto-submitted a SIMULATED order within the risk envelope (ADR-0019/0022)."
                : " Review and execute on the Orders ticket.";
        String body = h.thesis() + " — Quant sized " + e.quantity().toPlainString() + " " + h.instrumentId()
                + " on " + e.book() + " (" + h.horizon() + " horizon); pre-trade check passed." + backtestNote + tail;
        return new AttentionFeed.AttentionItem(id, now, AttentionFeed.Severity.INFO,
                "hypothesis", title, body, "/orders.html");
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
            log.info("hypothesis layer stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}

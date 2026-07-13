package io.jethro.app.hypothesis;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.Side;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final SimNarrativeFeed narrativeFeed;
    private final TradingCoreLifecycle tradingCore;
    private final RiskProjection risk;
    private final InstrumentRefSource refs;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;
    private final HypothesisProperties props;

    private final Set<String> active = new HashSet<>();
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private volatile List<HypothesisEvaluator.Evaluated> latest = List.of();
    private volatile ScheduledExecutorService scheduler;

    public HypothesisLifecycle(io.jethro.trading.algo.hypothesis.HypothesisGenerator generator,
                               HypothesisEvaluator evaluator, SimNarrativeFeed narrativeFeed,
                               TradingCoreLifecycle tradingCore, RiskProjection risk, InstrumentRefSource refs,
                               AttentionFeed feed, SseBroadcaster sse, HypothesisProperties props) {
        this.generator = generator;
        this.evaluator = evaluator;
        this.narrativeFeed = narrativeFeed;
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
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hypothesis");
            t.setDaemon(true);
            return t;
        });
        long interval = props.intervalSecondsOrDefault();
        scheduler.scheduleWithFixedDelay(this::runOnce, interval, interval, TimeUnit.SECONDS);
        log.info("hypothesis layer started: every {}s, up to {} per cycle (local SLM, human-in-loop)",
                interval, props.maxPerCycleOrDefault());
    }

    private void runOnce() {
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
                markViews.add(new HypothesisContext.MarkView(
                        mark.instrumentId(), mark.price().toPlainString(), mark.stale()));
                String assetClass = ref.get().assetClass();
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

            List<HypothesisEvaluator.Evaluated> evaluated = new ArrayList<>(hypotheses.size());
            for (Hypothesis h : hypotheses) {
                evaluated.add(evaluator.evaluate(h, priceMap));
            }
            latest = List.copyOf(evaluated);
            surface(evaluated, now);

            int admissible = (int) evaluated.stream()
                    .filter(e -> e.verdict() == HypothesisEvaluator.Verdict.ADMISSIBLE).count();
            if (!evaluated.isEmpty()) {
                log.info("hypotheses: {} proposed, {} admissible (regime {})",
                        evaluated.size(), admissible, tradingCore.regime());
            }
        } catch (Throwable t) {
            log.warn("hypothesis cycle failed: {}", t.toString());
        }
    }

    /** Admissible candidates become INFO cards on the attention feed; stale ones resolve. */
    private void surface(List<HypothesisEvaluator.Evaluated> evaluated, long now) {
        Set<String> current = new HashSet<>();
        for (HypothesisEvaluator.Evaluated e : evaluated) {
            if (e.verdict() != HypothesisEvaluator.Verdict.ADMISSIBLE) {
                continue;
            }
            String id = "hypothesis:" + e.hypothesis().instrumentId();
            current.add(id);
            feed.upsert(card(id, e, now));
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

    private static AttentionFeed.AttentionItem card(String id, HypothesisEvaluator.Evaluated e, long now) {
        Hypothesis h = e.hypothesis();
        String dir = h.direction() == Side.BUY ? "LONG" : "SHORT";
        String title = "Hypothesis: " + dir + " " + h.instrumentId() + " (" + h.conviction() + ")";
        String body = h.thesis() + " — Quant sized " + e.quantity().toPlainString() + " " + h.instrumentId()
                + " on " + e.book() + " (" + h.horizon() + " horizon); pre-trade check passed. "
                + "Review and execute on the Orders ticket.";
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

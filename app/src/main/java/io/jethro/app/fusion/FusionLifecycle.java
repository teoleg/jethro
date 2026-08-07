package io.jethro.app.fusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ADR-0055 fusion loop, running in SHADOW MODE (phase 4): on a cadence it reads every source's fresh
 * forecast, combines them into one target per instrument, and computes the netted order delta — the
 * whole "combine all sources before any order decision" pipeline — but it does NOT place orders. The
 * target book is surfaced so it can be watched against the live sources and the placeholder dials tuned
 * before any rerouting is switched on (per ADR-0019 that switch is sim-only, and gated on Oleg setting
 * the dials). Live routing (phase 4b/5) will send each delta through the existing ADR-0049/envelope
 * gates; until then {@code routeOrders} only logs intent. Observational — no order, no risk number
 * (ADR-0016 / invariant 7).
 */
public final class FusionLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FusionLifecycle.class);

    /**
     * The latest fused target book, with the per-source weights it was combined at (ADR-0055) and the
     * ADR-0064 edge-gate decision that shaped its deltas. The deltas surfaced here are the ones that
     * will actually be routed — a book that showed an intent it then declined to trade would be a
     * misleading operator view.
     */
    public record TargetBook(long atMillis, boolean routing, int instruments,
                             Map<String, Double> weights, List<FusionPlanner.Target> targets,
                             EdgeGate.Decision edgeGate,
                             double portfolioRiskMultiplier, int covarianceCoveredNames,
                             int volBudgetNames, double volBudgetDispersion, double volBudgetLeverCap,
                             List<TrailingRiskCut.Cut> riskCuts, int riskCutStoppedNames,
                             int streamVolMeasuredNames, String covarianceBasis,
                             Map<String, ForecastScalars.Measurement> forecastScalars,
                             Map<String, BigDecimal> aims, int insideBuffer,
                             double bookVolBrake, Double bookVolPlannedSigmaUsd,
                             Double bookVolReferenceSigmaUsd, int bookVolSamples) {
        static TargetBook empty() {
            return new TargetBook(0, false, 0, Map.of(), List.of(), null, 1.0, 0, 0, 1.0, 1.0,
                    List.of(), 0, 0, Basis.NONE_NAME, Map.of(), Map.of(), 0, 1.0, null, null, 0);
        }
    }

    /**
     * Which measured covariance sized this cycle's book, and the estimator itself (ADR-0089). Purely a
     * disclosure alongside the source — the operator must be able to see WHICH measurement moved the
     * sizes, since the two estimators are of the same statistic over different sampling periods.
     */
    record Basis(String name, ReturnCovarianceSource source) {
        static final String NONE_NAME = "none";
        static final String DAILY_NAME = "daily-close";
        static final String STREAM_NAME = "mark-stream";
    }

    private final ForecastRegistry registry;
    private final Function<String, BigDecimal> priceFor;
    /** instrument → contract multiplier from the instrument master; cash-at-risk → quantity (ADR-0078). */
    private final Function<String, BigDecimal> multiplierFor;
    private final Supplier<Map<String, BigDecimal>> positionsSupplier;
    private final Supplier<java.util.Set<String>> heldSupplier; // ADR-0065: names we must have a target for
    private final Supplier<FusionWeights> weightsSupplier;
    private final FusionPlanner.Params params;
    private final boolean routeOrders;
    private final FusionExecutor executor; // null ⇒ shadow only (no order path available)
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    private final double minForecastToRoute; // ADR-0059: conviction floor — don't route weak/oscillating signals
    private final Supplier<EdgeGate.Decision> edgeGate; // ADR-0064: measured edge vs measured cost
    /** ADR-0079: measured daily-return covariance — how much of the book is one bet repeated. */
    private final Supplier<ReturnCovarianceSource> covariance;
    /** ADR-0082 fallback: the desk's stated base measurement horizon, used when no rung is selected. */
    private final long baseHorizonSeconds;
    /** ADR-0083: the percentile each tail of the measured σ cross-section is winsorised at. */
    private final double volBudgetWinsorPct;
    /** ADR-0086: per-name σ measured from the mark stream — null ⇒ the risk cut is not wired. */
    private final StreamVolatility streamVol;
    /** ADR-0086: the volatility-scaled trailing exit — null ⇒ disabled, book untouched. */
    private final TrailingRiskCut riskCut;
    /** ADR-0071: durable mark history, so the σ sensor is not permanently cold on a redeployed desk. */
    private final SensorWarmup.History markHistory;
    /** Provider timestamp of a name's current mark — the σ sensor's seed anchor (ADR-0071 correction). */
    private final Function<String, Long> markTimeFor;
    /** Instruments already seeded into the σ sensor — touched only from the scheduled tick thread. */
    private final java.util.Set<String> volSeeded = new java.util.HashSet<>();
    /** ADR-0089: pairwise covariance measured on the mark stream — null ⇒ not wired, book untouched. */
    private final StreamCovariance streamCov;
    /** Instruments already in the ADR-0089 joint seed — touched only from the scheduled tick thread. */
    private final java.util.Set<String> covSeeded = new java.util.HashSet<>();
    /** ADR-0094: the no-trade region around the aim — null ⇒ not wired, deltas are the ADR-0080 ones. */
    private final PositionBuffer positionBuffer;
    /** ADR-0104: the absolute book-level risk anchor — null ⇒ not wired, the book keeps whatever
     *  risk level the cross-section happened to plan. */
    private final BookVolatilityBrake bookVolBrake;
    /** ADR-0137: the planned book's gross-notional cap — null ⇒ not wired, the book is byte-identical. */
    private final GrossNotionalCap grossCap;

    private volatile TargetBook lastBook = TargetBook.empty();
    private Future<?> task;

    public FusionLifecycle(ForecastRegistry registry, Function<String, BigDecimal> priceFor,
                           Function<String, BigDecimal> multiplierFor,
                           Supplier<Map<String, BigDecimal>> positionsSupplier,
                           Supplier<java.util.Set<String>> heldSupplier, Supplier<FusionWeights> weightsSupplier,
                           FusionPlanner.Params params, boolean routeOrders, FusionExecutor executor,
                           ScheduledExecutorService scheduler, long intervalSeconds, double minForecastToRoute,
                           Supplier<EdgeGate.Decision> edgeGate,
                           Supplier<ReturnCovarianceSource> covariance, long baseHorizonSeconds,
                           double volBudgetWinsorPct) {
        this(registry, priceFor, multiplierFor, positionsSupplier, heldSupplier, weightsSupplier, params,
                routeOrders, executor, scheduler, intervalSeconds, minForecastToRoute, edgeGate,
                covariance, baseHorizonSeconds, volBudgetWinsorPct, null, null, null, null, null);
    }

    public FusionLifecycle(ForecastRegistry registry, Function<String, BigDecimal> priceFor,
                           Function<String, BigDecimal> multiplierFor,
                           Supplier<Map<String, BigDecimal>> positionsSupplier,
                           Supplier<java.util.Set<String>> heldSupplier, Supplier<FusionWeights> weightsSupplier,
                           FusionPlanner.Params params, boolean routeOrders, FusionExecutor executor,
                           ScheduledExecutorService scheduler, long intervalSeconds, double minForecastToRoute,
                           Supplier<EdgeGate.Decision> edgeGate,
                           Supplier<ReturnCovarianceSource> covariance, long baseHorizonSeconds,
                           double volBudgetWinsorPct, StreamVolatility streamVol, TrailingRiskCut riskCut,
                           SensorWarmup.History markHistory, Function<String, Long> markTimeFor) {
        this(registry, priceFor, multiplierFor, positionsSupplier, heldSupplier, weightsSupplier, params,
                routeOrders, executor, scheduler, intervalSeconds, minForecastToRoute, edgeGate,
                covariance, baseHorizonSeconds, volBudgetWinsorPct, streamVol, riskCut, markHistory,
                markTimeFor, null);
    }

    public FusionLifecycle(ForecastRegistry registry, Function<String, BigDecimal> priceFor,
                           Function<String, BigDecimal> multiplierFor,
                           Supplier<Map<String, BigDecimal>> positionsSupplier,
                           Supplier<java.util.Set<String>> heldSupplier, Supplier<FusionWeights> weightsSupplier,
                           FusionPlanner.Params params, boolean routeOrders, FusionExecutor executor,
                           ScheduledExecutorService scheduler, long intervalSeconds, double minForecastToRoute,
                           Supplier<EdgeGate.Decision> edgeGate,
                           Supplier<ReturnCovarianceSource> covariance, long baseHorizonSeconds,
                           double volBudgetWinsorPct, StreamVolatility streamVol, TrailingRiskCut riskCut,
                           SensorWarmup.History markHistory, Function<String, Long> markTimeFor,
                           StreamCovariance streamCov) {
        this(registry, priceFor, multiplierFor, positionsSupplier, heldSupplier, weightsSupplier, params,
                routeOrders, executor, scheduler, intervalSeconds, minForecastToRoute, edgeGate,
                covariance, baseHorizonSeconds, volBudgetWinsorPct, streamVol, riskCut, markHistory,
                markTimeFor, streamCov, null);
    }

    public FusionLifecycle(ForecastRegistry registry, Function<String, BigDecimal> priceFor,
                           Function<String, BigDecimal> multiplierFor,
                           Supplier<Map<String, BigDecimal>> positionsSupplier,
                           Supplier<java.util.Set<String>> heldSupplier, Supplier<FusionWeights> weightsSupplier,
                           FusionPlanner.Params params, boolean routeOrders, FusionExecutor executor,
                           ScheduledExecutorService scheduler, long intervalSeconds, double minForecastToRoute,
                           Supplier<EdgeGate.Decision> edgeGate,
                           Supplier<ReturnCovarianceSource> covariance, long baseHorizonSeconds,
                           double volBudgetWinsorPct, StreamVolatility streamVol, TrailingRiskCut riskCut,
                           SensorWarmup.History markHistory, Function<String, Long> markTimeFor,
                           StreamCovariance streamCov, PositionBuffer positionBuffer) {
        this(registry, priceFor, multiplierFor, positionsSupplier, heldSupplier, weightsSupplier, params,
                routeOrders, executor, scheduler, intervalSeconds, minForecastToRoute, edgeGate,
                covariance, baseHorizonSeconds, volBudgetWinsorPct, streamVol, riskCut, markHistory,
                markTimeFor, streamCov, positionBuffer, null);
    }

    public FusionLifecycle(ForecastRegistry registry, Function<String, BigDecimal> priceFor,
                           Function<String, BigDecimal> multiplierFor,
                           Supplier<Map<String, BigDecimal>> positionsSupplier,
                           Supplier<java.util.Set<String>> heldSupplier, Supplier<FusionWeights> weightsSupplier,
                           FusionPlanner.Params params, boolean routeOrders, FusionExecutor executor,
                           ScheduledExecutorService scheduler, long intervalSeconds, double minForecastToRoute,
                           Supplier<EdgeGate.Decision> edgeGate,
                           Supplier<ReturnCovarianceSource> covariance, long baseHorizonSeconds,
                           double volBudgetWinsorPct, StreamVolatility streamVol, TrailingRiskCut riskCut,
                           SensorWarmup.History markHistory, Function<String, Long> markTimeFor,
                           StreamCovariance streamCov, PositionBuffer positionBuffer,
                           BookVolatilityBrake bookVolBrake) {
        this(registry, priceFor, multiplierFor, positionsSupplier, heldSupplier, weightsSupplier, params,
                routeOrders, executor, scheduler, intervalSeconds, minForecastToRoute, edgeGate,
                covariance, baseHorizonSeconds, volBudgetWinsorPct, streamVol, riskCut, markHistory,
                markTimeFor, streamCov, positionBuffer, bookVolBrake, null);
    }

    public FusionLifecycle(ForecastRegistry registry, Function<String, BigDecimal> priceFor,
                           Function<String, BigDecimal> multiplierFor,
                           Supplier<Map<String, BigDecimal>> positionsSupplier,
                           Supplier<java.util.Set<String>> heldSupplier, Supplier<FusionWeights> weightsSupplier,
                           FusionPlanner.Params params, boolean routeOrders, FusionExecutor executor,
                           ScheduledExecutorService scheduler, long intervalSeconds, double minForecastToRoute,
                           Supplier<EdgeGate.Decision> edgeGate,
                           Supplier<ReturnCovarianceSource> covariance, long baseHorizonSeconds,
                           double volBudgetWinsorPct, StreamVolatility streamVol, TrailingRiskCut riskCut,
                           SensorWarmup.History markHistory, Function<String, Long> markTimeFor,
                           StreamCovariance streamCov, PositionBuffer positionBuffer,
                           BookVolatilityBrake bookVolBrake, GrossNotionalCap grossCap) {
        this.grossCap = grossCap;
        this.bookVolBrake = bookVolBrake;
        this.positionBuffer = positionBuffer;
        this.streamCov = streamCov;
        this.streamVol = streamVol;
        this.riskCut = riskCut;
        this.markHistory = markHistory;
        this.markTimeFor = markTimeFor;
        this.volBudgetWinsorPct = volBudgetWinsorPct;
        this.baseHorizonSeconds = Math.max(1, baseHorizonSeconds);
        this.edgeGate = edgeGate;
        this.covariance = covariance;
        this.registry = registry;
        this.priceFor = priceFor;
        this.multiplierFor = multiplierFor;
        this.positionsSupplier = positionsSupplier;
        this.heldSupplier = heldSupplier;
        this.weightsSupplier = weightsSupplier;
        this.params = params;
        this.executor = executor;
        this.scheduler = scheduler;
        this.routeOrders = routeOrders && executor != null;
        this.intervalSeconds = Math.max(5, intervalSeconds);
        this.minForecastToRoute = Math.max(0, minForecastToRoute);
    }

    /** True when this loop is actually placing orders (route-orders set AND an order path is wired). */
    public boolean live() {
        return routeOrders;
    }

    /**
     * This cycle's planner params, with the partial-adjustment rate derived from the horizon the
     * evidence selected (ADR-0080 identity, ADR-0082 horizon).
     *
     * <p>A configured rate above zero pins it — the ADR-0080 escape hatch, untouched. At zero the rate
     * is DERIVED so the desk's exposure e-folds toward target in exactly the period its edge was
     * measured over, which is the period the gate credited one round trip against. When the gate has
     * not stated a horizon (no telemetry, no fills yet, or a failed read) the desk's stated base
     * horizon stands — the slowest rung, and so the fewest round trips, which is the safe default when
     * the evidence has not spoken.
     */
    private FusionPlanner.Params withHoldingPeriod(EdgeGate.Decision gate) {
        if (params.adjustmentRate() > 0) {
            return params;
        }
        long horizon = gate != null && gate.horizonSeconds() > 0 ? gate.horizonSeconds() : baseHorizonSeconds;
        return params.withAdjustmentRate(TargetPlanner.adjustmentRateFor(intervalSeconds, horizon));
    }

    public void start() {
        task = scheduler.scheduleWithFixedDelay(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("fusion loop started (every {}s) — ADR-0055 {}", intervalSeconds,
                routeOrders ? "LIVE ROUTING (paper — SimulatedExecutor, any feed): the sole order origin, gated by ADR-0049/guardrail/breaker"
                        : "SHADOW MODE (computes the target book, places NO orders)");
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            // ADR-0084: retire last cycle's passive orders BEFORE reading the book. An entry now
            // rests rather than crossing, so without this sweep the desk would plan against a
            // position that ignores its own working intent and stack a fresh order on top of it
            // every cycle. Sweeping first means the plan below is made against a book with no
            // fusion order in flight, exactly as it was when every delta filled instantly.
            if (routeOrders) {
                executor.cancelStalePassiveOrders();
            }
            Map<String, List<Forecast>> forecasts = registry.byInstrument(now);
            Map<String, BigDecimal> positions = positionsSupplier.get();
            FusionWeights weights = weightsSupplier.get(); // re-estimated from live telemetry each cycle (ADR-0055)
            // ADR-0064/0082: read the gate BEFORE planning. It carries the horizon the evidence picked,
            // and under ADR-0080 the desk's holding period is that horizon — so the rate the planner
            // sizes this cycle's step with is not known until the gate has spoken.
            EdgeGate.Decision gate = edgeGate == null ? null : edgeGate.get();
            FusionPlanner.Params cycleParams = withHoldingPeriod(gate);
            // ADR-0065: plan over the names we HOLD as well as the names we have a view on, so a
            // position never falls out of the target book when its sources go quiet.
            java.util.Set<String> held = heldSupplier == null ? java.util.Set.of() : heldSupplier.get();
            List<FusionPlanner.Target> targets = FusionPlanner.plan(forecasts, held, weights::weightFor, priceFor,
                    multiplierFor, id -> positions.getOrDefault(id, BigDecimal.ZERO), cycleParams);
            ReturnCovarianceSource dailyCov = covariance == null ? ReturnCovarianceSource.NONE : covariance.get();
            // ADR-0089: both sizing controls below are silent on a name their covariance does not cover,
            // and the daily-close estimate covers none of this book on a stream only a session or two
            // old. Measure the same statistic on the mark stream and size on whichever of the two
            // actually covers more of the book that is about to be planned.
            Basis basis = sizingCovariance(dailyCov, targets);
            ReturnCovarianceSource cov = basis.source();
            // ADR-0083: split the per-name cash budget by each name's own MEASURED volatility before
            // anything looks at the book as a whole, so every name contributes the same standalone risk
            // instead of the same cash. Applied FIRST because the correlation control below prices how
            // much of the book is one bet, and that question is only well posed once the names are
            // comparable — otherwise it measures a concentration this step was always going to remove.
            var budgeted = VolatilityBudget.apply(targets, multiplierFor, cov, volBudgetWinsorPct,
                    cycleParams);
            targets = budgeted.targets();
            // ADR-0079: the per-name budget sizes each name as if it were the only position, which is
            // the independence assumption. Scale the book back to the risk that assumption implies
            // once the names' MEASURED correlation is counted, so a cross-section that is really one
            // bet cannot carry N budgets of it. Applied before the edge gate: the gate clamps what the
            // desk actually intends to hold, and the operator's book shows the sizes that will route.
            var normalised = PortfolioRiskNormaliser.apply(targets, multiplierFor, cov, cycleParams);
            targets = normalised.targets();
            // ADR-0104: both controls above are RELATIVE — they decide how the book's risk is shared out
            // and how much of it is one bet, but nothing above states how much risk the book should
            // carry. The level was falling out of how many names happened to pass the gate and how
            // strong their forecasts happened to be, which is why planned gross swung several-fold
            // between evaluations with the desk's appetite unchanged. Cap the book's measured ex-ante σ
            // at the MEDIAN of its own planned-σ series — no chosen number, one-way, and applied here so
            // the anchor is on the risk the desk actually intends, before the gate clamps it and before
            // the exit gets its final word.
            var braked = bookVolBrake == null
                    ? new BookVolatilityBrake.Result(targets, 1.0, null, null, 0, 0)
                    : bookVolBrake.apply(targets, multiplierFor, cov, cycleParams);
            targets = braked.targets();
            // ADR-0137: every control above is σ-RELATIVE — they decide how the book's risk is shared
            // out, how much of it is one bet, and what σ level it carries. None of them states a
            // NOTIONAL, and on a calm tape a measured σ is small, so none of them binds: the planned
            // book ran to several times the gross the deterministic guardrail permits the routing book
            // to hold. That does not put risk on — the guardrail still refuses the order — it makes the
            // target permanently unreachable, so the ADR-0094 aim never converges, the held book stays a
            // small fraction of its own target, and the ADR-0080 step a×gap pays that inflation in
            // turnover every cycle. Cap the planned gross at the cap the guardrail already enforces:
            // one-way, uniform, and introducing no money number of its own. Unwired ⇒ book unchanged.
            var capped = grossCap == null
                    ? new GrossNotionalCap.Result(targets, 1.0, BigDecimal.ZERO, 0)
                    : grossCap.apply(targets, multiplierFor, cycleParams);
            targets = capped.targets();
            // ADR-0064: with no measured edge that beats measured execution cost, the only trades worth
            // paying for are the ones that take risk OFF. ADR-0072 asks the same question per name, so
            // a name whose own round trip costs more than the passing source's measured edge is
            // reduce-only even when the desk as a whole may increase. Clamp before anything else sees
            // the deltas — the operator's target book must show what will actually be routed.
            if (gate != null) {
                targets = reduceOnlyWhere(targets, gate);
            }
            // ADR-0086: the risk-reactive exit runs LAST, so a cut is the desk's final word on a name.
            // Everything above decides how much risk the desk WANTS; this is the only step that asks
            // whether a position it already holds has gone wrong. It can only ever set a target flat.
            long horizon = gate != null && gate.horizonSeconds() > 0 ? gate.horizonSeconds() : baseHorizonSeconds;
            var cut = applyRiskCut(targets, now, horizon, cycleParams);
            targets = cut.targets();
            // ADR-0094: the no-trade region, applied LAST and against the AIM rather than the target.
            // Everything above decides where the desk means to be; this decides whether the difference
            // between that and where it is is worth paying spread for. It runs after the risk cut so a
            // flat target reaches it as a flat AIM and is still worked in full, and it re-applies the
            // ADR-0064 gate itself because it re-derives the delta rather than clamping the old one.
            // ADR-0126: and the buffer is also where the desk refuses to OPEN a name whose ADR-0086
            // trailing stop has no measured σ yet — the risk cut above cannot protect a position it
            // cannot price a distance for, so the desk holds no risk it has no exit for. Passed in here
            // rather than clamped upstream because this step re-derives every delta from the aim, and
            // evaluated independently of the edge gate so switching that gate off cannot silence it.
            var buffered = positionBuffer == null
                    ? new PositionBuffer.Result(targets, Map.of(), 0, targets.size())
                    : positionBuffer.apply(targets, gate, cycleParams.adjustmentRate(), this::stopArmed);
            targets = buffered.targets();
            lastBook = new TargetBook(now, routeOrders, targets.size(), weights.snapshot(), targets, gate,
                    normalised.multiplier(), normalised.coveredNames(),
                    budgeted.coveredNames(), budgeted.dispersion(), budgeted.leverCap(),
                    cut.cuts(), cut.stoppedNames(),
                    streamVol == null ? 0 : streamVol.measuredNames(), basis.name(),
                    registry.scalarSnapshot(), buffered.aims(), buffered.insideBuffer(),
                    braked.multiplier(), braked.plannedSigmaUsd(), braked.referenceSigmaUsd(),
                    braked.samples());
            if (routeOrders) {
                int routed = 0;
                // ADR-0134: the names the ADR-0086 trailing stop flattened this cycle. A stop cut and a
                // decayed view both arrive here as a reduce, but they are different triggers and the
                // post-mortem needs to tell them apart, so the distinction is captured where it is known.
                java.util.Set<String> stopped = new java.util.HashSet<>();
                for (TrailingRiskCut.Cut c : cut.cuts()) {
                    stopped.add(c.instrument());
                }
                for (FusionPlanner.Target t : targets) {
                    if (t.deltaQty().signum() == 0) {
                        continue; // inside the no-trade band — nothing to do
                    }
                    // ADR-0065: the conviction floor asks "is this view strong enough to put risk ON?".
                    // It has no business blocking a trade that takes risk OFF — and applied there it
                    // would permanently trap exactly the positions whose view has decayed to nothing.
                    boolean reducing = TargetPlanner.isRiskReducing(t.deltaQty(), t.currentQty());
                    if (!reducing && Math.abs(t.combinedForecast()) < minForecastToRoute) {
                        continue; // ADR-0059: below the conviction floor — don't churn a weak/oscillating signal
                    }
                    if (executor.route(t.instrument(), t.deltaQty(), reducing,
                            originOf(t, reducing, stopped.contains(t.instrument()))).routed()) {
                        routed++;
                    }
                }
                if (routed > 0) {
                    log.info("fusion: routed {} sole-origin delta order(s) this cycle (paper fills)", routed);
                }
            }
        } catch (Exception e) {
            log.debug("fusion tick failed: {}", e.toString());
        }
    }

    /**
     * Feeds this cycle's marks to the σ sensor and applies the ADR-0086 trailing exit to the book.
     * Unwired (no sensor or no cut) leaves the book byte-identical, which is exactly the behaviour
     * before this control existed.
     *
     * <p>The sensor is sampled from the SAME prices the plan was made from — one read of the mark, so
     * the volatility that decides a cut and the price the excursion is measured against cannot come
     * from two different instants. On first sight of a name it is seeded from the durable mark history
     * at this loop's own cadence (ADR-0071), anchored on that mark's PROVIDER timestamp: its warm-up is
     * an hour of samples and the process lifetime is a fraction of that, so without the seed the sensor
     * would never speak and this control would be dead code.
     */
    /**
     * The ORIGINATION trigger for one routed delta (ADR-0134) — why the desk wanted this trade, named
     * where the planner still knows it. Four triggers the post-mortem must be able to tell apart: a
     * trailing-stop cut, an exit all the way to flat, a partial reduce toward a smaller target, and an
     * entry. The forecast and source count are carried along because they are what the desk acted ON;
     * both are read from the target the planner computed, never authored here (invariant 7).
     *
     * <p>Pure telemetry: nothing reads this string back, so it cannot change what is traded.
     */
    private static String originOf(FusionPlanner.Target t, boolean reducing, boolean stopped) {
        String trigger;
        if (stopped) {
            trigger = "ADR-0086 trailing risk cut — target flat";
        } else if (!reducing) {
            trigger = "fusion entry — target increase";
        } else if (t.targetQty().signum() == 0) {
            trigger = "fusion exit — target decayed to flat";
        } else {
            trigger = "fusion reduce toward a smaller target";
        }
        return trigger + " [forecast=" + t.combinedForecast() + ", sources=" + t.sources() + "]";
    }

    private TrailingRiskCut.Result applyRiskCut(List<FusionPlanner.Target> targets, long now,
                                                long horizonSeconds, FusionPlanner.Params cycleParams) {
        if (riskCut == null || streamVol == null) {
            return new TrailingRiskCut.Result(targets, List.of(), 0, 0);
        }
        for (FusionPlanner.Target t : targets) {
            if (t.price() == null || t.price().signum() <= 0) {
                continue;
            }
            seedVolatility(t.instrument());
            // ADR-0116: the σ that sets the cut distance advances on the MARKET's clock, not on ours.
            // The plan is made once per cycle from a last-value cache, so on a quiet tape the same price
            // arrives here over and over; absorbing those as zero returns decays σ toward zero and puts
            // the ADR-0086 trigger at ~0, cutting every name held across the close on the first genuine
            // move of the next session. Same rule and same clock as ADR-0113 on the forecast sensors.
            streamVol.update(t.instrument(), t.price(), markInstant(t.instrument()));
        }
        var result = riskCut.apply(targets, now, horizonSeconds, intervalSeconds, streamVol, cycleParams);
        for (TrailingRiskCut.Cut c : result.cuts()) {
            log.warn("fusion risk cut: {} {} — gave back {}% from its peak against a {}% trigger "
                            + "({}σ over {}s); target flat, reduce-only until re-arm (ADR-0086)",
                    c.instrument(), c.side() > 0 ? "long" : "short",
                    String.format("%.4f", c.excursion() * 100.0),
                    String.format("%.4f", c.threshold() * 100.0),
                    String.format("%.4f", c.sigmaOverHorizon() * 100.0), c.horizonSeconds());
        }
        return result;
    }

    /**
     * The covariance this cycle's two sizing controls are measured with (ADR-0089): the mark-stream
     * estimate when it covers strictly more of the planned book than the daily-close estimate, the
     * daily-close estimate otherwise.
     *
     * <p><b>Why coverage and not preference.</b> Both are estimates of the same statistic — the
     * covariance of contemporaneous returns — measured over different sampling periods, and both
     * consumers are scale-invariant in Σ, so the choice cannot change the units the answer is read in
     * (see {@link StreamCovariance}). What it does change is how many names carry a measurement at
     * all, and a control that covers nothing is not a conservative control, it is an absent one. The
     * tie goes to the incumbent daily-close estimate, so this can only ever ADD coverage: on a desk
     * whose daily series has matured the behaviour is exactly what it was before.
     *
     * <p><b>One estimator, never a blend.</b> A matrix stitched from two sources is not a covariance
     * of anything — its off-diagonals would be measured over a different period than its diagonals, and
     * the quadratic forms above would mix them. Both controls read from the ONE source chosen here.
     *
     * <p>The stream estimator is fed the same prices the plan was just made from — one read of the
     * mark per cycle, so the sizes and the correlation that scales them cannot come from two different
     * instants — and is seeded on first sight from the durable mark history on a shared bucket grid
     * (ADR-0071/0089), because its warm-up is an hour of samples and the process lifetime is a
     * fraction of that.
     */
    private Basis sizingCovariance(ReturnCovarianceSource dailyCov, List<FusionPlanner.Target> targets) {
        List<String> planned = new java.util.ArrayList<>(targets.size());
        Map<String, BigDecimal> sample = new java.util.LinkedHashMap<>();
        for (FusionPlanner.Target t : targets) {
            planned.add(t.instrument());
            if (t.price() != null && t.price().signum() > 0) {
                sample.put(t.instrument(), t.price());
            }
        }
        int dailyNames = coveredCount(dailyCov, planned);
        if (streamCov == null) {
            return new Basis(dailyNames > 0 ? Basis.DAILY_NAME : Basis.NONE_NAME, dailyCov);
        }
        seedCovariance(sample.keySet());
        streamCov.update(sample);
        int streamNames = streamCov.measuredNames(planned);
        if (streamNames > dailyNames) {
            return new Basis(Basis.STREAM_NAME, streamCov.asSource());
        }
        return new Basis(dailyNames > 0 ? Basis.DAILY_NAME : Basis.NONE_NAME, dailyCov);
    }

    /** How many of {@code planned} carry a measured variance in {@code cov} — coverage, never a size. */
    private static int coveredCount(ReturnCovarianceSource cov, List<String> planned) {
        if (cov == null) {
            return 0;
        }
        int n = 0;
        for (String id : planned) {
            if (cov.covariance(id, id).isPresent()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Replays the durable mark history into the ADR-0089 covariance estimator, as SYNCHRONISED
     * snapshots on one bucket grid, the first time any name needs it. Unlike the per-name σ seed this
     * must be replayed for the whole cross-section at once — a covariance of returns taken at different
     * instants measures the misalignment — so the seed is re-run whenever the planned universe grows,
     * and the estimator's own warm-up counter decides when a pair may speak.
     */
    private void seedCovariance(java.util.Collection<String> instruments) {
        if (markHistory == null || streamCov == null || covSeeded.containsAll(instruments)) {
            return;
        }
        covSeeded.addAll(instruments);
        Long providerMillis = null;
        for (String id : instruments) {
            Long t = markTimeFor == null ? null : markTimeFor.apply(id);
            if (t != null && t > 0 && (providerMillis == null || t > providerMillis)) {
                providerMillis = t; // the newest provider stamp on the book — one clock, the feed's
            }
        }
        long anchor = providerMillis != null ? providerMillis : System.currentTimeMillis();
        // Snapshots, not joint returns: N snapshots yield N−1 returns for a pair present throughout,
        // so the replay must be one longer than the estimator's warm-up (ADR-0117).
        var samples = SensorWarmup.jointSeedSamples(markHistory, instruments, anchor,
                intervalSeconds * 1_000L, streamCov.warmupSnapshots());
        for (var s : samples) {
            streamCov.update(s);
        }
        int measured = streamCov.measuredNames(instruments);
        if (measured == 0) {
            // WARN, not INFO: with no covered name the concentration control is silent, which is the
            // exact failure this seed exists to prevent (the ADR-0071 correction's lesson).
            log.warn("fusion covariance still cold after seeding {} synchronised snapshots of {} name(s) "
                            + "— the book cannot be scaled for concentration until the mark history "
                            + "accumulates (ADR-0089)", samples.size(), instruments.size());
        } else {
            log.info("fusion covariance warmed {} of {} name(s) from {} synchronised snapshots (ADR-0089)",
                    measured, instruments.size(), samples.size());
        }
    }

    /**
     * This name's mark as timestamped by the FEED (invariant 5), or null when no provider clock is
     * wired — in which case {@link PrintClock} admits every sample and behaviour is exactly what it was
     * before ADR-0116, which is what a test harness or a caller without a mark cache needs.
     */
    private java.time.Instant markInstant(String instrument) {
        Long millis = markTimeFor == null ? null : markTimeFor.apply(instrument);
        return millis == null || millis <= 0 ? null : java.time.Instant.ofEpochMilli(millis);
    }

    /**
     * ADR-0126 — can this name be stopped out? True when the ADR-0086 σ sensor has warmed enough to
     * price a cut distance for it. With no sensor wired there is no claim to make and every name reads
     * armed, which leaves the book byte-identical to the behaviour before this control existed.
     *
     * <p>Read from the sensor's own warm-up state, not from a threshold: the same
     * {@code sigmaPerSample(...).isPresent()} that {@link #seedVolatility} logs the cold warning from
     * and that {@link TrailingRiskCut} requires before it will cut. So "the desk may open it" and "the
     * risk cut can protect it" are the same question answered in one place, and the answer arrives
     * exactly one cycle after the seed succeeds — no number is introduced (invariant 7 / ADR-0016).
     */
    private boolean stopArmed(String instrument) {
        return streamVol == null || streamVol.sigmaPerSample(instrument).isPresent();
    }

    /** Replays this name's stored recent prices into the σ sensor the first time it is planned. */
    private void seedVolatility(String instrument) {
        if (markHistory == null || !volSeeded.add(instrument)) {
            return;
        }
        Long providerMillis = markTimeFor == null ? null : markTimeFor.apply(instrument);
        long anchor = providerMillis != null && providerMillis > 0 ? providerMillis : System.currentTimeMillis();
        // The seed is counted in PRICES, the sensor in RETURNS, and a return needs two prices
        // (ADR-0117) — asking for warmupSamples() prices lands the replay one return short every time.
        var seed = SensorWarmup.warm(markHistory, instrument, anchor, intervalSeconds * 1_000L,
                streamVol.warmupPrices(), price -> streamVol.update(instrument, price));
        if (streamVol.sigmaPerSample(instrument).isEmpty()) {
            // WARN, not INFO: an unmeasured name is one the risk cut can never protect, and that has
            // to be loud enough to reach the report (the ADR-0071 correction's lesson). Quoted against
            // what the seed ASKED FOR, so "n of n, still cold" can only ever mean a genuine cold start,
            // and with the terminator so a short seed says WHY it is short (ADR-0138).
            log.warn("risk-cut σ sensor still cold for {} after seeding {} of {} stored prices — stopped "
                    + "on {} covering {}s in {} read(s) at a {}ms step; this name cannot be stopped out "
                    + "until its mark history has accumulated", instrument, seed.size(),
                    streamVol.warmupPrices(), seed.termination(), seed.spanMillis() / 1000L, seed.reads(),
                    seed.stepMillis());
        } else {
            log.info("risk-cut σ sensor warmed {} from {} stored prices (ADR-0086)", instrument, seed.size());
        }
    }

    /**
     * Every target the gate declines to let grow, with its delta projected onto "reduce or hold"
     * (ADR-0064 desk-wide, ADR-0072 per name). Targets the gate allows are returned untouched, so a
     * shut gate still clamps everything and an open one clamps only the names that cannot pay for
     * themselves.
     */
    private static List<FusionPlanner.Target> reduceOnlyWhere(List<FusionPlanner.Target> targets,
                                                              EdgeGate.Decision gate) {
        List<FusionPlanner.Target> out = new java.util.ArrayList<>(targets.size());
        for (FusionPlanner.Target t : targets) {
            BigDecimal clamped = gate.mayIncrease(t.instrument())
                    ? t.deltaQty()
                    : TargetPlanner.reduceOnly(t.deltaQty(), t.currentQty());
            out.add(clamped.compareTo(t.deltaQty()) == 0 ? t
                    : new FusionPlanner.Target(t.instrument(), t.combinedForecast(), t.sources(),
                            t.diversificationMultiplier(), t.agreement(), t.price(), t.targetQty(), t.currentQty(),
                            clamped, t.contributions()));
        }
        return out;
    }

    public TargetBook book() {
        return lastBook;
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}

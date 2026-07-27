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
                             int streamVolMeasuredNames,
                             long forecastSmoothingSeconds, int forecastSmoothedNames) {
        static TargetBook empty() {
            return new TargetBook(0, false, 0, Map.of(), List.of(), null, 1.0, 0, 0, 1.0, 1.0,
                    List.of(), 0, 0, 0L, 0);
        }
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
    /** ADR-0088: averages each name's conviction over the horizon its edge is measured on — null ⇒ off. */
    private final ForecastSmoother forecastSmoother;
    /** ADR-0071: durable mark history, so the σ sensor is not permanently cold on a redeployed desk. */
    private final SensorWarmup.History markHistory;
    /** Provider timestamp of a name's current mark — the σ sensor's seed anchor (ADR-0071 correction). */
    private final Function<String, Long> markTimeFor;
    /** Instruments already seeded into the σ sensor — touched only from the scheduled tick thread. */
    private final java.util.Set<String> volSeeded = new java.util.HashSet<>();

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
                           SensorWarmup.History markHistory, Function<String, Long> markTimeFor,
                           ForecastSmoother forecastSmoother) {
        this.forecastSmoother = forecastSmoother;
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
            // ADR-0080/0082/0088: ONE horizon per cycle. It is the period the evidence selected, and it
            // now sets three things that must never disagree — the return the gate credited, how long
            // the position is held, and how long the CONVICTION is averaged over.
            long horizon = gate != null && gate.horizonSeconds() > 0 ? gate.horizonSeconds() : baseHorizonSeconds;
            // ADR-0088: filter the fused conviction over that same horizon, so a view that reverses
            // faster than the desk can be graded on it is averaged down instead of dragging the book
            // through a round trip it was never credited for.
            ForecastSmoother.Smoothing smoothing = forecastSmoother == null ? ForecastSmoother.NONE
                    : (id, value) -> forecastSmoother.smooth(id, value, now, horizon);
            // ADR-0065: plan over the names we HOLD as well as the names we have a view on, so a
            // position never falls out of the target book when its sources go quiet.
            java.util.Set<String> held = heldSupplier == null ? java.util.Set.of() : heldSupplier.get();
            List<FusionPlanner.Target> targets = FusionPlanner.plan(forecasts, held, weights::weightFor, priceFor,
                    multiplierFor, id -> positions.getOrDefault(id, BigDecimal.ZERO), cycleParams, smoothing);
            ReturnCovarianceSource cov = covariance == null ? ReturnCovarianceSource.NONE : covariance.get();
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
            var cut = applyRiskCut(targets, now, horizon, cycleParams);
            targets = cut.targets();
            lastBook = new TargetBook(now, routeOrders, targets.size(), weights.snapshot(), targets, gate,
                    normalised.multiplier(), normalised.coveredNames(),
                    budgeted.coveredNames(), budgeted.dispersion(), budgeted.leverCap(),
                    cut.cuts(), cut.stoppedNames(),
                    streamVol == null ? 0 : streamVol.measuredNames(),
                    forecastSmoother == null ? 0L : horizon,
                    forecastSmoother == null ? 0 : forecastSmoother.trackedNames());
            if (routeOrders) {
                int routed = 0;
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
                    if (executor.route(t.instrument(), t.deltaQty(), reducing).routed()) {
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
            streamVol.update(t.instrument(), t.price());
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

    /** Replays this name's stored recent prices into the σ sensor the first time it is planned. */
    private void seedVolatility(String instrument) {
        if (markHistory == null || !volSeeded.add(instrument)) {
            return;
        }
        Long providerMillis = markTimeFor == null ? null : markTimeFor.apply(instrument);
        long anchor = providerMillis != null && providerMillis > 0 ? providerMillis : System.currentTimeMillis();
        int n = SensorWarmup.warm(markHistory, instrument, anchor, intervalSeconds * 1_000L,
                streamVol.warmupSamples(), price -> streamVol.update(instrument, price));
        if (streamVol.sigmaPerSample(instrument).isEmpty()) {
            // WARN, not INFO: an unmeasured name is one the risk cut can never protect, and that has
            // to be loud enough to reach the report (the ADR-0071 correction's lesson).
            log.warn("risk-cut σ sensor still cold for {} after seeding {} of {} stored prices — this "
                    + "name cannot be stopped out until its mark history has accumulated", instrument, n,
                    streamVol.warmupSamples());
        } else {
            log.info("risk-cut σ sensor warmed {} from {} stored prices (ADR-0086)", instrument, n);
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
                            t.diversificationMultiplier(), t.price(), t.targetQty(), t.currentQty(),
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

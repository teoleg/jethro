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
                             int volBudgetNames, double volBudgetDispersion, double volBudgetLeverCap) {
        static TargetBook empty() {
            return new TargetBook(0, false, 0, Map.of(), List.of(), null, 1.0, 0, 0, 1.0, 1.0);
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
            lastBook = new TargetBook(now, routeOrders, targets.size(), weights.snapshot(), targets, gate,
                    normalised.multiplier(), normalised.coveredNames(),
                    budgeted.coveredNames(), budgeted.dispersion(), budgeted.leverCap());
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

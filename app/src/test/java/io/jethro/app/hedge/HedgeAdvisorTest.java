package io.jethro.app.hedge;

import io.jethro.trading.riskpnl.CovMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hedge advisor's target-flat, two-tier, HELD-HEDGE-FEEDBACK and PROXY-SELECTION behaviour
 * (ADR-0039/0040/0042 and the 2026-07-18 math review P1-1): the proposal is always
 * {@code target − held}, a book on target proposes nothing, a flat book unwinds its residual
 * hedge, and the proxy is chosen by measured ρ² with switch hysteresis. Hand-computed
 * covariances (ρ=0.9 ES/STOCK as in HedgeMathTest; ρ=0.95 NQ/STOCK) keep every size checkable.
 */
class HedgeAdvisorTest {

    /** ES + STOCK only (NQ uncovered): σ_ES=1%, σ_STOCK=2%, ρ=0.9 → β=1.8, ρ²=0.81. */
    private static CovMath.Covariance cov2() {
        double[][] sigma = {{1e-4, 1.8e-4}, {1.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 60);
    }

    /** ES, NQ and STOCK: NQ/STOCK ρ=0.95 → ρ²=0.9025 beats ES's 0.81; β_NQ=0.95. */
    private static CovMath.Covariance cov3() {
        double[][] sigma = {
                {1e-4, 1.8e-4, 1.8e-4},
                {1.8e-4, 4e-4, 3.8e-4},
                {1.8e-4, 3.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "NQ", "STOCK"), sigma, 60);
    }

    private static HedgeAdvisor advisor(HedgeAdvisor.Mode mode, String rebalanceFloorUsd) {
        return advisor(mode, rebalanceFloorUsd, 0.10);
    }

    private static HedgeAdvisor advisor(HedgeAdvisor.Mode mode, String rebalanceFloorUsd,
                                        double switchMargin) {
        // min-trade $10k (ADR-0039) with the 25%-of-scale relative leg (ADR-0069); min-covariance
        // 40d (ADR-0041; fixture covs have 60 obs); candidates ES,NQ with refdata-style
        // multipliers (ES 50, NQ 20); structural proxy ES.
        return new HedgeAdvisor(mode, new BigDecimal(rebalanceFloorUsd), new BigDecimal("10000"),
                new BigDecimal("0.25"), 0.25, 40, List.of("ES", "NQ"), switchMargin, "ES", MULTIPLIERS);
    }

    private static final Function<String, Optional<BigDecimal>> MULTIPLIERS = id -> switch (id) {
        case "ES" -> Optional.of(new BigDecimal("50"));
        case "NQ" -> Optional.of(new BigDecimal("20"));
        default -> Optional.empty();
    };
    private static final Function<String, Optional<BigDecimal>> PRICES = id -> switch (id) {
        case "ES" -> Optional.of(new BigDecimal("5600"));   // × 50 → $280k/contract
        case "NQ" -> Optional.of(new BigDecimal("20000"));  // × 20 → $400k/contract
        default -> Optional.empty();
    };
    private static final java.util.function.Predicate<String> IS_EQUITY = "STOCK"::equals;
    private static final Function<String, Optional<BigDecimal>> NO_BETA = id -> Optional.empty();
    private static final Function<String, Optional<BigDecimal>> STOCK_BETA_1_2 =
            id -> "STOCK".equals(id) ? Optional.of(new BigDecimal("1.2")) : Optional.empty();
    private static final Map<String, BigDecimal> NONE_HELD = Map.of();
    private static final Predicate<String> ALL_TRADABLE = id -> true;
    private static final Map<String, BigDecimal> LONG_1M = Map.of("STOCK", new BigDecimal("1000000"));

    private static HedgeAdvisor.Axis eval(HedgeAdvisor a, Optional<CovMath.Covariance> cov,
                                          Map<String, BigDecimal> exposures,
                                          Function<String, Optional<BigDecimal>> betaOf,
                                          Map<String, BigDecimal> held, Predicate<String> tradable) {
        return a.evaluate(cov, exposures, IS_EQUITY, PRICES, betaOf, held, tradable).axes().get(0);
    }

    @Test
    void firstHedgeProposesTheFullTarget() {
        var axis = eval(advisor(HedgeAdvisor.Mode.ADVISE, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("STATISTICAL", axis.tier());
        assertEquals("ES", axis.proxyId(), "NQ is not in this covariance — ES is the only sized candidate");
        assertEquals("SELL", axis.hedgeSide());
        assertEquals("HEDGE", axis.status());
        assertEquals(0.81, axis.effectiveness(), 1e-9);
        // β̂=1.8 → target −6.428571 ES; held 0 → the delta IS the full target.
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void onTargetBookProposesNothing_theP1RunawayRegression() {
        // The exact runaway scenario: the hedge from the last cycle is ON (held = target).
        // Without held-hedge feedback the advisor would propose the FULL hedge again forever.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertFalse(axis.hedging(), "held == target — nothing to trade");
        assertNull(axis.hedgeQuantity());
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void partialHoldingTopsUpOnlyTheDelta() {
        var axis = eval(advisor(HedgeAdvisor.Mode.ADVISE, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-4")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("SELL", axis.hedgeSide());
        // target −6.428571, held −4 → delta −2.428571.
        assertEquals(0, new BigDecimal("2.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void flatBookUnwindsTheResidualHedge() {
        // Equities all closed; a −6.4 ES hedge is still on. No underlying → no hedge (ADR-0039).
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of(), NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide(), "closing a short hedge buys it back");
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void deltaUnderTheMinTradeNotionalHolds() {
        // held −6.4 vs target −6.428571 → delta 0.028571 ES ≈ $8,000 < $10k min trade.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.4")), ALL_TRADABLE);
        assertFalse(axis.hedging(), "an $8k trim is churn, not a hedge");
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void smallBookResidualHedgeIsTrimmedNotStranded() {
        // ADR-0069, the live 2026-07-26 defect: the equity book collapsed to a fraction of its
        // former size, leaving a hedge far above its own target. β̂=1.8 on $2,800 net equity →
        // target −0.018 ES ($5,040 at $280k/contract); held −0.045 ES ($12,600). The delta is
        // 0.027 ES = $7,560 — under the $10k ABSOLUTE guard, so the pure-ADR-0039 rule held it
        // forever and $7.6k of unwanted proxy exposure sat on the firm book. The relative leg is
        // 25% × $12,600 = $3,150, and $7,560 clears it, so the excess is trimmed.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of("STOCK", new BigDecimal("2800")), NO_BETA,
                Map.of("ES", new BigDecimal("-0.045")), ALL_TRADABLE);
        assertTrue(axis.hedging(), "a 150%-of-target excess is not churn: " + axis.rationale());
        assertEquals("BUY", axis.hedgeSide(), "buying back part of a too-short hedge");
        assertEquals(0, new BigDecimal("0.027").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void tinyResidualHedgeIsAlwaysUnwound() {
        // ADR-0039 Decision 1 promises "no underlying, no hedge". Under a pure absolute guard that
        // promise failed silently for any residual smaller than the guard: equities flat, held
        // −0.02 ES = $5,600 < $10k, so the naked proxy leg lingered. With the band at 25% of the
        // hedge's own scale ($1,400) a zero target is reachable at any size.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of(), NO_BETA, Map.of("ES", new BigDecimal("-0.02")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("0.02").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void smallBookStillDampsSubBandChurn() {
        // The relative leg must not turn into a churn machine: β̂=1.8 on $3,080 → target −0.0198
        // ES, held −0.02 → a 0.0002 ES / $56 trim, which is 1% of the $5,600 hedge and far under
        // the $1,400 band. Nothing trades.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of("STOCK", new BigDecimal("3080")), NO_BETA,
                Map.of("ES", new BigDecimal("-0.02")), ALL_TRADABLE);
        assertFalse(axis.hedging(), "a 1% drift is churn at any book size: " + axis.rationale());
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void structuralTierHedgesTheDeltaWhenNoCovariance() {
        // No covariance — structural target from assigned β=1.2: −1.2M/280k = −4.285714 ES;
        // held −2 → SELL 2.285714 more.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.empty(),
                LONG_1M, STOCK_BETA_1_2, Map.of("ES", new BigDecimal("-2")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("STRUCTURAL", axis.tier());
        assertEquals("SELL", axis.hedgeSide());
        assertNull(axis.effectiveness(), "structural effectiveness is asserted, never a measured ρ²");
        assertEquals(0, new BigDecimal("2.285714").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void seedHeavyCovarianceYieldsToTheStructuralTier() {
        // Only 25 observations — under the 40-day ADR-0041 gate: the estimate is still too
        // seed-heavy to size real money, so the assigned-beta structural tier carries the book.
        var thin = new CovMath.Covariance(cov2().instruments(), cov2().sigma(), 25);
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(thin),
                LONG_1M, STOCK_BETA_1_2, NONE_HELD, ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("STRUCTURAL", axis.tier(), "statistical gated below 40 obs");
        assertEquals(0, new BigDecimal("4.285714").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void picksTheHigherEffectivenessProxy() {
        // ADR-0042: NQ hedges this book better (ρ² 0.9025 vs ES 0.81) → buy the best option.
        // β_NQ = 3.8e-4/4e-4 = 0.95 → notional −$950k → 950k/400k = 2.375 NQ.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov3()),
                LONG_1M, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertEquals("NQ", axis.proxyId());
        assertEquals("SELL", axis.hedgeSide());
        assertEquals(0.9025, axis.effectiveness(), 1e-9);
        assertEquals(0, new BigDecimal("2.375").compareTo(axis.hedgeQuantity()));
        assertTrue(axis.rationale().contains("→ NQ"), "the comparison is shown: " + axis.rationale());
    }

    @Test
    void sticksWithTheHeldProxyInsideTheSwitchMargin() {
        // NQ is better by 0.0925 but the margin is 0.10 — flipping instruments on estimation
        // noise pays two spreads; the held ES hedge stays (and is already on target).
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0", 0.10), Optional.of(cov3()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertEquals("ES", axis.proxyId());
        assertEquals("ON-TARGET", axis.status());
        assertFalse(axis.hedging());
    }

    @Test
    void switchesProxiesByUnwindingTheOldOneFirst() {
        // Margin 0.05 < NQ's 0.0925 edge → switch. Cycle one UNWINDS the ES hedge (the larger
        // delta); the NQ hedge builds on a later cycle once ES is flat.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0", 0.05), Optional.of(cov3()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertEquals("ES", axis.proxyId(), "old proxy unwinds before the new builds");
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
        assertTrue(axis.rationale().contains("before building the NQ hedge"), axis.rationale());
    }

    @Test
    void quarantinedProxyIsNeverBought() {
        // NQ measures better but is in no shape to trade (mark-quarantined) → ES is chosen.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov3()),
                LONG_1M, NO_BETA, NONE_HELD, id -> !"NQ".equals(id));
        assertEquals("ES", axis.proxyId());
        assertEquals("SELL", axis.hedgeSide());
        assertEquals(0.81, axis.effectiveness(), 1e-9);
    }

    @Test
    void cannotSizeWhenNeitherCovarianceNorBeta() {
        var axis = eval(advisor(HedgeAdvisor.Mode.ADVISE, "0"), Optional.empty(),
                LONG_1M, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertFalse(axis.hedging());
        assertNull(axis.hedgeQuantity());
        assertEquals("WARMING", axis.status());
    }

    // ---------------------------------------------------------------------------------------
    // ADR-0095: the mark-stream basis, and a measurement that says no
    // ---------------------------------------------------------------------------------------

    /** ES + STOCK measured on the MARK STREAM: same shape as {@link #cov2()}, but the entries are
     *  the (co)variance of a return over one SAMPLING INTERVAL — here 1/100th of cov2()'s scale.
     *  β and ρ² must come out identical; only the absolute σ differs. */
    private static CovMath.Covariance streamCov2() {
        double[][] sigma = {{1e-6, 1.8e-6}, {1.8e-6, 4e-6}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 300);
    }

    /** ES/STOCK ρ=0.4 → ρ²=0.16, under the 0.25 floor: the proxy does not hedge this book.
     *  σ_ES=1%, σ_STOCK=2% → Σ[ES,STOCK] = 0.4·0.01·0.02 = 8e-5. */
    private static CovMath.Covariance weakCov() {
        double[][] sigma = {{1e-4, 8e-5}, {8e-5, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 60);
    }

    private static HedgeAdvisor.Axis evalStream(HedgeAdvisor a, Optional<CovMath.Covariance> daily,
                                                Optional<CovMath.Covariance> stream,
                                                Map<String, BigDecimal> exposures,
                                                Function<String, Optional<BigDecimal>> betaOf,
                                                Map<String, BigDecimal> held) {
        return a.evaluate(daily, stream, exposures, IS_EQUITY, PRICES, betaOf, held, ALL_TRADABLE)
                .axes().get(0);
    }

    @Test
    void streamCovarianceSizesTheHedgeWhenTheDailySeriesCoversNothing() {
        // The live defect: the daily-close series is empty for every name the desk holds, so the
        // hedge sized itself from assigned betas forever (−4.285714 ES at β=1.2) and never measured
        // its own ρ². With the stream basis the SAME minimum-variance answer as cov2() appears.
        var axis = evalStream(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.empty(),
                Optional.of(streamCov2()), LONG_1M, STOCK_BETA_1_2, NONE_HELD);
        assertTrue(axis.hedging());
        assertEquals("STATISTICAL-STREAM", axis.tier());
        assertEquals("SELL", axis.hedgeSide());
        assertEquals(0.81, axis.effectiveness(), 1e-9, "ρ² is measured, no longer null");
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void theSamplingPeriodCannotMoveTheHedgeOrTheFloorTest() {
        // ADR-0095's admissibility argument, as a test: β and ρ² are homogeneous of degree ZERO in
        // Σ, so a covariance measured over a 100× shorter interval sizes exactly the same hedge.
        var daily = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, NONE_HELD, ALL_TRADABLE);
        var stream = evalStream(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.empty(),
                Optional.of(streamCov2()), LONG_1M, NO_BETA, NONE_HELD);
        assertEquals(0, daily.hedgeQuantity().compareTo(stream.hedgeQuantity()));
        assertEquals(daily.effectiveness(), stream.effectiveness(), 1e-12);
        // ...but the ABSOLUTE σ is not scale-free, so it is withheld rather than mislabelled.
        assertNull(stream.grossSigmaUsd(), "a per-interval σ is never surfaced as a daily σ");
        assertNull(stream.residualSigmaUsd());
        assertEquals(0, new BigDecimal("20000.00").compareTo(daily.grossSigmaUsd()),
                "the daily-sourced σ is still reported: √(1e6²·4e-4) = $20,000/day");
    }

    @Test
    void aMeasurementBelowTheFloorUnwindsTheHedgeInsteadOfFallingBackToAssignedBetas() {
        // The precedence defect: ρ²=0.16 is a MEASUREMENT that this proxy does not hedge this book.
        // The old code fell through to the structural tier and hedged −4.285714 ES on assigned
        // β=1.2 anyway, which made the effectiveness floor unreachable by construction.
        var axis = evalStream(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(weakCov()),
                Optional.empty(), LONG_1M, STOCK_BETA_1_2, Map.of("ES", new BigDecimal("-4")));
        assertTrue(axis.hedging());
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide(), "buying back a hedge measured not to hedge");
        assertEquals(0, new BigDecimal("4").compareTo(axis.hedgeQuantity()));
        assertEquals(0.16, axis.effectiveness(), 1e-9);
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.targetProxyQty()));
    }

    @Test
    void noMeasurementAtAllStillFallsToTheStructuralTier() {
        // ADR-0040's floor is untouched: with NEITHER series covering the book, assigned betas
        // carry it exactly as before. Only a measurement may override a measurement.
        var axis = evalStream(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.empty(),
                Optional.empty(), LONG_1M, STOCK_BETA_1_2, NONE_HELD);
        assertTrue(axis.hedging());
        assertEquals("STRUCTURAL", axis.tier());
        assertEquals(0, new BigDecimal("4.285714").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void theDailySeriesWinsWhenItCanMeasureTheBook() {
        // Precedence: the stream basis is a stand-in for an absent measurement, never a preference.
        // Here both cover the book and the daily estimate is the one that sizes.
        var axis = evalStream(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Optional.of(streamCov2()), LONG_1M, NO_BETA, NONE_HELD);
        assertEquals("STATISTICAL", axis.tier());
        assertEquals(0, new BigDecimal("20000.00").compareTo(axis.grossSigmaUsd()));
    }

    @Test
    void theStreamBasisIsNotHeldToTheSessionGate() {
        // ADR-0041's min-covariance-days counts SESSIONS of the daily-close series. The stream
        // estimator counts sampling intervals and warm-gates its own pairs, so a 30-observation
        // stream matrix is not silently thrown away by a 40-session gate.
        var young = new CovMath.Covariance(streamCov2().instruments(), streamCov2().sigma(), 30);
        var axis = evalStream(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.empty(),
                Optional.of(young), LONG_1M, STOCK_BETA_1_2, NONE_HELD);
        assertEquals("STATISTICAL-STREAM", axis.tier());
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void offModeProposesNothing() {
        var snap = advisor(HedgeAdvisor.Mode.OFF, "0")
                .evaluate(Optional.of(cov2()), LONG_1M, IS_EQUITY, PRICES, STOCK_BETA_1_2,
                        NONE_HELD, ALL_TRADABLE);
        assertFalse(snap.axes().get(0).hedging());
        assertEquals("OFF", snap.mode());
        assertEquals("OFF", snap.axes().get(0).status());
    }

    @Test
    void onlyEquityNamesEnterTheAxisNotTheProxyItself() {
        // A held ES position (the hedge itself) must not count as equity exposure — it enters
        // through the held-quantity feedback, never through the axis sum.
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "0").evaluate(Optional.of(cov2()),
                Map.of("STOCK", new BigDecimal("1000000"), "ES", new BigDecimal("9000000")),
                Set.of("STOCK")::contains, PRICES, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertEquals(0, new BigDecimal("1000000.00").compareTo(snap.axes().get(0).netExposureUsd()));
    }
}

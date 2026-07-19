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
        // min-trade $10k (ADR-0039); min-covariance 40d (ADR-0041; fixture covs have 60 obs);
        // candidates ES,NQ with refdata-style multipliers (ES 50, NQ 20); structural proxy ES.
        return new HedgeAdvisor(mode, new BigDecimal(rebalanceFloorUsd), new BigDecimal("10000"),
                0.25, 40, List.of("ES", "NQ"), switchMargin, "ES", MULTIPLIERS);
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

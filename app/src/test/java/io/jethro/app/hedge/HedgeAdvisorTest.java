package io.jethro.app.hedge;

import io.jethro.trading.riskpnl.CovMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hedge advisor's target-flat, two-tier and HELD-HEDGE-FEEDBACK behaviour (ADR-0039/0040 and
 * the 2026-07-18 math review P1-1): the proposal is always {@code target − held}, so a book already
 * on target proposes nothing, and a book whose equities went flat unwinds its residual hedge. Same
 * hand-computed covariance as HedgeMathTest (ES/STOCK, ρ=0.9) so the sized numbers are checkable.
 */
class HedgeAdvisorTest {

    private static CovMath.Covariance cov() {
        double[][] sigma = {{1e-4, 1.8e-4}, {1.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 60);
    }

    private static HedgeAdvisor advisor(HedgeAdvisor.Mode mode, String rebalanceFloorUsd) {
        // min-trade $10k (ADR-0039); ES multiplier 50.
        return new HedgeAdvisor(mode, new BigDecimal(rebalanceFloorUsd), new BigDecimal("10000"),
                0.25, "ES", new BigDecimal("50"));
    }

    private static final java.util.function.Predicate<String> IS_EQUITY = "STOCK"::equals;
    private static final Function<String, Optional<BigDecimal>> ES_AT_5600 =
            id -> "ES".equals(id) ? Optional.of(new BigDecimal("5600")) : Optional.empty();
    private static final Function<String, Optional<BigDecimal>> NO_BETA = id -> Optional.empty();
    private static final Function<String, Optional<BigDecimal>> STOCK_BETA_1_2 =
            id -> "STOCK".equals(id) ? Optional.of(new BigDecimal("1.2")) : Optional.empty();
    private static final BigDecimal NONE_HELD = BigDecimal.ZERO;
    private static final Map<String, BigDecimal> LONG_1M = Map.of("STOCK", new BigDecimal("1000000"));

    @Test
    void firstHedgeProposesTheFullTarget() {
        var axis = advisor(HedgeAdvisor.Mode.ADVISE, "0")
                .evaluate(Optional.of(cov()), LONG_1M, IS_EQUITY, ES_AT_5600, NO_BETA, NONE_HELD)
                .axes().get(0);
        assertTrue(axis.hedging());
        assertEquals("STATISTICAL", axis.tier());
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
        var axis = advisor(HedgeAdvisor.Mode.AUTO, "0")
                .evaluate(Optional.of(cov()), LONG_1M, IS_EQUITY, ES_AT_5600, NO_BETA,
                        new BigDecimal("-6.428571"))
                .axes().get(0);
        assertFalse(axis.hedging(), "held == target — nothing to trade");
        assertFalse(axis.hedgeRecommended());
        assertNull(axis.hedgeQuantity());
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void partialHoldingTopsUpOnlyTheDelta() {
        var axis = advisor(HedgeAdvisor.Mode.ADVISE, "0")
                .evaluate(Optional.of(cov()), LONG_1M, IS_EQUITY, ES_AT_5600, NO_BETA,
                        new BigDecimal("-4"))
                .axes().get(0);
        assertTrue(axis.hedging());
        assertEquals("SELL", axis.hedgeSide());
        // target −6.428571, held −4 → delta −2.428571.
        assertEquals(0, new BigDecimal("2.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void flatBookUnwindsTheResidualHedge() {
        // Equities all closed; a −6.4 ES hedge is still on. No underlying → no hedge (ADR-0039).
        var axis = advisor(HedgeAdvisor.Mode.AUTO, "0")
                .evaluate(Optional.of(cov()), Map.of(), IS_EQUITY, ES_AT_5600, NO_BETA,
                        new BigDecimal("-6.428571"))
                .axes().get(0);
        assertTrue(axis.hedging());
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide(), "closing a short hedge buys it back");
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void deltaUnderTheMinTradeNotionalHolds() {
        // held −6.4 vs target −6.428571 → delta 0.028571 ES ≈ $8,000 < $10k min trade.
        var axis = advisor(HedgeAdvisor.Mode.AUTO, "0")
                .evaluate(Optional.of(cov()), LONG_1M, IS_EQUITY, ES_AT_5600, NO_BETA,
                        new BigDecimal("-6.4"))
                .axes().get(0);
        assertFalse(axis.hedging(), "an $8k trim is churn, not a hedge");
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void structuralTierHedgesTheDeltaWhenNoCovariance() {
        // No covariance — structural target from assigned β=1.2: −1.2M/280k = −4.285714 ES;
        // held −2 → SELL 2.285714 more.
        var axis = advisor(HedgeAdvisor.Mode.AUTO, "0")
                .evaluate(Optional.empty(), LONG_1M, IS_EQUITY, ES_AT_5600, STOCK_BETA_1_2,
                        new BigDecimal("-2"))
                .axes().get(0);
        assertTrue(axis.hedging());
        assertEquals("STRUCTURAL", axis.tier());
        assertEquals("SELL", axis.hedgeSide());
        assertNull(axis.effectiveness(), "structural effectiveness is asserted, never a measured ρ²");
        assertEquals(0, new BigDecimal("2.285714").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void cannotSizeWhenNeitherCovarianceNorBeta() {
        var axis = advisor(HedgeAdvisor.Mode.ADVISE, "0")
                .evaluate(Optional.empty(), LONG_1M, IS_EQUITY, ES_AT_5600, NO_BETA, NONE_HELD)
                .axes().get(0);
        assertFalse(axis.hedging());
        assertNull(axis.hedgeQuantity());
        assertEquals("WARMING", axis.status());
    }

    @Test
    void offModeProposesNothing() {
        var snap = advisor(HedgeAdvisor.Mode.OFF, "0")
                .evaluate(Optional.of(cov()), LONG_1M, IS_EQUITY, ES_AT_5600, STOCK_BETA_1_2, NONE_HELD);
        assertFalse(snap.axes().get(0).hedging());
        assertEquals("OFF", snap.mode());
        assertEquals("OFF", snap.axes().get(0).status());
    }

    @Test
    void onlyEquityNamesEnterTheAxisNotTheProxyItself() {
        // A held ES position (the hedge itself) must not count as equity exposure — it enters
        // through the held-quantity feedback, never through the axis sum.
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "0").evaluate(Optional.of(cov()),
                Map.of("STOCK", new BigDecimal("1000000"), "ES", new BigDecimal("9000000")),
                Set.of("STOCK")::contains, ES_AT_5600, NO_BETA, NONE_HELD);
        assertEquals(0, new BigDecimal("1000000.00").compareTo(snap.axes().get(0).netExposureUsd()));
    }
}

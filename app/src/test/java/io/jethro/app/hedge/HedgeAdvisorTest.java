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
 * The hedge advisor's target-flat behaviour, two-tier selection and warm-up handling (ADR-0039/0040),
 * driving the tested {@link io.jethro.trading.riskpnl.HedgeMath}. Same hand-computed covariance as
 * HedgeMathTest (ES/STOCK, ρ=0.9) so the sized numbers are checkable. The book is held flat: any net
 * equity above the (small, anti-churn) rebalance floor is hedged — statistically when the covariance
 * is ready, structurally (assigned beta) otherwise; there is no band where it runs unhedged.
 */
class HedgeAdvisorTest {

    private static CovMath.Covariance cov() {
        double[][] sigma = {{1e-4, 1.8e-4}, {1.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 60);
    }

    private static HedgeAdvisor advisor(HedgeAdvisor.Mode mode, String rebalanceFloorUsd) {
        return new HedgeAdvisor(mode, new BigDecimal(rebalanceFloorUsd), 0.25, "ES", new BigDecimal("50"));
    }

    private static final java.util.function.Predicate<String> IS_EQUITY = "STOCK"::equals;
    private static final Function<String, Optional<BigDecimal>> ES_AT_5600 =
            id -> "ES".equals(id) ? Optional.of(new BigDecimal("5600")) : Optional.empty();
    private static final Function<String, Optional<BigDecimal>> NO_BETA = id -> Optional.empty();
    private static final Function<String, Optional<BigDecimal>> STOCK_BETA_1_2 =
            id -> "STOCK".equals(id) ? Optional.of(new BigDecimal("1.2")) : Optional.empty();

    @Test
    void belowTheRebalanceFloorNothingToHedge() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "2000000") // floor $2M, net $1M → treated as flat
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600, NO_BETA);
        var axis = snap.axes().get(0);
        assertFalse(axis.hedging(), "$1M is below the $2M anti-churn floor");
        assertFalse(axis.hedgeRecommended());
        assertNull(axis.hedgeQuantity(), "no hedge sized below the floor");
        assertEquals("FLAT", axis.status());
    }

    @Test
    void statisticalTierWinsWhenCovarianceReadyAndAboveFloor() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "500000") // floor $500k, net $1M → hedge
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY,
                        ES_AT_5600, STOCK_BETA_1_2); // beta present too — statistical still preferred
        var axis = snap.axes().get(0);
        assertTrue(axis.hedging());
        assertTrue(axis.hedgeRecommended(), "ρ²=0.81 clears the floor");
        assertEquals("STATISTICAL", axis.tier());
        assertEquals("SELL", axis.hedgeSide(), "hedge a long book by shorting the proxy");
        assertEquals("HEDGE", axis.status());
        assertEquals(0.81, axis.effectiveness(), 1e-9);
        // β̂=1.8 → $1.8M notional → 1.8M/280k = 6.428571 ES.
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
        assertEquals(0, new BigDecimal("8717.80").compareTo(axis.residualSigmaUsd()));
    }

    @Test
    void structuralTierHedgesWithAssignedBetaWhenNoCovariance() {
        // No covariance at all — the structural tier sizes from the assigned beta, no history.
        var snap = advisor(HedgeAdvisor.Mode.AUTO, "0")
                .evaluate(Optional.empty(), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY,
                        ES_AT_5600, STOCK_BETA_1_2);
        var axis = snap.axes().get(0);
        assertTrue(axis.hedging());
        assertTrue(axis.hedgeRecommended());
        assertEquals("STRUCTURAL", axis.tier());
        assertEquals("HEDGE", axis.status());
        assertEquals("SELL", axis.hedgeSide());
        assertNull(axis.effectiveness(), "structural effectiveness is asserted, never a measured ρ²");
        // systematic = 1.2 × $1M = $1.2M → 1,200,000 / (5,600 × 50 = 280,000) = 4.285714 ES.
        assertEquals(0, new BigDecimal("4.285714").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void cannotSizeWhenNeitherCovarianceNorBeta() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "0")
                .evaluate(Optional.empty(), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY,
                        ES_AT_5600, NO_BETA);
        var axis = snap.axes().get(0);
        assertTrue(axis.hedging(), "there IS net to hedge, we just can't size it yet");
        assertNull(axis.hedgeQuantity());
        assertEquals("WARMING", axis.status());
    }

    @Test
    void offModeProposesNothing() {
        var snap = advisor(HedgeAdvisor.Mode.OFF, "0")
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY,
                        ES_AT_5600, STOCK_BETA_1_2);
        assertFalse(snap.axes().get(0).hedging());
        assertFalse(snap.axes().get(0).hedgeRecommended());
        assertEquals("OFF", snap.mode());
        assertEquals("OFF", snap.axes().get(0).status());
    }

    @Test
    void onlyEquityNamesEnterTheAxisNotTheProxyItself() {
        // A held ES position must not count as equity exposure to be hedged (it IS the hedge).
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "0").evaluate(Optional.of(cov()),
                Map.of("STOCK", new BigDecimal("1000000"), "ES", new BigDecimal("9000000")),
                Set.of("STOCK")::contains, ES_AT_5600, NO_BETA);
        // net equity = STOCK only ($1M), ES excluded.
        assertEquals(0, new BigDecimal("1000000.00").compareTo(snap.axes().get(0).netExposureUsd()));
    }
}

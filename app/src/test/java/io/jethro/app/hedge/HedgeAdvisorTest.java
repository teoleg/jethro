package io.jethro.app.hedge;

import io.jethro.trading.riskpnl.CovMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hedge advisor's target-flat behaviour and warm-up handling (ADR-0039), driving the tested
 * {@link io.jethro.trading.riskpnl.HedgeMath} ratio. Same hand-computed covariance as HedgeMathTest
 * (ES/STOCK, ρ=0.9) so the sized numbers are checkable. The book is held flat: any net equity above
 * the (small, anti-churn) rebalance floor is hedged; there is no band inside which it runs unhedged.
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
    private static final java.util.function.Function<String, Optional<BigDecimal>> ES_AT_5600 =
            id -> "ES".equals(id) ? Optional.of(new BigDecimal("5600")) : Optional.empty();

    @Test
    void belowTheRebalanceFloorNothingToHedge() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "2000000") // floor $2M, net $1M → treated as flat
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
        var axis = snap.axes().get(0);
        assertFalse(axis.hedging(), "$1M is below the $2M anti-churn floor");
        assertFalse(axis.hedgeRecommended());
        assertNull(axis.hedgeQuantity(), "no hedge sized below the floor");
        assertEquals("FLAT", axis.status());
    }

    @Test
    void anyNetAboveAZeroFloorIsHedgedToFlat() {
        // The book is target-flat: with the default floor of 0, even a small net sizes a hedge.
        var snap = advisor(HedgeAdvisor.Mode.AUTO, "0")
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("12345")), IS_EQUITY, ES_AT_5600);
        var axis = snap.axes().get(0);
        assertTrue(axis.hedging(), "any net exposure is hedged when the floor is 0");
        assertTrue(axis.hedgeRecommended());
        assertEquals("SELL", axis.hedgeSide());
        assertEquals("HEDGE", axis.status());
    }

    @Test
    void netExposureSizesTheMinimumVarianceHedgeToFlat() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "500000") // floor $500k, net $1M → hedge
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
        var axis = snap.axes().get(0);
        assertTrue(axis.hedging());
        assertTrue(axis.hedgeRecommended(), "ρ²=0.81 clears the floor");
        assertEquals("SELL", axis.hedgeSide(), "hedge a long book by shorting the proxy");
        assertEquals("HEDGE", axis.status());
        assertEquals(0.81, axis.effectiveness(), 1e-9);
        // β=1.8 → $1.8M notional → 1.8M/280k = 6.428571 ES.
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
        assertEquals(0, new BigDecimal("8717.80").compareTo(axis.residualSigmaUsd()));
    }

    @Test
    void netExposureDuringCovarianceWarmupCannotSizeYet() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "500000")
                .evaluate(Optional.empty(), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
        var axis = snap.axes().get(0);
        assertTrue(axis.hedging());
        assertFalse(snap.covarianceReady());
        assertNull(axis.hedgeQuantity(), "no covariance → no sized hedge, but the net-to-hedge is still shown");
        assertEquals("WARMING", axis.status());
        assertTrue(axis.rationale().contains("warming up"), axis.rationale());
    }

    @Test
    void offModeProposesNothing() {
        var snap = advisor(HedgeAdvisor.Mode.OFF, "0")
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
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
                Set.of("STOCK")::contains, ES_AT_5600);
        // net equity = STOCK only ($1M), ES excluded.
        assertEquals(0, new BigDecimal("1000000.00").compareTo(snap.axes().get(0).netExposureUsd()));
    }
}

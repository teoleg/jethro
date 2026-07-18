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
 * The hedge advisor's target-flat deadband and warm-up handling (ADR-0039), driving the tested
 * {@link io.jethro.trading.riskpnl.HedgeMath} ratio. Same hand-computed covariance as HedgeMathTest
 * (ES/STOCK, ρ=0.9) so the sized numbers are checkable.
 */
class HedgeAdvisorTest {

    private static CovMath.Covariance cov() {
        double[][] sigma = {{1e-4, 1.8e-4}, {1.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 60);
    }

    private static HedgeAdvisor advisor(HedgeAdvisor.Mode mode, String capUsd) {
        return new HedgeAdvisor(mode, new BigDecimal(capUsd), 0.25, "ES", new BigDecimal("50"));
    }

    private static final java.util.function.Predicate<String> IS_EQUITY = "STOCK"::equals;
    private static final java.util.function.Function<String, Optional<BigDecimal>> ES_AT_5600 =
            id -> "ES".equals(id) ? Optional.of(new BigDecimal("5600")) : Optional.empty();

    @Test
    void withinTheDeadbandNoHedgeIsProposed() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "2000000") // cap $2M, exposure $1M
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
        var axis = snap.axes().get(0);
        assertFalse(axis.breached(), "$1M is inside the $2M deadband");
        assertFalse(axis.hedgeRecommended());
        assertNull(axis.hedgeQuantity(), "no hedge sized within the band");
        assertEquals("OK", axis.status());
    }

    @Test
    void breachSizesTheMinimumVarianceHedgeToFlat() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "500000") // cap $500k, exposure $1M → breached
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
        var axis = snap.axes().get(0);
        assertTrue(axis.breached());
        assertTrue(axis.hedgeRecommended(), "ρ²=0.81 clears the floor");
        assertEquals("SELL", axis.hedgeSide(), "hedge a long book by shorting the proxy");
        assertEquals("HEDGE", axis.status());
        assertEquals(0.81, axis.effectiveness(), 1e-9);
        // β=1.8 → $1.8M notional → 1.8M/280k = 6.428571 ES.
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
        assertEquals(0, new BigDecimal("8717.80").compareTo(axis.residualSigmaUsd()));
    }

    @Test
    void breachDuringCovarianceWarmupCannotSizeYet() {
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "500000")
                .evaluate(Optional.empty(), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
        var axis = snap.axes().get(0);
        assertTrue(axis.breached());
        assertFalse(snap.covarianceReady());
        assertNull(axis.hedgeQuantity(), "no covariance → no sized hedge, but the breach is still shown");
        assertTrue(axis.rationale().contains("warming up"), axis.rationale());
    }

    @Test
    void offModeProposesNothing() {
        var snap = advisor(HedgeAdvisor.Mode.OFF, "500000")
                .evaluate(Optional.of(cov()), Map.of("STOCK", new BigDecimal("1000000")), IS_EQUITY, ES_AT_5600);
        assertFalse(snap.axes().get(0).hedgeRecommended());
        assertEquals("OFF", snap.mode());
    }

    @Test
    void onlyEquityNamesEnterTheAxisNotTheProxyItself() {
        // A held ES position must not count as equity exposure to be hedged (it IS the hedge).
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "500000").evaluate(Optional.of(cov()),
                Map.of("STOCK", new BigDecimal("1000000"), "ES", new BigDecimal("9000000")),
                Set.of("STOCK")::contains, ES_AT_5600);
        // net equity = STOCK only ($1M), ES excluded.
        assertEquals(0, new BigDecimal("1000000.00").compareTo(snap.axes().get(0).netExposureUsd()));
    }
}

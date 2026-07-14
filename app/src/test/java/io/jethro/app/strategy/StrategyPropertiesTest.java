package io.jethro.app.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regime-aware sizing scale: reduced in VOLATILE, unscaled otherwise; safe defaults. */
class StrategyPropertiesTest {

    private static StrategyProperties withVolatileScale(BigDecimal scale) {
        return new StrategyProperties(true, 5, 24, 2.5, new BigDecimal("2"), new BigDecimal("25000"),
                "ALPHA", Map.of(), false, 60, null, null, null, Map.of(), false, null, null, true, scale, null, null);
    }

    @Test
    void volatileRegimeScalesDownOthersUnchanged() {
        var p = withVolatileScale(new BigDecimal("0.5"));
        assertEquals(0, new BigDecimal("0.5").compareTo(p.regimeScaleFor("VOLATILE")));
        assertEquals(0, BigDecimal.ONE.compareTo(p.regimeScaleFor("CALM")));
        assertEquals(0, BigDecimal.ONE.compareTo(p.regimeScaleFor("TREND_UP")));
    }

    @Test
    void defaultsToHalfSizeWhenUnset() {
        assertEquals(0, new BigDecimal("0.5").compareTo(withVolatileScale(null).regimeScaleFor("VOLATILE")));
    }

    @Test
    void zeroMeansStandAside() {
        assertEquals(0, BigDecimal.ZERO.compareTo(withVolatileScale(BigDecimal.ZERO).regimeScaleFor("VOLATILE")));
    }
}

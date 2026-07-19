package io.jethro.app.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ELEVATED (risk-off) sizing scale value; the regime itself is sensed from prices (ADR-0051). */
class StrategyPropertiesTest {

    private static StrategyProperties withVolatileScale(BigDecimal scale) {
        return new StrategyProperties(true, 5, 24, 2.5, new BigDecimal("2"), new BigDecimal("25000"),
                "ALPHA", Map.of(), false, 60, null, null, null, Map.of(), false, null, null, true, scale, null, null, null, null);
    }

    @Test
    void elevatedScaleIsTheConfiguredValue() {
        assertEquals(0, new BigDecimal("0.5").compareTo(
                withVolatileScale(new BigDecimal("0.5")).regimeVolatileScaleOrDefault()));
    }

    @Test
    void defaultsToHalfSizeWhenUnset() {
        assertEquals(0, new BigDecimal("0.5").compareTo(withVolatileScale(null).regimeVolatileScaleOrDefault()));
    }

    @Test
    void zeroMeansStandAside() {
        assertEquals(0, BigDecimal.ZERO.compareTo(withVolatileScale(BigDecimal.ZERO).regimeVolatileScaleOrDefault()));
    }
}

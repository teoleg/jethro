package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimTickGeneratorTest {

    @Test
    void sameSeedProducesIdenticalWalk() {
        var g1 = new SimTickGenerator(42L, 2, 100_000_000L);
        var g2 = new SimTickGenerator(42L, 2, 100_000_000L);
        for (int i = 0; i < 1_000; i++) {
            assertEquals(g1.nextPriceScaled(i % 2), g2.nextPriceScaled(i % 2));
            assertEquals(g1.nextQuantityScaled(), g2.nextQuantityScaled());
        }
    }

    @Test
    void differentSeedsDiverge() {
        var g1 = new SimTickGenerator(1L, 1, 100_000_000L);
        var g2 = new SimTickGenerator(2L, 1, 100_000_000L);
        boolean diverged = false;
        for (int i = 0; i < 100 && !diverged; i++) {
            diverged = g1.nextPriceScaled(0) != g2.nextPriceScaled(0);
        }
        assertTrue(diverged, "walks with different seeds should diverge");
    }

    @Test
    void pricesNeverGoBelowMinimum() {
        var g = new SimTickGenerator(7L, 1, 10_000L); // start at the floor
        for (int i = 0; i < 10_000; i++) {
            assertTrue(g.nextPriceScaled(0) >= 10_000L, "price fell below 0.01");
        }
    }
}

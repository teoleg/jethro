package io.jethro.app.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared Finnhub REST budget: grants up to the cap in a rolling minute, then denies. */
class FinnhubRateLimiterTest {

    @Test
    void grantsUpToTheCapThenDenies() {
        var limiter = new FinnhubRateLimiter(3);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "4th call in the window is denied");
        assertEquals(3, limiter.used());
    }

    @Test
    void capIsClampedIntoTheFreeTierBounds() {
        assertTrue(new FinnhubRateLimiter(0).tryAcquire(), "clamped up to at least 1");
        var wide = new FinnhubRateLimiter(1000); // clamped to 60
        for (int i = 0; i < 60; i++) {
            assertTrue(wide.tryAcquire(), "call " + i);
        }
        assertFalse(wide.tryAcquire(), "61st denied — clamped to the 60/min account cap");
    }
}

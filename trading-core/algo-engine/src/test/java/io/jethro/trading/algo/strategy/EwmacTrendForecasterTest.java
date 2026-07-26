package io.jethro.trading.algo.strategy;

import io.jethro.trading.algo.strategy.EwmacTrendForecaster.Params;
import io.jethro.trading.algo.strategy.EwmacTrendForecaster.Reading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0066 trend sensor. Every assertion is about SHAPE and SIGN — the properties the fusion layer
 * relies on — because the sensor's whole design is that its scale is measured from the stream rather
 * than fixed: a clean uptrend is positive, a downtrend negative, chop is small, and the reading is
 * invariant to the price units of the instrument it is measuring.
 */
class EwmacTrendForecasterTest {

    private static final Params FAST = new Params(2, 8, 16); // short spans keep the tests quick

    private static Reading feed(EwmacTrendForecaster f, String id, double... prices) {
        Reading last = Reading.cold(id);
        for (double p : prices) {
            last = f.update(id, BigDecimal.valueOf(p));
        }
        return last;
    }

    /** A steady ramp of `steps` prices from `start`, each step `by`. */
    private static double[] ramp(double start, double by, int steps) {
        double[] out = new double[steps];
        for (int i = 0; i < steps; i++) {
            out[i] = start + i * by;
        }
        return out;
    }

    /** Alternating up/down of the same size — the definition of chop: net move ~0 over many steps. */
    private static double[] chop(double centre, double amplitude, int steps) {
        double[] out = new double[steps];
        for (int i = 0; i < steps; i++) {
            out[i] = centre + (i % 2 == 0 ? amplitude : -amplitude);
        }
        return out;
    }

    @Test
    void coldUntilTheWindowsAreFull() {
        EwmacTrendForecaster f = new EwmacTrendForecaster(FAST);
        Reading r = feed(f, "AAPL", ramp(100, 0.5, 5)); // fewer than slowSpan+1 prices
        assertFalse(r.warm(), "sensor must not speak before its slow window is full");
        assertEquals(0.0, r.score(), "no view while warming up");
    }

    @Test
    void steadyUptrendReadsPositiveAndCleanlyTrending() {
        EwmacTrendForecaster f = new EwmacTrendForecaster(FAST);
        Reading r = feed(f, "AAPL", ramp(100, 0.5, 40));
        assertTrue(r.warm(), "40 prices is well past the 8-step slow window");
        assertTrue(r.score() > 0, "a monotone rise must read long, got " + r.score());
        assertTrue(r.rawTrend() > 0, "fast EWMA must sit above slow in an uptrend");
        assertEquals(1.0, r.efficiencyRatio(), 1e-9, "a monotone ramp is a perfectly efficient trend");
    }

    @Test
    void steadyDowntrendReadsNegative() {
        EwmacTrendForecaster f = new EwmacTrendForecaster(FAST);
        Reading r = feed(f, "AAPL", ramp(100, -0.5, 40));
        assertTrue(r.score() < 0, "a monotone fall must read short, got " + r.score());
    }

    @Test
    void chopReadsNearFlatEvenThoughEveryStepIsLarge() {
        EwmacTrendForecaster f = new EwmacTrendForecaster(FAST);
        Reading r = feed(f, "AAPL", chop(100, 1.0, 40));
        assertTrue(r.efficiencyRatio() < 0.2, "alternating prices are chop, ER=" + r.efficiencyRatio());
        assertTrue(Math.abs(r.score()) < 1.0,
                "a chopping name must not read as a typical (|score|~1) trend, got " + r.score());
    }

    @Test
    void reversalFlipsTheSign() {
        EwmacTrendForecaster f = new EwmacTrendForecaster(FAST);
        assertTrue(feed(f, "AAPL", ramp(100, 0.5, 40)).score() > 0);
        // The trend rolls over and runs the other way: the crossover cuts the position by itself.
        Reading after = feed(f, "AAPL", ramp(120, -0.5, 40));
        assertTrue(after.score() < 0, "the sensor must flip with the trend, got " + after.score());
    }

    @Test
    void readingIsInvariantToThePriceUnitsOfTheInstrument() {
        // The same shape at 100x the price level must produce the same reading — this is what lets one
        // sensor speak for an FX cross, an equity and a futures contract on one scale (invariant 9:
        // nothing here is calibrated to a particular feed's price levels).
        EwmacTrendForecaster cheap = new EwmacTrendForecaster(FAST);
        EwmacTrendForecaster dear = new EwmacTrendForecaster(FAST);
        double[] shape = ramp(100, 0.5, 40);
        double[] scaled = new double[shape.length];
        for (int i = 0; i < shape.length; i++) {
            scaled[i] = shape[i] * 100;
        }
        Reading a = feed(cheap, "X", shape);
        Reading b = feed(dear, "Y", scaled);
        assertEquals(a.score(), b.score(), 1e-9, "score must be scale-free");
        assertEquals(a.rawTrend(), b.rawTrend(), 1e-9, "raw trend is in vol units — also scale-free");
    }

    @Test
    void staleOrJunkPricesAreRefused() {
        EwmacTrendForecaster f = new EwmacTrendForecaster(FAST);
        feed(f, "AAPL", ramp(100, 0.5, 40));
        Reading zero = f.update("AAPL", BigDecimal.ZERO);
        assertEquals(0.0, zero.score(), "a non-positive price is not a mark and must not move the sensor");
        Reading nullId = f.update(null, BigDecimal.TEN);
        assertEquals(0.0, nullId.score());
    }

    @Test
    void eachInstrumentIsMeasuredOnItsOwnBehaviour() {
        EwmacTrendForecaster f = new EwmacTrendForecaster(FAST);
        feed(f, "UP", ramp(100, 0.5, 40));
        feed(f, "DOWN", ramp(100, -0.5, 40));
        assertTrue(f.readingFor("UP").score() > 0);
        assertTrue(f.readingFor("DOWN").score() < 0);
        assertEquals(2, f.readings().size());
        assertFalse(f.readingFor("NEVER-SEEN").warm());
    }
}

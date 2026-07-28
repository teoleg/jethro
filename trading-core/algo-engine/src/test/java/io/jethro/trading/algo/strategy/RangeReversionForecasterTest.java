package io.jethro.trading.algo.strategy;

import io.jethro.trading.algo.strategy.RangeReversionForecaster.Params;
import io.jethro.trading.algo.strategy.RangeReversionForecaster.Reading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0070 mean-reversion sensor. Like its trend sibling, every assertion is about SHAPE and SIGN —
 * the properties the fusion layer relies on — because the sensor's whole design is that its scale is
 * measured from the stream rather than fixed: a stretch to the top of the range is faded (negative), a
 * stretch to the bottom is bought (positive), a clean trend is refused, and the reading is invariant to
 * the price units of the instrument it is measuring.
 */
class RangeReversionForecasterTest {

    private static final Params FAST = new Params(8, 16); // short spans keep the tests quick

    private static Reading feed(RangeReversionForecaster f, String id, double... prices) {
        Reading last = Reading.cold(id);
        for (double p : prices) {
            last = f.update(id, BigDecimal.valueOf(p));
        }
        return last;
    }

    /** Alternating up/down of the same size — the definition of chop: net move ~0 over many steps. */
    private static double[] chop(double centre, double amplitude, int steps) {
        double[] out = new double[steps];
        for (int i = 0; i < steps; i++) {
            out[i] = centre + (i % 2 == 0 ? amplitude : -amplitude);
        }
        return out;
    }

    /** A steady ramp of `steps` prices from `start`, each step `by`. */
    private static double[] ramp(double start, double by, int steps) {
        double[] out = new double[steps];
        for (int i = 0; i < steps; i++) {
            out[i] = start + i * by;
        }
        return out;
    }

    private static double[] concat(double[] a, double... b) {
        double[] out = new double[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Warm the sensor on chop so it has both a range and a calibrated scale, then read one price. */
    private static Reading warmThen(RangeReversionForecaster f, String id, double last) {
        return feed(f, id, concat(chop(100.0, 0.5, 200), last));
    }

    @Test
    void fadesAStretchToTheTopOfItsOwnRange() {
        var f = new RangeReversionForecaster(FAST);
        Reading r = warmThen(f, "AAPL", 103.0); // well above everything the window has traded
        assertTrue(r.warm(), "sensor should be speaking after its warm-up");
        assertEquals(1.0, r.rangePosition(), 1e-9, "a new high sits at the top of its own range");
        assertTrue(r.score() < 0, "a stretch to the top of the range is SOLD, not followed");
    }

    @Test
    void buysAStretchToTheBottomOfItsOwnRange() {
        var f = new RangeReversionForecaster(FAST);
        Reading r = warmThen(f, "AAPL", 97.0);
        assertTrue(r.warm());
        assertEquals(-1.0, r.rangePosition(), 1e-9, "a new low sits at the bottom of its own range");
        assertTrue(r.score() > 0, "a stretch to the bottom of the range is BOUGHT");
    }

    @Test
    void hasNoViewInTheMiddleOfTheRange() {
        var f = new RangeReversionForecaster(FAST);
        // Warm on a symmetric range, then land exactly on its midpoint.
        Reading r = feed(f, "AAPL", concat(chop(100.0, 1.0, 200), 100.0));
        assertTrue(r.warm());
        assertEquals(0.0, r.rangePosition(), 1e-9);
        assertEquals(0.0, r.score(), 1e-9, "mid-range is no view — there is nothing stretched to fade");
    }

    /**
     * The design property that keeps this from being "the trend signal negated": the efficiency-ratio
     * weight collapses the fade to nothing on a clean directional move. A new high made by a straight
     * ramp is the same range position as a new high made by chop, and must NOT be sold the same way.
     */
    @Test
    void refusesToFadeACleanTrend() {
        var chopped = new RangeReversionForecaster(FAST);
        Reading choppyHigh = warmThen(chopped, "AAPL", 103.0);

        var trending = new RangeReversionForecaster(FAST);
        // Same warm-up so both have a comparable scale, then a clean one-way ramp to a new high.
        Reading trendHigh = feed(trending, "AAPL", concat(chop(100.0, 0.5, 200), ramp(101.0, 1.0, 12)));

        assertEquals(1.0, choppyHigh.rangePosition(), 1e-9);
        assertEquals(1.0, trendHigh.rangePosition(), 1e-9);
        assertTrue(trendHigh.efficiencyRatio() > choppyHigh.efficiencyRatio(),
                "the ramp must read as more efficient than the chop");
        assertTrue(Math.abs(trendHigh.score()) < Math.abs(choppyHigh.score()),
                "the same range position must be faded LESS when the move is a real trend");
    }

    /** The ADR-0066 lesson, inherited: no view until the scale estimator has been warmed. */
    @Test
    void publishesNoViewUntilTheScaleEstimatorIsWarm() {
        var f = new RangeReversionForecaster(new Params(4, 40)); // warm-up = 20 readings
        double[] prices = concat(chop(100.0, 1.0, 12), 105.0);   // a big stretch, but far too early
        Reading r = feed(f, "AAPL", prices);
        assertFalse(r.warm(), "the scale estimator has not absorbed its warm-up readings yet");
        assertEquals(0.0, r.score(), 1e-12, "an un-warmed sensor must not pin at the forecast cap");
    }

    /** Feed-agnostic (invariant 9): the same shape at a different price scale reads the same. */
    @Test
    void readingIsInvariantToPriceUnits() {
        var cheap = new RangeReversionForecaster(FAST);
        var rich = new RangeReversionForecaster(FAST);
        double[] base = concat(chop(100.0, 0.5, 200), 103.0);
        double[] scaled = new double[base.length];
        for (int i = 0; i < base.length; i++) {
            scaled[i] = base[i] * 50.0; // same relative shape, a very different quoted price
        }
        Reading a = feed(cheap, "AAPL", base);
        Reading b = feed(rich, "ES", scaled);
        assertEquals(a.score(), b.score(), 1e-9);
        assertEquals(a.rangePosition(), b.rangePosition(), 1e-9);
    }

    /** A dead-flat window has no range to be stretched against, and must not divide by zero. */
    @Test
    void deadFlatWindowHasNoView() {
        var f = new RangeReversionForecaster(FAST);
        double[] flat = new double[60];
        java.util.Arrays.fill(flat, 100.0);
        Reading r = feed(f, "AAPL", flat);
        assertEquals(0.0, r.score(), 1e-12);
        assertFalse(r.warm());
    }

    /** Typical readings sit near 1 in absolute value — the house convention the mappers rely on. */
    @Test
    void normalisesToATypicalAbsoluteReadingOfAboutOne() {
        var f = new RangeReversionForecaster(new Params(16, 64));
        // An irregular, incommensurate oscillation: chop with structure, not a clean square wave.
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 2_000; i++) {
            double p = 100.0 + Math.sin(i / 7.0) + 0.6 * Math.sin(i / 3.1) + 0.3 * Math.sin(i / 11.7);
            Reading r = f.update("AAPL", BigDecimal.valueOf(p));
            if (r.warm() && i > 500) {
                sum += Math.abs(r.score());
                n++;
            }
        }
        assertTrue(n > 500, "expected a long warm sample, got " + n);
        double meanAbs = sum / n;
        assertTrue(meanAbs > 0.5 && meanAbs < 2.0,
                "expected E|score| near 1 by construction, got " + meanAbs);
    }
}

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

    /**
     * A stationary stream with trends and reversals at several timescales, built from incommensurate
     * sinusoids so it is statistically homogeneous AND fully deterministic — no RNG, so the assertions
     * below are exact facts about the sensor rather than a flaky sample.
     */
    private static double[] mixedRegimes(int steps) {
        double[] out = new double[steps];
        for (int t = 0; t < steps; t++) {
            out[t] = 100.0 * (1 + 0.020 * Math.sin(t / 7.3)
                                + 0.013 * Math.sin(t / 17.1)
                                + 0.008 * Math.sin(t / 3.7));
        }
        return out;
    }

    /** Production-shaped spans (slow = 4×fast, normalisation = 4×slow), scaled down to keep tests quick. */
    private static final Params SHAPED = new Params(4, 16, 64);

    /** Every reading the sensor actually publishes — i.e. those it reports itself warm for. */
    private static java.util.List<Double> published(Params params, double[] prices) {
        EwmacTrendForecaster f = new EwmacTrendForecaster(params);
        java.util.List<Double> out = new java.util.ArrayList<>();
        for (double p : prices) {
            Reading r = f.update("X", BigDecimal.valueOf(p));
            if (r.warm()) {
                out.add(r.score());
            }
        }
        return out;
    }

    @Test
    void saysNothingUntilItHasMeasuredWhatTypicalMeansOnThisStream() {
        // The scale estimator needs normalisationSpan/2 readings; until then the sensor has a price
        // window but no calibration, and a reading divided by an uncalibrated scale is not a reading.
        EwmacTrendForecaster f = new EwmacTrendForecaster(SHAPED);
        double[] prices = ramp(100, 0.5, 16 + 64 / 2); // past the slow window, still inside scale warm-up
        Reading r = feed(f, "AAPL", prices);
        assertFalse(r.warm(), "the sensor must not speak while its scale is still being measured");
        assertEquals(0.0, r.score(), "no view means no view — never a placeholder conviction");

        Reading later = feed(f, "AAPL", ramp(100 + 0.5 * prices.length, 0.5, 40));
        assertTrue(later.warm(), "once the scale estimator is warm the sensor speaks");
    }

    @Test
    void aFreshlyWarmedNameDoesNotOpenAtMaximumConviction() {
        // Regression (ADR-0066): the scale EWMA used to be seeded from ONE observation, so the first
        // readings were divided by a single noisy draw rather than an estimate of typical size. On this
        // stream that pinned 5 of the first 10 readings at |score| >= 2 — the point at which the desk's
        // forecast clips to its ±20 cap, i.e. maximum position size on every such name, decided at the
        // moment the sensor knew least. Saturation also destroys the sensor's whole purpose: a clipped
        // forecast cannot rank a clean trend above a noisy one.
        java.util.List<Double> pub = published(SHAPED, mixedRegimes(600));
        assertTrue(pub.size() >= 10, "expected the sensor to publish readings, got " + pub.size());

        for (int i = 0; i < 10; i++) {
            assertTrue(Math.abs(pub.get(i)) < 2.0,
                    "reading " + i + " opened at the forecast cap (|score|=" + Math.abs(pub.get(i))
                            + "): the scale estimator is not warm enough to divide by");
        }
    }

    @Test
    void aTypicalReadingStaysTheUnitOfConvictionOverTheLongRun() {
        // The warm-up must not disturb what the score MEANS: E|score| ~ 1 = "one typical trend", which
        // is what lets TARGET_ABS map a typical trend onto a typical conviction for every source.
        java.util.List<Double> pub = published(SHAPED, mixedRegimes(600));
        double mean = pub.stream().mapToDouble(Math::abs).average().orElseThrow();
        assertTrue(mean > 0.6 && mean < 1.6,
                "a typical reading must still be ~1 by construction, got E|score|=" + mean);
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

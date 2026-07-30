package io.jethro.app.fusion;

import io.jethro.trading.algo.strategy.EwmacTrendForecaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0131 — a cold sensor re-seeds; it is not abandoned after one attempt.
 *
 * <p>The live failure these tests pin down: the boot-time seed read a store that ended in the gap which
 * made the seed necessary, returned a couple of prices against a warm-up of hundreds, and the sensor was
 * never seeded again for the life of the process.
 */
class SensorReseedTest {

    @Test
    @DisplayName("first sight always seeds, exactly as the once-only policy did")
    void firstSightSeeds() {
        SensorReseed reseed = new SensorReseed(5);
        assertThat(reseed.due("AAPL")).isTrue();
    }

    @Test
    @DisplayName("a still-cold name is re-seeded once per cadence, not on every sighting")
    void coldNameRetriesOnCadence() {
        SensorReseed reseed = new SensorReseed(3);
        assertThat(reseed.due("AAPL")).isTrue();   // first sight
        reseed.record("AAPL", false);
        assertThat(reseed.due("AAPL")).isFalse();  // 1 sighting since the attempt
        assertThat(reseed.due("AAPL")).isFalse();  // 2
        assertThat(reseed.due("AAPL")).isTrue();   // 3 — one cadence after the first attempt
        reseed.record("AAPL", false);
        // and the counter restarts cleanly: the reset must not read as a fresh first sight
        assertThat(reseed.due("AAPL")).isFalse();  // 1
        assertThat(reseed.due("AAPL")).isFalse();  // 2
        assertThat(reseed.due("AAPL")).isTrue();   // 3 — and again
    }

    @Test
    @DisplayName("a warm name is never seeded again, however long the process runs")
    void warmNameIsRetiredForGood() {
        SensorReseed reseed = new SensorReseed(2);
        assertThat(reseed.due("AAPL")).isTrue();
        reseed.record("AAPL", true);
        for (int i = 0; i < 100; i++) {
            assertThat(reseed.due("AAPL")).as("sighting %d after warming", i).isFalse();
        }
        assertThat(reseed.warmedNames()).isEqualTo(1);
    }

    @Test
    @DisplayName("names are tracked independently — one warming does not retire another")
    void namesAreIndependent() {
        SensorReseed reseed = new SensorReseed(3);
        assertThat(reseed.due("AAPL")).isTrue();
        assertThat(reseed.due("MSFT")).isTrue();
        reseed.record("AAPL", true);
        reseed.record("MSFT", false);
        assertThat(reseed.due("AAPL")).isFalse();
        assertThat(reseed.due("MSFT")).isFalse(); // 1 of 3
        assertThat(reseed.due("MSFT")).isFalse(); // 2 of 3
        assertThat(reseed.due("MSFT")).isTrue();  // 3 of 3 — retried
    }

    @Test
    @DisplayName("null and unknown names are inert")
    void nullSafe() {
        SensorReseed reseed = new SensorReseed(3);
        assertThat(reseed.due(null)).isFalse();
        reseed.record(null, true);
        assertThat(reseed.warmedNames()).isZero();
    }

    @Test
    @DisplayName("the live failure, end to end: a truncated boot seed leaves the sensor cold, and the "
            + "SAME sensor warms on the retry once the store has accumulated — with no double-counting")
    void reseedWarmsASensorTheBootSeedCouldNot() {
        EwmacTrendForecaster forecaster = new EwmacTrendForecaster(
                new EwmacTrendForecaster.Params(4, 8, 8));
        int needed = forecaster.warmupSamples();

        // The boot read: the store ends in the outage, so the seed hands over two prices of `needed`.
        List<BigDecimal> bootSeed = trendingPrices(2);
        bootSeed.forEach(p -> forecaster.update("AAPL", p));
        assertThat(forecaster.readingFor("AAPL").warm())
                .as("a two-price seed cannot warm a sensor that needs %d", needed).isFalse();

        // The store has since accumulated a full series. Under the old once-only policy this read never
        // happened. Replay it into a state we drop first, so the two prices above are not counted twice.
        List<BigDecimal> laterSeed = trendingPrices(needed + 20);
        forecaster.forget("AAPL");
        laterSeed.forEach(p -> forecaster.update("AAPL", p));

        assertThat(forecaster.readingFor("AAPL").warm())
                .as("the retry warms the sensor the boot seed could not").isTrue();
        assertThat(forecaster.readingFor("AAPL").score())
                .as("and it publishes a real view, not the no-view sentinel").isNotEqualTo(0.0);
    }

    @Test
    @DisplayName("forget() drops the name whole — a re-seeded sensor is identical to a never-seen one")
    void forgetLeavesNoResidue() {
        var params = new EwmacTrendForecaster.Params(4, 8, 8);
        EwmacTrendForecaster polluted = new EwmacTrendForecaster(params);
        EwmacTrendForecaster fresh = new EwmacTrendForecaster(params);
        List<BigDecimal> series = trendingPrices(polluted.warmupSamples() + 20);

        // A sensor that consumed a different, wrong series first, then forgot it and replayed the seed…
        trendingPrices(37).forEach(p -> polluted.update("AAPL", p));
        polluted.forget("AAPL");
        series.forEach(p -> polluted.update("AAPL", p));
        // …must land exactly where one that only ever saw the seed does.
        series.forEach(p -> fresh.update("AAPL", p));

        assertThat(polluted.readingFor("AAPL").warm()).isEqualTo(fresh.readingFor("AAPL").warm());
        assertThat(polluted.readingFor("AAPL").score()).isEqualTo(fresh.readingFor("AAPL").score());
        assertThat(polluted.readingFor("AAPL").rawTrend()).isEqualTo(fresh.readingFor("AAPL").rawTrend());
    }

    @Test
    @DisplayName("the σ sensor forgets whole too — no return is manufactured across the reset")
    void volatilityForgetLeavesNoResidue() {
        StreamVolatility polluted = new StreamVolatility(new StreamVolatility.Params(8));
        StreamVolatility fresh = new StreamVolatility(new StreamVolatility.Params(8));
        List<BigDecimal> series = trendingPrices(40);

        trendingPrices(11).forEach(p -> polluted.update("AAPL", p));
        polluted.forget("AAPL");
        assertThat(polluted.seen("AAPL")).as("the state is gone, not merely zeroed").isFalse();
        series.forEach(p -> polluted.update("AAPL", p));
        series.forEach(p -> fresh.update("AAPL", p));

        assertThat(polluted.sigmaPerSample("AAPL")).isEqualTo(fresh.sigmaPerSample("AAPL"));
    }

    /** A deterministic non-degenerate price series: a drift with an alternating wobble so the step vol,
     *  the efficiency ratio and the scale estimator all have something to measure. Exact decimal. */
    private static List<BigDecimal> trendingPrices(int n) {
        List<BigDecimal> out = new ArrayList<>(n);
        BigDecimal price = new BigDecimal("100.00");
        for (int i = 0; i < n; i++) {
            price = price.add(new BigDecimal(i % 3 == 0 ? "0.35" : "0.10"));
            out.add(price);
        }
        return out;
    }
}

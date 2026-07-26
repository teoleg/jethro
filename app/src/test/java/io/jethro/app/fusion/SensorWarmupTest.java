package io.jethro.app.fusion;

import io.jethro.trading.algo.strategy.RangeReversionForecaster;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0071: a sensor must boot from stored history already calibrated, and the seed must be the stream
 * the sensor would have seen — sampled at its own cadence, ending at the present, never bridging a hole.
 */
class SensorWarmupTest {

    private static final long NOW = 1_700_000_000_000L;

    /** A 1 Hz series of {@code count} points ending at {@code NOW}, price rising by 1 each step. */
    private static SensorWarmup.History oneHertz(int count) {
        List<SensorWarmup.Point> points = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            points.add(new SensorWarmup.Point(NOW - i * 1_000L, BigDecimal.valueOf(100 + (count - 1 - i))));
        }
        return (instrumentId, since) -> points.stream().filter(p -> p.timestampMillis() >= since).toList();
    }

    @Test
    void thinsToTheSensorCadenceRatherThanTheTapeRate() {
        // 1 Hz marks, a 10 s sensor: consecutive seed prices must be 10 apart, not 1.
        List<BigDecimal> seed = SensorWarmup.seedPrices(oneHertz(600), "AAPL", NOW, 10_000L, 5);

        assertThat(seed).containsExactly(
                new BigDecimal("659"), new BigDecimal("669"), new BigDecimal("679"),
                new BigDecimal("689"), new BigDecimal("699"));
    }

    @Test
    void endsAtThePresentAndIsCappedAtTheSampleCount() {
        List<BigDecimal> seed = SensorWarmup.seedPrices(oneHertz(600), "AAPL", NOW, 1_000L, 4);

        assertThat(seed).hasSize(4);
        assertThat(seed.get(3)).isEqualByComparingTo("699"); // the newest stored price
    }

    @Test
    void stopsAtAHoleInsteadOfFabricatingAJumpAcrossIt() {
        // Two 1 Hz blocks either side of an hour-long hole; a 1 s sensor tolerates 30 missed samples.
        List<SensorWarmup.Point> points = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            points.add(new SensorWarmup.Point(NOW - 3_600_000L - (49 - i) * 1_000L, BigDecimal.valueOf(50 + i)));
        }
        for (int i = 0; i < 5; i++) {
            points.add(new SensorWarmup.Point(NOW - (4 - i) * 1_000L, BigDecimal.valueOf(100 + i)));
        }
        SensorWarmup.History history = (instrumentId, since) ->
                points.stream().filter(p -> p.timestampMillis() >= since).toList();

        List<BigDecimal> seed = SensorWarmup.seedPrices(history, "AAPL", NOW, 1_000L, 40);

        assertThat(seed).containsExactly(new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("102"), new BigDecimal("103"), new BigDecimal("104"));
    }

    @Test
    void noHistoryIsAPlainColdStartNotAFailure() {
        assertThat(SensorWarmup.seedPrices(null, "AAPL", NOW, 1_000L, 10)).isEmpty();
        assertThat(SensorWarmup.seedPrices((id, since) -> List.of(), "AAPL", NOW, 1_000L, 10)).isEmpty();
        assertThat(SensorWarmup.seedPrices((id, since) -> {
            throw new IllegalStateException("store unavailable");
        }, "AAPL", NOW, 1_000L, 10)).isEmpty();
    }

    @Test
    void nonPositivePricesAreSkipped() {
        List<SensorWarmup.Point> points = List.of(
                new SensorWarmup.Point(NOW - 3_000L, new BigDecimal("10")),
                new SensorWarmup.Point(NOW - 2_000L, BigDecimal.ZERO),
                new SensorWarmup.Point(NOW - 1_000L, new BigDecimal("12")));

        List<BigDecimal> seed = SensorWarmup.seedPrices((id, since) -> points, "AAPL", NOW, 1_000L, 10);

        assertThat(seed).containsExactly(new BigDecimal("10"), new BigDecimal("12"));
    }

    /**
     * The point of the whole exercise: a sensor seeded with its own warm-up requirement speaks on its
     * very first live mark, where the same sensor cold-started stays silent. That difference is what a
     * redeploy costs today, and what stored history buys back.
     */
    @Test
    void aSeededSensorSpeaksOnItsFirstLiveMarkWhereAColdOneStaysSilent() {
        var params = new RangeReversionForecaster.Params(4, 6);
        int needed = new RangeReversionForecaster(params).warmupSamples();

        // An oscillating series, so the range is non-degenerate and the scale estimator advances.
        List<SensorWarmup.Point> points = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            long t = NOW - (399 - i) * 1_000L;
            points.add(new SensorWarmup.Point(t, BigDecimal.valueOf(100 + (i % 7))));
        }
        SensorWarmup.History history = (instrumentId, since) ->
                points.stream().filter(p -> p.timestampMillis() >= since).toList();

        var warmed = new RangeReversionForecaster(params);
        int replayed = SensorWarmup.warm(history, "AAPL", NOW, 2_000L, needed,
                price -> warmed.update("AAPL", price));
        assertThat(replayed).isEqualTo(needed);

        var cold = new RangeReversionForecaster(params);

        BigDecimal live = new BigDecimal("106");
        assertThat(warmed.update("AAPL", live).warm()).isTrue();
        assertThat(cold.update("AAPL", live).warm()).isFalse();
    }
}

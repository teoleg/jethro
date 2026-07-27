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

    /**
     * ADR-0071 correction — <b>one clock only</b>. The store is keyed by provider time; a delayed,
     * replayed or simulated feed puts provider time behind the wall clock. Anchoring the seed window on
     * wall-clock now then admits only the sliver of the series newer than {@code wallNow - lookback},
     * and once the lag exceeds the lookback it admits nothing at all — a permanently cold sensor that
     * still reports a successful warm. Anchoring on the mark's own provider timestamp is immune to the
     * lag, whatever it is.
     */
    @Test
    void anchorsOnTheFeedClockSoAFeedRunningBehindWallTimeStillSeeds() {
        int samples = 20;
        long interval = 5_000L;
        // The series ends half an hour behind the wall clock — the lag of a delayed or simulated feed.
        long feedLag = 1_800_000L;
        long newestProviderMillis = NOW - feedLag;
        List<SensorWarmup.Point> points = new ArrayList<>();
        for (int i = 199; i >= 0; i--) {
            points.add(new SensorWarmup.Point(newestProviderMillis - i * 1_000L, BigDecimal.valueOf(300 - i)));
        }
        SensorWarmup.History history = (instrumentId, since) ->
                points.stream().filter(p -> p.timestampMillis() >= since).toList();

        // Anchored on the feed's own clock: a full seed, ending on the newest stored price.
        List<BigDecimal> onFeedClock =
                SensorWarmup.seedPrices(history, "AAPL", newestProviderMillis, interval, samples);
        assertThat(onFeedClock).hasSize(samples);
        assertThat(onFeedClock.get(samples - 1)).isEqualByComparingTo("300");

        // The defect being fixed: a wall-clock anchor, with a lag far wider than the lookback window,
        // reads a window entirely in the feed's future and seeds nothing.
        assertThat(SensorWarmup.seedPrices(history, "AAPL", NOW, interval, samples)).isEmpty();
    }

    /**
     * ADR-0089: a covariance seed must be SYNCHRONISED. Two names whose stored series print on
     * different sub-second offsets must land in the same bucket of the store's own clock, so the
     * returns replayed into the estimator are contemporaneous rather than merely adjacent.
     */
    @Test
    void jointSeedAlignsNamesOnOneBucketGrid() {
        List<SensorWarmup.Point> a = new ArrayList<>();
        List<SensorWarmup.Point> b = new ArrayList<>();
        for (int i = 9; i >= 0; i--) {
            a.add(new SensorWarmup.Point(NOW - i * 10_000L, BigDecimal.valueOf(100 + (9 - i))));
            // B prints 3 s later inside the same 10 s bucket, and twice in it.
            b.add(new SensorWarmup.Point(NOW - i * 10_000L - 7_000L, BigDecimal.valueOf(50)));
            b.add(new SensorWarmup.Point(NOW - i * 10_000L - 3_000L, BigDecimal.valueOf(200 + (9 - i))));
        }
        SensorWarmup.History history = (instrumentId, since) ->
                ("A".equals(instrumentId) ? a : b).stream().filter(p -> p.timestampMillis() >= since).toList();

        var samples = SensorWarmup.jointSeedSamples(history, List.of("A", "B"), NOW, 10_000L, 4);

        assertThat(samples).hasSize(4);
        for (var s : samples) {
            assertThat(s).containsOnlyKeys("A", "B"); // every snapshot carries both names
        }
        // Newest bucket last, and the LAST print inside a bucket is the one taken.
        assertThat(samples.get(3).get("A")).isEqualByComparingTo("109");
        assertThat(samples.get(3).get("B")).isEqualByComparingTo("209");
        assertThat(samples.get(0).get("A")).isEqualByComparingTo("106");
    }

    /** The joint seed truncates at a hole on the same tolerance the per-name seed uses. */
    @Test
    void jointSeedStopsAtAHoleRatherThanBridgingIt() {
        List<SensorWarmup.Point> points = new ArrayList<>();
        for (int i = 0; i < 20; i++) { // an old block, an hour before the recent one
            points.add(new SensorWarmup.Point(NOW - 3_600_000L - (19 - i) * 1_000L, BigDecimal.valueOf(50 + i)));
        }
        for (int i = 0; i < 5; i++) {
            points.add(new SensorWarmup.Point(NOW - (4 - i) * 1_000L, BigDecimal.valueOf(100 + i)));
        }
        SensorWarmup.History history = (instrumentId, since) ->
                points.stream().filter(p -> p.timestampMillis() >= since).toList();

        var samples = SensorWarmup.jointSeedSamples(history, List.of("A"), NOW, 1_000L, 40);

        assertThat(samples).hasSize(5); // the contiguous tail only — never across the hole
        assertThat(samples.get(4).get("A")).isEqualByComparingTo("104");
    }
}

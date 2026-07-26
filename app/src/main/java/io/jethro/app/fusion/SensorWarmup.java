package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Boots a continuous forecast sensor <b>already calibrated</b>, by replaying the durable recent price
 * history into it instead of making it re-learn the stream from scratch after every process restart
 * (ADR-0071).
 *
 * <p><b>The problem this solves.</b> Both continuous sensors — the ADR-0066 EWMAC trend sensor and the
 * ADR-0070 range-reversion sensor — are deliberately silent until their windows are full <em>and</em>
 * their scale estimator has absorbed a warm-up sample (ADR-0066's lesson: dividing by a one-observation
 * anchor pins a fresh name at the forecast cap). That warm-up is counted in evaluation cycles, so in
 * wall-clock it is tens of minutes. All of that state lives in heap and dies with the JVM. On a desk
 * that is redeployed on a short cadence, the arithmetic is brutal and entirely mechanical: a sensor
 * whose warm-up exceeds the process lifetime <b>never speaks at all</b>, and one whose warm-up is merely
 * comparable speaks only in the last few minutes of each process life — always from a scale estimate
 * built on the minimum possible sample. Either way the edge gate (ADR-0064), which decides whether the
 * desk may open a position at all, is measuring sensors that are permanently cold-starting. That is a
 * property of the deployment cadence, not of the signal, and it silently starves every downstream
 * measurement.
 *
 * <p><b>The fix.</b> The recent mark series is already durable and already survives a restart — it is
 * the LMDB store behind the interactive chart (ADR-0014 derived data, ADR-0017). So on first sight of
 * an instrument a sensor replays that instrument's own recent prices through its ordinary
 * {@code update} path, at its own evaluation cadence, and only then starts consuming live marks. The
 * sensor's arithmetic is untouched: it sees exactly the sequence it would have seen had the process
 * been running, so a warm sensor is warm for the same reason it always was.
 *
 * <p><b>Sampled at the sensor's own cadence, not the tape's.</b> Marks arrive at roughly 1 Hz; a sensor
 * evaluates every {@code intervalSeconds}. Replaying every mark would define its windows over a
 * different horizon than live operation does — the same span count over a much shorter period — so the
 * seed is thinned to one price per evaluation interval. The walk runs newest-first so the seed always
 * ends at the present, and it stops at a gap wider than {@link #GAP_TOLERANCE_SAMPLES} intervals: a
 * redeploy blip is bridged, a genuine outage (or a feed-mode change, which necessarily involves one)
 * truncates the seed rather than fabricating a jump across it.
 *
 * <p><b>One clock only — the feed's.</b> The seed window is expressed in the <em>same</em> clock the
 * store is keyed by: provider time. Callers pass the anchor from the mark they are processing, never
 * from {@code System.currentTimeMillis()}. Mixing the two is silently fatal, because provider time
 * trails wall clock by the feed's delay — invariant 5 is why both stamps exist at all, and the feeds
 * endpoint reports {@code delayed}/{@code delaySeconds} precisely because a lagging feed is normal
 * (15-minute-delayed equity data is an industry standard; a replay or a slow simulated clock lags
 * arbitrarily far). A wall-clock lower bound on a provider-keyed store admits only the sliver of the
 * series newer than {@code wallNow - lookback}, and once the feed's lag exceeds the lookback it admits
 * <b>nothing at all</b> — the sensor then cold-starts forever while the seed reports success. That is
 * exactly how the first cut of ADR-0071 failed in practice: seeds of 4 points against warm-ups of 193
 * and 241, on a feed running half an hour behind the wall clock.
 *
 * <p>Everything here is a price series and a count of samples. There is no money, risk or exposure
 * number in this class, and nothing it produces is a size — the sensors it warms publish a conviction,
 * which the deterministic fusion layer, the edge gate, the conviction floor and the pre-trade floor all
 * still stand between and a fill. Prices stay exact decimal end to end (invariant 1).
 */
public final class SensorWarmup {

    /**
     * How many missed evaluation intervals still count as "the same stream". Past this the walk stops
     * and the sensor warms from the contiguous tail only. This is mine and arbitrary — a data-hygiene
     * threshold, not a money, risk or exposure dial: it is expressed in the sensor's own cadence so it
     * scales with whatever interval is configured, and it is set well above a redeploy gap (seconds)
     * and well below an outage (many minutes).
     */
    static final int GAP_TOLERANCE_SAMPLES = 30;

    /**
     * How much wider than the needed span to read history, so that thinning to the evaluation cadence
     * still finds enough samples when the stored series is sparse or has been stride-downsampled by the
     * store's read cap. A shape dial: reading more can only improve the seed, never size anything.
     */
    private static final int LOOKBACK_MULTIPLE = 2;

    /** One stored price point: provider timestamp and the exact-decimal price. */
    public record Point(long timestampMillis, BigDecimal price) {
    }

    /** The durable recent-price series, adapted from whatever store holds it. */
    @FunctionalInterface
    public interface History {
        /** Points at or after {@code sinceMillis} for one instrument, oldest first; never null. */
        List<Point> since(String instrumentId, long sinceMillis);
    }

    private SensorWarmup() {
    }

    /**
     * The prices to replay into a sensor, oldest first: at most {@code samples} points, spaced at least
     * {@code intervalMillis} apart, ending as close to {@code anchorMillis} as the history allows.
     *
     * @param anchorMillis the newest point of interest, <b>in the store's own clock</b> — i.e. the
     *                     provider timestamp of the mark being processed, never wall-clock now. See the
     *                     class note: a wall-clock anchor on a provider-keyed store silently empties the
     *                     seed on any delayed, replayed or simulated feed.
     * @return an empty list when there is no usable history — the caller then cold-starts exactly as before
     */
    public static List<BigDecimal> seedPrices(History history, String instrumentId, long anchorMillis,
                                              long intervalMillis, int samples) {
        if (history == null || instrumentId == null || samples <= 0 || intervalMillis <= 0) {
            return List.of();
        }
        long lookback = intervalMillis * (long) samples * LOOKBACK_MULTIPLE;
        List<Point> points;
        try {
            points = history.since(instrumentId, anchorMillis - lookback);
        } catch (RuntimeException e) {
            return List.of(); // a history read must never stop a sensor from starting
        }
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        long gapTolerance = intervalMillis * GAP_TOLERANCE_SAMPLES;
        Deque<BigDecimal> out = new ArrayDeque<>();
        long previousAccepted = Long.MIN_VALUE; // timestamp of the last point taken (walking backwards)
        for (int i = points.size() - 1; i >= 0 && out.size() < samples; i--) {
            Point p = points.get(i);
            if (p == null || p.price() == null || p.price().signum() <= 0) {
                continue;
            }
            if (previousAccepted != Long.MIN_VALUE) {
                long age = previousAccepted - p.timestampMillis();
                if (age < intervalMillis) {
                    continue; // too close to the point we already took — thin to the sensor's cadence
                }
                if (age > gapTolerance) {
                    break; // a hole in the series: warm from the contiguous tail, never across it
                }
            }
            out.addFirst(p.price());
            previousAccepted = p.timestampMillis();
        }
        return new ArrayList<>(out);
    }

    /**
     * Replays one instrument's seed prices, oldest first, into a sensor's ordinary update path.
     *
     * @param anchorMillis the store's own clock, as in {@link #seedPrices} — a provider timestamp
     * @return how many prices were replayed — 0 when there is no usable history (a plain cold start)
     */
    public static int warm(History history, String instrumentId, long anchorMillis, long intervalMillis,
                           int samples, java.util.function.Consumer<BigDecimal> sensor) {
        List<BigDecimal> prices = seedPrices(history, instrumentId, anchorMillis, intervalMillis, samples);
        for (BigDecimal price : prices) {
            sensor.accept(price);
        }
        return prices.size();
    }
}

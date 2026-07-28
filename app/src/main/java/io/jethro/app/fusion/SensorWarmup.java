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
 * <p><b>Sampled at the cadence the sensor consumes, which is the slower of our clock and the tape's.</b>
 * On a fast tape marks arrive far quicker than the sensor evaluates, so replaying every mark would define
 * its windows over a different horizon than live operation does — the same span count over a much shorter
 * period — and the seed is thinned to one price per evaluation interval. On a slow tape the binding
 * constraint is the other one: since ADR-0113 the live sensor advances only when the tape prints, so it
 * consumes a name at {@code max(poll interval, print interval)}. That step, measured from the name's own
 * stored series ({@link #consumptionStepMillis}), is the unit BOTH derived quantities are counted in
 * (ADR-0114) — how far back to read, and how large a break stops the walk. The walk runs newest-first so
 * the seed always ends at the present, and it stops at a gap wider than {@link #GAP_TOLERANCE_SAMPLES}
 * of those steps: a redeploy blip is bridged, a genuine outage (or a feed-mode change, which necessarily
 * involves one) truncates the seed rather than fabricating a jump across it.
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
     * How many missed <em>consumption steps</em> still count as "the same stream". Past this the walk
     * stops and the sensor warms from the contiguous tail only. This is mine and arbitrary — a
     * data-hygiene threshold, not a money, risk or exposure dial: it is expressed in the step the sensor
     * actually consumes this name at (see {@link #consumptionStepMillis}) so it scales with both the
     * configured cadence and the name's own tape, and it is set well above a redeploy gap and well below
     * an outage.
     */
    static final int GAP_TOLERANCE_SAMPLES = 30;

    /**
     * How much wider than the needed span to read history, so that thinning to the consumption step
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
        List<Point> points = read(history, instrumentId, anchorMillis, intervalMillis, samples);
        if (points.isEmpty()) {
            return List.of();
        }
        // ADR-0114: the span the seed covers and what counts as a hole in it are properties of the
        // series being walked, so both are measured in the step the sensor actually consumes this name
        // at — not in our poll cadence, which says nothing about how often this tape prints.
        long step = consumptionStepMillis(intervalMillis, points);
        if (step > intervalMillis) {
            List<Point> wider = read(history, instrumentId, anchorMillis, step, samples);
            if (wider.size() > points.size()) {
                points = wider;
                step = consumptionStepMillis(intervalMillis, points);
            }
        }
        long gapTolerance = step * GAP_TOLERANCE_SAMPLES;
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
     * The interval at which the live sensor actually consumes this name: it takes at most one mark per
     * evaluation cycle, and — since ADR-0113 — at most one per print, so the cadence it advances at is
     * the SLOWER of the two. Hence {@code max(pollInterval, typical print interval)}.
     *
     * <p><b>Why this is the right unit</b> (ADR-0114). The seed's lookback and its hole test are both
     * counts of consumption steps: "read twice the span I need" and "a break of thirty steps is not the
     * same stream". Denominating them in the poll cadence alone assumes the tape prints at least that
     * often, which is exactly the assumption ADR-0113 removed from the live path — and on this desk it is
     * false for most of the day: measured live at 23:00Z the median inter-print gap is 20 s on the
     * Treasury curve, 90 s on NQ, 13 min on GBPUSD and 20 min on ES, against a 10 s reversion cadence.
     * A name printing slower than we poll then has every one of its normal print intervals read as an
     * outage, the walk breaks at the first of them, and the seed is one sample against a warm-up of
     * hundreds — permanently, because after ADR-0113 the live path accumulates at that same print rate.
     *
     * <p>This does NOT bridge a halt, which is what ADR-0113 considered and rejected: the tolerance is a
     * multiple of the name's OWN typical interval, so a genuine cessation of printing — the equity cash
     * close, hours against a 12 s tape — still reads as a hole and still truncates the seed. What changes
     * is only that a 20-minute gap on a contract that prints every 20 minutes stops being called one.
     *
     * <p>The median, not the mean: print gaps are heavy-tailed (one overnight break would drag a mean
     * across every other reading), and a median needs no outlier rule to choose. Nothing here is
     * configured and nothing is fitted — the statistic is re-read from the running stream every time a
     * sensor first sees a name, so a live feed, a delayed feed, a replay and a simulated clock are all
     * read on their own terms with no edit (invariant 9).
     */
    private static long consumptionStepMillis(long intervalMillis, List<Point> points) {
        if (points == null || points.size() < 2) {
            return intervalMillis; // no gap to measure — the poll cadence is all we know
        }
        long[] gaps = new long[points.size() - 1];
        int n = 0;
        for (int i = 1; i < points.size(); i++) {
            long gap = points.get(i).timestampMillis() - points.get(i - 1).timestampMillis();
            if (gap > 0) {
                gaps[n++] = gap;
            }
        }
        if (n == 0) {
            return intervalMillis;
        }
        long[] sorted = java.util.Arrays.copyOf(gaps, n);
        java.util.Arrays.sort(sorted);
        return Math.max(intervalMillis, sorted[n / 2]);
    }

    /** One history read of {@code LOOKBACK_MULTIPLE × samples} steps back from the anchor, in the
     *  store's own clock. Never throws and never reads before the epoch — a history read must not stop
     *  a sensor from starting. */
    private static List<Point> read(History history, String instrumentId, long anchorMillis,
                                    long stepMillis, int samples) {
        long lookback = stepMillis * (long) samples * LOOKBACK_MULTIPLE;
        long since = lookback < 0 || anchorMillis - lookback < 0 ? 0L : anchorMillis - lookback;
        try {
            List<Point> points = history.since(instrumentId, since);
            return points == null ? List.of() : points;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The SYNCHRONISED seed for a multi-name estimator (ADR-0089): at most {@code samples} snapshots,
     * oldest first, each one a map of instrument → the last stored price inside the same
     * {@code intervalMillis}-wide bucket of provider time.
     *
     * <p><b>Why bucketing rather than {@link #seedPrices} per name.</b> A covariance is a statement
     * about CONTEMPORANEOUS returns. {@link #seedPrices} thins each name's series backwards from that
     * name's own anchor, so two names' seeds are aligned only by luck — and a covariance built from
     * misaligned returns measures the misalignment. Bucketing on a common grid of the store's own clock
     * makes every snapshot simultaneous to within one bucket, which is the same synchronisation the
     * live path has (one read of the mark cache per cycle, each mark up to its own age old).
     *
     * <p><b>Last mark carried forward — because that is what live does.</b> A name is read at each grid
     * point from its most recent print at or before it, not only from prints landing inside the bucket.
     * That is exactly the live sampling semantics: the fusion loop reads the mark cache once per cycle
     * and a name that has not printed since the last cycle reads its previous mark, contributing a zero
     * return. Bucketing raw prints instead would drop a name out of the snapshots whenever its series
     * is sparse or its prints straddle a boundary — and a name absent from a snapshot never pairs with
     * anything, so its correlation would silently never accumulate. A carried-forward mark biases a
     * covariance toward zero, which biases the control it feeds toward NOT shrinking the book: the
     * conservative direction for a one-way control. A name whose last print is more than
     * {@link #GAP_TOLERANCE_SAMPLES} buckets old is omitted from that snapshot rather than carried —
     * past that it is not a stale mark, it is no mark.
     *
     * <p>The walk is newest-first and stops at a hole wider than {@link #GAP_TOLERANCE_SAMPLES}
     * buckets — the same tolerance, in the same units, that the per-name seed already bridges a
     * redeploy blip with and truncates a genuine outage at. Buckets are then returned oldest-first so
     * the consumer replays them exactly as it would have received them live.
     *
     * @param anchorMillis the newest point of interest, in the store's own clock (a PROVIDER
     *                     timestamp), never wall-clock now — see the class note
     * @return an empty list when there is no usable history; the caller then cold-starts as before
     */
    public static List<java.util.Map<String, BigDecimal>> jointSeedSamples(
            History history, java.util.Collection<String> instruments, long anchorMillis,
            long intervalMillis, int samples) {
        if (history == null || instruments == null || instruments.isEmpty() || samples <= 0
                || intervalMillis <= 0) {
            return List.of();
        }
        long lookback = intervalMillis * (long) samples * LOOKBACK_MULTIPLE;
        // instrument → (bucket index of provider time → the LAST price printed inside that bucket)
        java.util.Map<String, java.util.NavigableMap<Long, BigDecimal>> byName = new java.util.LinkedHashMap<>();
        java.util.NavigableSet<Long> grid = new java.util.TreeSet<>();
        for (String id : instruments) {
            if (id == null) {
                continue;
            }
            List<Point> points;
            try {
                points = history.since(id, anchorMillis - lookback);
            } catch (RuntimeException e) {
                continue; // a history read must never stop an estimator from starting
            }
            if (points == null) {
                continue;
            }
            for (Point p : points) {
                if (p == null || p.price() == null || p.price().signum() <= 0
                        || p.timestampMillis() > anchorMillis) {
                    continue;
                }
                long bucket = Math.floorDiv(p.timestampMillis(), intervalMillis);
                byName.computeIfAbsent(id, k -> new java.util.TreeMap<>()).put(bucket, p.price());
                grid.add(bucket);
            }
        }
        if (grid.isEmpty()) {
            return List.of();
        }
        // The grid points to emit: newest first, stopping at a hole no name printed across.
        Deque<Long> buckets = new ArrayDeque<>();
        long previousAccepted = Long.MIN_VALUE;
        for (long bucket : grid.descendingSet()) {
            if (buckets.size() >= samples) {
                break;
            }
            if (previousAccepted != Long.MIN_VALUE && previousAccepted - bucket > GAP_TOLERANCE_SAMPLES) {
                break; // a hole in the series: warm from the contiguous tail, never across it
            }
            buckets.addFirst(bucket);
            previousAccepted = bucket;
        }
        List<java.util.Map<String, BigDecimal>> out = new ArrayList<>(buckets.size());
        for (long bucket : buckets) {
            java.util.Map<String, BigDecimal> snapshot = new java.util.LinkedHashMap<>();
            for (var e : byName.entrySet()) {
                var latest = e.getValue().floorEntry(bucket); // the mark as of this grid point
                if (latest != null && bucket - latest.getKey() <= GAP_TOLERANCE_SAMPLES) {
                    snapshot.put(e.getKey(), latest.getValue());
                }
            }
            if (!snapshot.isEmpty()) {
                out.add(snapshot);
            }
        }
        return out;
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

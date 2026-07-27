package io.jethro.app.hedge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much net exposure this desk <em>habitually</em> carries on a hedge axis (ADR-0105) — the
 * level the hedge overlay treats as intended rather than as something to neutralize.
 *
 * <p>The series is the absolute net exposure of the axis in USD, sampled once per hedge
 * <em>cooldown</em> (the soonest one hedge order can be followed by the next — the same clock
 * {@link HedgeTargetChurn} is sampled on, so a REST poll of the panel can never shorten it).
 * {@link #habitualUsd(String)} is the <b>median</b> of the last {@code span} samples, published only
 * once {@code minSample} of them exist.
 *
 * <p><b>Why the median of its own history.</b> A hedge band is a statement of appetite, and any
 * dollar figure the desk has not declared would be an invented risk number (invariant 7 /
 * ADR-0016 — the {@code $250k} lesson in CLAUDE.md). The median of the desk's own observed net
 * exposure asserts only this: the exposure the book habitually runs is the exposure it means to
 * run. It self-calibrates to any feed's scale and volatility, so nothing here special-cases the
 * simulator (invariant 9). It is the same construction {@link io.jethro.app.fusion.BookVolatilityBrake}
 * (ADR-0104) uses for the book's risk level, applied to the overlay's band.
 *
 * <p><b>No ratchet.</b> The quantity sampled is the strategy books' net equity exposure, which this
 * control never touches — the overlay trades an index proxy, which is not a member of the axis. So
 * the reference cannot be dragged by its own effect: a cycle in which the hedge stood down does not
 * move the level that made it stand down.
 *
 * <p>Exact decimal throughout (invariant 1): the level is a USD money figure and it scales a money
 * target, so it never touches binary floating point. Thread-safe for the hedge loop's single writer
 * plus REST readers, keyed per <b>axis</b> (the notional is USD either way, and an ADR-0042 proxy
 * switch must not reset the memory).
 */
public final class HedgeExposureLevel {

    private static final BigDecimal TWO = new BigDecimal("2");
    /** USD scale of the published level — the same scale {@link HedgeTargetChurn#sigmaUsd} publishes. */
    private static final int USD_SCALE = 2;

    private static final class State {
        /** |net exposure| USD, oldest first, bounded by {@code span}. */
        private final Deque<BigDecimal> history = new ArrayDeque<>();
        private long lastSampleMillis = Long.MIN_VALUE;
    }

    private final long sampleIntervalMillis;
    private final int span;
    private final int minSample;
    private final Map<String, State> byAxis = new ConcurrentHashMap<>();

    /**
     * @param sampleIntervalMillis the spacing the series is sampled at — the hedge cooldown. Samples
     *                             offered sooner are ignored, so a busy evaluation loop cannot pack
     *                             the window with one moment's exposure.
     * @param span                 how many samples the median is taken over — an estimation window
     *                             in observations, not a money/risk figure
     * @param minSample            observations required before the level may speak at all; below it
     *                             the caller hedges exactly as it did before ADR-0105
     */
    public HedgeExposureLevel(long sampleIntervalMillis, int span, int minSample) {
        this.sampleIntervalMillis = Math.max(0, sampleIntervalMillis);
        this.span = Math.max(2, span);
        this.minSample = Math.max(2, minSample);
    }

    /** Offer the axis's current signed net exposure in USD; only its magnitude is retained. */
    public void observe(String axis, BigDecimal netExposureUsd, long nowMillis) {
        if (axis == null || netExposureUsd == null) {
            return;
        }
        State s = byAxis.computeIfAbsent(axis, k -> new State());
        synchronized (s) {
            if (s.lastSampleMillis != Long.MIN_VALUE
                    && nowMillis - s.lastSampleMillis < sampleIntervalMillis) {
                return;
            }
            s.lastSampleMillis = nowMillis;
            s.history.addLast(netExposureUsd.abs());
            while (s.history.size() > span) {
                s.history.removeFirst();
            }
        }
    }

    /**
     * The axis's habitual net exposure — the median of the retained |net| series, in USD — or empty
     * while the series is shorter than {@code minSample}, in which case the caller must not band the
     * hedge at all.
     */
    public Optional<BigDecimal> habitualUsd(String axis) {
        State s = axis == null ? null : byAxis.get(axis);
        if (s == null) {
            return Optional.empty();
        }
        synchronized (s) {
            int n = s.history.size();
            if (n < minSample) {
                return Optional.empty();
            }
            BigDecimal[] sorted = s.history.toArray(new BigDecimal[0]);
            Arrays.sort(sorted);
            // Mean of the two central order statistics on an even count; division by two always
            // terminates in decimal, so the median is exact before it is published at USD scale.
            BigDecimal median = n % 2 == 1 ? sorted[n / 2]
                    : sorted[n / 2 - 1].add(sorted[n / 2]).divide(TWO);
            return Optional.of(median.setScale(USD_SCALE, RoundingMode.HALF_UP));
        }
    }

    /** Observations retained on an axis — the operator's read on whether the level is warm. */
    public int samples(String axis) {
        State s = axis == null ? null : byAxis.get(axis);
        if (s == null) {
            return 0;
        }
        synchronized (s) {
            return s.history.size();
        }
    }
}

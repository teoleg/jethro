package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The round-trip execution cost a name's own <b>print series</b> implies, for the ADR-0075 per-name
 * edge test — the estimate that stands in for a name the desk has never filled <em>and</em> whose feed
 * publishes no two-sided quote (ADR-0112).
 *
 * <h2>The gap this closes</h2>
 * ADR-0075 charges a name with no measured round trip the desk's <b>blended</b> cost. ADR-0099
 * narrowed that to the name's own quoted touch where a quote exists. Neither rung fires on a feed that
 * publishes trade prints without a book — and on such a feed the blend is a closed loop:
 * <ul>
 *   <li>a name is only ever measured <em>after</em> it has filled;</li>
 *   <li>an unmeasured name is charged the blend;</li>
 *   <li>when the blend does not clear the significance hurdle, the gate holds every unmeasured name
 *       <b>reduce-only</b> — so it never fills, so it is never measured.</li>
 * </ul>
 * The tradable universe then freezes to whichever names happened to have filled before the hurdle
 * tightened, and no amount of edge anywhere else can unfreeze it. That is a property of the
 * measurement plumbing, not of the desk's opinion about those names.
 *
 * <h2>What is charged instead — Roll (1984)</h2>
 * The effective spread is recoverable from the print series alone, with no book, by the standard
 * implicit estimator (R. Roll, <i>A Simple Implicit Measure of the Effective Bid-Ask Spread in an
 * Efficient Market</i>, Journal of Finance 39(4), 1984; see also Hasbrouck, <i>Empirical Market
 * Microstructure</i> ch. 4). Under the model — an efficient log price following a random walk, plus a
 * half-spread bounce whose sign is i.i.d. and independent of it:
 * <pre>
 *   xₜ = mₜ + (s/2)·qₜ           qₜ = ±1 with equal probability, mₜ a random walk
 *   rₜ = xₜ − xₜ₋₁
 *   cov(rₜ, rₜ₋₁) = −(s/2)²      the random-walk term is serially uncorrelated and drops out
 *   ⟹ s = 2·√(−cov(rₜ, rₜ₋₁))
 * </pre>
 * {@code s} is the FULL effective spread as a fraction of price; × 10⁴ makes it basis points. That is
 * one round trip in exactly the sense the other two rungs use it — {@link QuotedSpreadCost} states the
 * same identity for a quote ("crossing to enter pays the half-spread and crossing to exit pays it
 * again"), and the TCA rung is one-way implementation shortfall doubled. The three are directly
 * comparable because they are three measurements of the same quantity.
 *
 * <h2>When it declines to measure</h2>
 * The estimator is defined only where the sample autocovariance is <b>negative</b>. A positive reading
 * means the bounce is not what dominates the series at this sampling frequency (a trending or
 * thin-printing tape), and Roll's model does not hold there. Rather than take a root of a negative
 * number — or, worse, flip the sign and report a cost anyway, which is the classic misuse — this
 * returns <em>no measurement</em> and the name falls back to the blend exactly as it does today. Same
 * for a series shorter than {@link #MIN_RETURNS} steps. Empty is deliberately distinct from zero
 * (invariant 7 / ADR-0016: an absent measurement must never become a number).
 *
 * <p><b>Repeated prints are collapsed, not counted as zero returns.</b> Marks are ~1 Hz conflated, so
 * a name that has not traded re-publishes its last price. A run of identical prints is one print, not
 * a sequence of zero-return steps: counting them would pull the autocovariance toward zero and
 * understate the spread — the unsafe direction for a gate. This is the same rule the forecast sensors
 * already apply to a repeated stale price ({@link ReversionForecastLifecycle}: "a repeated stale price
 * would feed the sensor a fabricated zero-return step").
 *
 * <h2>Known bias, and why the floor exists</h2>
 * Roll is unbiased under its own assumptions and <em>over</em>-states the spread when signs alternate
 * more than i.i.d. (a perfectly alternating tape returns 2s — see the worked example in
 * {@code EffectiveSpreadCostTest}); over-stating a cost is the safe direction for a gate. It can
 * understate when prints are sparse relative to the sampling interval, and a series whose bounce is
 * negligible at this frequency reads a near-zero spread rather than declining. So this rung never charges a
 * name less than {@link #withEffectiveSpreadFallback}'s {@code floorBps} — the cheapest round trip the
 * desk has <b>actually paid anywhere</b>. The desk may infer that an unfilled name is cheaper than the
 * blend; it may not infer that it is cheaper than anything it has ever executed.
 *
 * <p>That floor also makes the change provably one-way at the desk level. {@link EdgeGate} takes the
 * desk-wide verdict at the CHEAPEST round trip in the map-plus-blend; passing that same minimum in as
 * {@code floorBps} means no entry this class adds can be below it, so the minimum — and with it the
 * desk-wide verdict, every source's {@code netEdgeBps} and the ADR-0101 buffer's reference cost — is
 * bit-for-bit unchanged. Only the PER-NAME hurdle of a name that had no measurement of its own moves,
 * and only from "the average of the names the desk already trades" to "what this name's own tape
 * says", bounded below by what the desk has paid.
 *
 * <p>Feed-agnostic (invariant 9): the input is whatever price series the running feed publishes —
 * never a level, an asset-class assumption or a configured spread, so it self-calibrates to any feed's
 * quality. Prices arrive as exact decimal and only the dimensionless log-return ratio becomes a
 * {@code double}, at exactly the boundary {@link StreamVolatility} already draws (invariant 1).
 * Nothing here is money or a size: it produces a cost in bps that gates a name and sizes nothing
 * (ADR-0016 / invariant 7).
 */
public final class EffectiveSpreadCost {

    /**
     * The fewest distinct-price steps that may produce an estimate. A statistical sample size, not a
     * money, risk or exposure dial: Roll's estimator is a second moment, so its standard error falls
     * as 1/√n and a handful of steps would report noise as a cost.
     */
    static final int MIN_RETURNS = 60;

    /**
     * The most recent steps used. A window length, not a money dial — it bounds both the work per
     * cycle and how far back a spread regime is allowed to persist in the estimate.
     */
    static final int MAX_RETURNS = 600;

    /** Enough precision for a price ratio; the result is a dimensionless statistic, never money. */
    private static final MathContext RATIO = MathContext.DECIMAL64;

    /** A fraction of price → basis points. */
    private static final double BPS = 10_000.0;

    private EffectiveSpreadCost() {
    }

    /**
     * The Roll (1984) effective spread of one print series, in basis points of notional — i.e. one
     * round trip — or empty when the series cannot state one.
     *
     * @param prints one instrument's prints, OLDEST FIRST, as exact decimals; nulls and non-positive
     *               prices end the usable series rather than being skipped over, because a hole is not
     *               an adjacency and pairing across it would fabricate a return
     * @return the effective spread in bps, or empty when there are too few distinct-price steps or the
     *         lag-1 autocovariance is not negative (Roll's model does not hold — no measurement)
     */
    public static OptionalDouble roundTripBps(List<BigDecimal> prints) {
        if (prints == null || prints.size() < 2) {
            return OptionalDouble.empty();
        }
        // Log returns over CHANGES only: a repeated conflated print is the same print, not a zero-
        // return step (see the class note). The walk runs newest-first so a hole truncates the tail
        // that is furthest from now, and stops once MAX_RETURNS steps are in hand.
        List<Double> reversed = new ArrayList<>(Math.min(prints.size(), MAX_RETURNS));
        BigDecimal newer = null;
        for (int i = prints.size() - 1; i >= 0 && reversed.size() < MAX_RETURNS; i--) {
            BigDecimal p = prints.get(i);
            if (p == null || p.signum() <= 0) {
                break; // a hole in the series: estimate from the contiguous tail, never across it
            }
            if (newer == null) {
                newer = p;
                continue;
            }
            int cmp = p.compareTo(newer);
            if (cmp == 0) {
                continue; // a repeated print is not a step
            }
            double r = Math.log(newer.divide(p, RATIO).doubleValue());
            if (!Double.isFinite(r)) {
                break;
            }
            reversed.add(r);
            newer = p;
        }
        int n = reversed.size();
        if (n < MIN_RETURNS) {
            return OptionalDouble.empty();
        }
        // Order is irrelevant to a lag-1 autocovariance of a demeaned series (the pairing is the same
        // set of adjacent pairs either way), so the reversed list is used as-is.
        double sum = 0.0;
        for (double r : reversed) {
            sum += r;
        }
        double mean = sum / n;
        double cross = 0.0;
        for (int i = 1; i < n; i++) {
            cross += (reversed.get(i) - mean) * (reversed.get(i - 1) - mean);
        }
        double cov = cross / (n - 1); // n-1 adjacent pairs, averaged
        if (!Double.isFinite(cov) || cov >= 0.0) {
            return OptionalDouble.empty(); // Roll's model does not hold here — no measurement
        }
        double spreadBps = 2.0 * Math.sqrt(-cov) * BPS;
        if (!Double.isFinite(spreadBps) || spreadBps <= 0.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(spreadBps);
    }

    /**
     * The ADR-0075 per-name cost map, with every name that still has no cost of its own charged the
     * effective spread its own prints imply instead of the desk blend — floored at {@code floorBps}.
     *
     * @param costs    the map so far: measured round trips (ADR-0075) plus any quoted fallbacks
     *                 (ADR-0099). An entry here always wins — a fill, and then a live quote, both beat
     *                 an inference from prints
     * @param floorBps the cheapest round trip in {@code costs} and the desk blend, i.e. exactly the
     *                 minimum {@link EdgeGate} takes its desk-wide verdict at. Nothing added here may
     *                 be below it, which is what makes this change per-name only (see the class note).
     *                 A non-finite or non-positive floor means the desk has no cost measurement to
     *                 anchor on, and the map is returned untouched
     * @param estimated instrumentId → the Roll effective spread its prints imply, price-quoted names
     *                  only (a rate-quoted "bp" is an additive basis point of RATE, not a fraction of
     *                  notional — the same unit rule the other two rungs apply)
     * @return a new map; the inputs are not modified
     */
    public static Map<String, Double> withEffectiveSpreadFallback(Map<String, Double> costs,
                                                                  double floorBps,
                                                                  Map<String, Double> estimated) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (costs != null) {
            for (var e : costs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        if (estimated == null || !Double.isFinite(floorBps) || floorBps <= 0.0) {
            return out;
        }
        for (var e : estimated.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || out.containsKey(e.getKey())) {
                continue; // a measured or quoted cost always wins
            }
            double bps = e.getValue();
            if (!Double.isFinite(bps) || bps <= 0.0) {
                continue;
            }
            out.put(e.getKey(), Math.max(floorBps, bps));
        }
        return out;
    }

    /**
     * The minimum {@link EdgeGate} will take its desk-wide verdict at, given the blend and the per-name
     * costs measured so far — the floor {@link #withEffectiveSpreadFallback} must not undercut.
     *
     * @return the smallest of {@code deskBlendBps} and the map's positive finite values
     */
    public static double cheapestMeasured(double deskBlendBps, Map<String, Double> costs) {
        double cheapest = deskBlendBps;
        if (costs != null) {
            for (Double c : costs.values()) {
                if (c != null && Double.isFinite(c) && c > 0.0) {
                    cheapest = Math.min(cheapest, c);
                }
            }
        }
        return cheapest;
    }
}

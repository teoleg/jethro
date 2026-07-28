package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A continuous, self-calibrating <b>cross-sectional residual reversion sensor</b> (ADR-0121): over a
 * common lookback window, how far has this name moved <em>relative to its peers</em>, and how much of
 * that relative move is worth fading?
 *
 * <p><b>Why the desk needs one, stated from its own measurements.</b> Of the four sources this desk
 * currently runs, exactly one has a positive measured mean at every horizon rung it is scored on —
 * {@code reversion}, the ADR-0070 own-price range sensor — and it is nowhere near significant, while
 * {@code trend} measures negative on the two best-sampled rungs. So the desk has one weak positive
 * structure and no way to sharpen it, because re-weighting a source that has no demonstrated edge
 * cannot create edge. This sensor is the literature's answer to exactly that reading: the reliable part
 * of short-horizon reversal is the <b>idiosyncratic</b> component, not the raw own-price move (Lehmann,
 * "Fads, Martingales, and Market Efficiency", <i>QJE</i> 1990; Lo &amp; MacKinlay, "When Are Contrarian
 * Profits Due to Stock Market Overreaction?", <i>RFS</i> 1990; Khandani &amp; Lo, "What Happened to the
 * Quants in August 2007?", <i>J. Investment Management</i> 2007). Fading a name that is down because
 * the whole cross-section is down is a bet on the market, not a reversal bet, and it carries the
 * market's expectancy — roughly zero over an hour — with the reversal bet's turnover cost. Fading a
 * name that is down <em>against its peers</em> is the documented effect.
 *
 * <p><b>It is not the own-price reversion sensor rescaled.</b> That distinction is the point of adding
 * it as a separate source rather than as a tweak to ADR-0070. The two disagree by construction whenever
 * the cross-section moves together: on a day the whole equity complex sells off, the range sensor is
 * long everything (every name is at the bottom of its own range) while this sensor is flat on the
 * complex and only long the names that fell <em>more</em> than the complex did. Its input is a
 * different statistic (a relative return over a common window, not a level inside a name's own
 * realised range), and its cross-section is a different object (a peer group, not a single name). Which
 * of the two is right is a measurement, and the desk already has the machinery to make it: this source
 * accumulates its own phase-1 telemetry and must clear the ADR-0064 edge gate on its own realised
 * expectancy before it may size anything. It arrives with no evidence and earns its allocation, or it
 * does not.
 *
 * <p><b>The computation</b>, on each sweep of the cross-section:
 * <pre>
 *   window   = [now − L, now] in the FEED's clock, L = lookbackMillis
 *   anchor_i = the oldest print of i inside the window, current_i = the newest
 *   admit i  ⇔ anchor_i lands in the first half of the window AND current_i in the second half
 *   span_i   = current_i.t − anchor_i.t                          the return actually observed
 *   r_i      = ln(current_i.p / anchor_i.p) / sqrt(span_i)        vol-time normalised (see below)
 *   per peer group g with at least minPeers admitted names:
 *     m_g    = median_{i∈g}(r_i)                                  the common move, robustly
 *     s_g    = 1.4826 · median_{i∈g}|r_i − m_g|                   robust σ of the cross-section
 *     z_i    = (r_i − m_g) / s_g                                  the residual, in its own units
 *     score  = −clamp(z_i, ±maxAbsZ)                              fade the residual
 * </pre>
 * The leading minus is the fade. A name that has out-run its peers reads negative (sell the winner), one
 * that has lagged reads positive — the classic cross-sectional contrarian portfolio, expressed as a
 * continuous per-name conviction instead of a decile sort so it fits the desk's existing forecast
 * pipeline.
 *
 * <p><b>Median and MAD, not mean and standard deviation.</b> The peer groups here are small — single
 * digits after the admission rule — and a single bad print or one genuinely idiosyncratic name would
 * drag a mean and inflate a standard deviation enough to reverse the sign of everyone else's residual.
 * The median and the median absolute deviation have a 50% breakdown point: half the group would have to
 * be corrupted before either moves materially. The 1.4826 factor is the standard consistency constant
 * {@code 1/Φ⁻¹(¾)} that puts the MAD on the same scale as σ for a normal sample (Hampel et al.,
 * <i>Robust Statistics</i>, 1986) — a statistical constant, not a dial.
 *
 * <p><b>Why divide by √span.</b> Names print at wildly different rates on this desk — seconds on the
 * liquid equities, tens of minutes on some FX and futures — so even inside a common window the returns
 * actually observed span different lengths of time. Under a random walk a return's scale grows as the
 * square root of its horizon, so comparing a 900 s return with a 400 s one un-normalised would make the
 * slow-printing name look systematically becalmed and hand it a spuriously negative residual every
 * sweep. Dividing by √span puts every name's move in the same vol-time units before they are compared.
 * It is the same reasoning the desk's own risk sensors already use and it introduces no dial.
 *
 * <p><b>Peer groups are reference data.</b> Names are compared only within their own asset class, read
 * from the instrument master — the universe's single source (invariant 9). A cross-section that mixed a
 * major FX pair with a single-stock name would be dominated by the equity's volatility, and the MAD
 * scale would read every FX residual as ≈ 0. Nothing here knows about the simulator or about any
 * particular feed: the window, the scale and the peer group are all read off whatever stream is running.
 *
 * <p>Everything in this class is a dimensionless statistic derived from a price series. There is no
 * money, risk or exposure number here and nothing it produces is a size (ADR-0016 / invariant 7):
 * it publishes a conviction, and the deterministic fusion layer, the edge gate, the conviction floor
 * and the pre-trade floor all still stand between that and a fill. Prices are held as exact decimals
 * (invariant 1) and converted to double only to form the dimensionless log ratio.
 *
 * <p>Not thread-safe: confined to the single scheduled thread that drives it, exactly like the ADR-0066
 * and ADR-0070 sensors.
 */
public final class CrossSectionalReversionForecaster {

    /**
     * The consistency constant that puts a median absolute deviation on the same scale as a standard
     * deviation for a normal sample: {@code 1/Φ⁻¹(0.75)}. A statistical constant (Hampel et al., 1986),
     * not a dial and not a money number.
     */
    static final double MAD_TO_SIGMA = 1.4826;

    /**
     * Shape dials — lookback length, the smallest peer group a robust scale may be estimated from, and
     * the bound beyond which a residual is treated as a data artefact rather than a signal. None of
     * them is a money, risk or exposure number: the reading is dimensionless and the book's size stays
     * governed by unit-notional-usd, the conviction floor, the edge gate and the guardrail.
     */
    public record Params(long lookbackMillis, int minPeers, double maxAbsZ) {
        public Params {
            if (lookbackMillis < 1_000L) {
                lookbackMillis = 1_000L; // a window shorter than a second cannot hold two prints
            }
            if (minPeers < 3) {
                // A median absolute deviation of two points is |difference|/2 — a range, not a
                // dispersion estimate, and it makes both residuals exactly ±1 by construction.
                minPeers = 3;
            }
            if (!(maxAbsZ > 0)) {
                maxAbsZ = 4.0;
            }
        }
    }

    /**
     * One name's reading, with the parts it was built from so the operator can see WHY it says what it
     * says. {@code score} is the published forecast (already faded and clamped); {@code peers} is how
     * many names its group compared it against.
     */
    public record Reading(String instrumentId, String peerGroup, double score, double normalisedReturn,
                          double peerMedian, double peerScale, int peers, long spanMillis) {
    }

    /** One stored print: the feed's own timestamp and the exact-decimal price. */
    private record Print(long atMillis, BigDecimal price) {
    }

    private final Params params;
    private final Map<String, Deque<Print>> prints = new LinkedHashMap<>();
    private final Map<String, String> peerGroups = new LinkedHashMap<>();

    public CrossSectionalReversionForecaster(Params params) {
        this.params = params;
    }

    public Params params() {
        return params;
    }

    /**
     * Records one print for a name. Callers must pass only prints the market's own clock has actually
     * advanced across (ADR-0113) — a republished last-value mark would add a fabricated zero-return
     * step, which here would shrink the name's observed span rather than its scale estimator, and would
     * let a name that has stopped printing keep claiming a fresh reading.
     *
     * <p>{@code atMillis} is the PROVIDER timestamp, the same clock the window is expressed in
     * (ADR-0071's "one clock only"): mixing wall clock with provider time silently empties the window
     * by exactly the feed's delay.
     */
    public void observe(String instrumentId, String peerGroup, BigDecimal price, long atMillis) {
        if (instrumentId == null || peerGroup == null || price == null || price.signum() <= 0) {
            return;
        }
        peerGroups.put(instrumentId, peerGroup);
        Deque<Print> series = prints.computeIfAbsent(instrumentId, k -> new ArrayDeque<>());
        // Out-of-order prints would corrupt the anchor/current pair; the mark cache already rejects
        // superseded ticks, so drop rather than re-sort (an unordered deque is the more expensive bug).
        if (!series.isEmpty() && series.peekLast().atMillis() >= atMillis) {
            return;
        }
        series.addLast(new Print(atMillis, price));
    }

    /**
     * Computes this sweep's cross-section. {@code nowMillis} is the market's own clock — in practice the
     * newest provider timestamp the caller saw this sweep, never {@code System.currentTimeMillis()}.
     *
     * @return one reading per name that was admitted AND belongs to a group large enough to score it;
     *         names with no view simply do not appear
     */
    public Map<String, Reading> sweep(long nowMillis) {
        long windowStart = nowMillis - params.lookbackMillis();
        long midpoint = nowMillis - params.lookbackMillis() / 2;
        // instrument → (vol-time normalised return, the span it was observed over)
        Map<String, double[]> admitted = new LinkedHashMap<>();
        Map<String, Long> spans = new LinkedHashMap<>();
        for (var entry : prints.entrySet()) {
            Deque<Print> series = entry.getValue();
            while (!series.isEmpty() && series.peekFirst().atMillis() < windowStart) {
                series.removeFirst();
            }
            if (series.size() < 2) {
                continue;
            }
            Print anchor = series.peekFirst();
            Print current = series.peekLast();
            // The admission rule, which carries no dial: the name must have printed in BOTH halves of
            // the window, so the return it contributes actually spans the window the group is compared
            // over. Without it a name that stopped printing ten minutes ago would contribute a stale
            // partial return as if it were current, and one that only started printing a minute ago
            // would contribute a minute's move against everyone else's quarter hour.
            if (anchor.atMillis() > midpoint || current.atMillis() <= midpoint) {
                continue;
            }
            long span = current.atMillis() - anchor.atMillis();
            if (span <= 0) {
                continue;
            }
            double ratio = current.price().doubleValue() / anchor.price().doubleValue();
            if (!(ratio > 0)) {
                continue;
            }
            double normalised = Math.log(ratio) / Math.sqrt(span / 1_000.0);
            if (!Double.isFinite(normalised)) {
                continue;
            }
            admitted.put(entry.getKey(), new double[]{normalised});
            spans.put(entry.getKey(), span);
        }
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (String id : admitted.keySet()) {
            byGroup.computeIfAbsent(peerGroups.getOrDefault(id, ""), k -> new ArrayList<>()).add(id);
        }
        Map<String, Reading> out = new LinkedHashMap<>();
        for (var group : byGroup.entrySet()) {
            List<String> members = group.getValue();
            if (members.size() < params.minPeers()) {
                continue; // too few peers for a robust scale — no view, rather than a fabricated one
            }
            double[] returns = new double[members.size()];
            for (int i = 0; i < members.size(); i++) {
                returns[i] = admitted.get(members.get(i))[0];
            }
            double median = median(returns.clone());
            double[] deviations = new double[returns.length];
            for (int i = 0; i < returns.length; i++) {
                deviations[i] = Math.abs(returns[i] - median);
            }
            double scale = MAD_TO_SIGMA * median(deviations);
            if (!(scale > 0)) {
                // Half the group or more moved identically: there is no dispersion to measure a
                // residual against, so the sensor has no view here rather than an infinite one.
                continue;
            }
            for (int i = 0; i < members.size(); i++) {
                String id = members.get(i);
                double z = (returns[i] - median) / scale;
                double clamped = Math.max(-params.maxAbsZ(), Math.min(params.maxAbsZ(), z));
                out.put(id, new Reading(id, group.getKey(), -clamped, returns[i], median, scale,
                        members.size(), spans.get(id)));
            }
        }
        return out;
    }

    /** How many names currently hold any print history — an operator/test count, it sizes nothing. */
    public int trackedNames() {
        return prints.size();
    }

    /** The median of the array, which this method SORTS in place; callers pass a copy they own. */
    private static double median(double[] values) {
        Arrays.sort(values);
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0;
    }

    /** The names this sensor has seen, for the warm-start bookkeeping and tests. */
    public java.util.Set<String> names() {
        return Collections.unmodifiableSet(prints.keySet());
    }
}

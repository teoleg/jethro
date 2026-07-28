package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The round-trip execution cost a name's own <b>live two-sided quote</b> implies, for the ADR-0075
 * per-name edge test — the estimate that stands in for a name the desk has never filled (ADR-0099).
 *
 * <h2>The gap this closes</h2>
 * ADR-0075 tests every name against <em>its own</em> measured round trip before letting the desk put
 * risk on it, and charges a name with no fill history the desk's <b>blended</b> measured cost, because
 * at the time there was nothing else to charge it that was not invented. That fallback is silently
 * wrong in one direction: a name is only ever measured <em>after</em> it has traded, so the desk enters
 * every new name believing it costs the average of the names it already trades, discovers the real
 * figure out of its own PnL, and only then applies the veto. On a cross-section whose measured round
 * trips span two orders of magnitude, that discovery is the expensive part.
 *
 * <h2>What is charged instead</h2>
 * The quoted touch is a measurement of that name, on the stream, available before the first fill:
 * <pre>
 *   roundTripBps = (ask − bid) / mid × 10⁴,     mid = (bid + ask)/2
 *                = 20000 × (ask − bid) / (ask + bid)
 * </pre>
 * — one exact division rather than two, so the midpoint never needs a rounded intermediate. This is
 * the standard ex-ante transaction-cost proxy (Grinold &amp; Kahn, <i>Active Portfolio Management</i>
 * 2e, ch. 16; Almgren &amp; Chriss 2000): crossing to enter pays the half-spread and crossing to exit
 * pays it again, so one full quoted spread is one round trip. It is measured in exactly the units the
 * desk's own TCA reports — {@code ExecutionQualityRepository} averages one-way slippage against the
 * arrival mark, which the caller doubles — so the two are directly comparable.
 *
 * <h2>Why it is a fallback and never an override</h2>
 * A quote states what a round trip <em>would</em> cost at the touch; the desk's realised TCA states
 * what its own orders actually paid, which under ADR-0084 passive entries is often less. So a name's
 * own measured cost always wins where it exists — this only ever fills the gap where nothing has been
 * measured, and it is displaced permanently by the first fill.
 *
 * <h2>A round trip that pays the desk is not a measurement of cost (ADR-0106)</h2>
 * The realised figure is an implementation shortfall against the arrival mark, so it carries the
 * price DRIFT over the fill window as well as the spread the desk crossed. Under ADR-0084 the desk
 * posts to enter, and a passive order is filled precisely when the price comes to it — so the drift
 * term is systematically favourable at entry, and on a name where it exceeds the exit's half-spread
 * the arithmetic returns a <b>negative</b> round trip. A round trip cannot pay the desk: entering and
 * leaving a position crosses the touch or waits for it, and the fee is charged twice either way. Such
 * a reading is drift that belongs to the signal, booked as if it were execution.
 *
 * <p>Left in the map it does real damage, because {@link EdgeGate} states the desk-wide verdict at the
 * CHEAPEST round trip in the map (ADR-0075): a single negative entry becomes the desk-wide cost, and
 * {@code netEdgeBps = avgReturnBps − cheapest} then exceeds the expectancy that was actually measured
 * — the gate crediting the desk with edge no signal produced. It also collapses that name's ADR-0101
 * no-trade buffer {@code 2C/μ} to its Carver floor, so the desk re-trades its least honestly-priced
 * name the most often. A non-positive measurement is therefore treated here as NO measurement, and the
 * name falls back to the quoted/blend path exactly as an as-yet-unfilled name does (ADR-0099) — the
 * same rule, reached because a reading that cannot be a cost tells the desk nothing about its cost.
 *
 * <h2>One-way by construction</h2>
 * {@link #withQuotedFallback} charges an unmeasured name {@code max(deskBlend, quotedRoundTrip)} —
 * never less than the blend it is charged today. Three consequences, all provable from that line:
 * <ul>
 *   <li><b>It can only ever remove a trade, never add one.</b> Every per-name hurdle is greater than
 *       or equal to today's, and {@link EdgeGate}'s test is monotone in cost.</li>
 *   <li><b>The desk-wide verdict can only get STRICTER.</b> That verdict is taken at the cheapest
 *       round trip in the map-plus-blend. No entry added by the quoted fallback is below the blend, so
 *       it cannot lower the minimum; and dropping a non-positive measurement (ADR-0106) can only
 *       RAISE it. Either way the cost the desk charges itself never falls.</li>
 *   <li><b>No number is invented.</b> The value is the live quote or the existing blend, whichever is
 *       larger; nothing is chosen, and a name with no two-sided quote is left exactly as it is today —
 *       absent from the map, which {@link EdgeGate} already charges the blend.</li>
 * </ul>
 *
 * <p>Feed-agnostic (invariant 9): the input is whatever bid/ask the running feed publishes — a venue's
 * top of book on a live feed, the synthesised touch on the sim — never a level, a class assumption or a
 * configured spread. Exact decimal on the quote (invariant 1); only the dimensionless bps ratio becomes
 * {@code double}, as every other bps figure in this package does.
 */
public final class QuotedSpreadCost {

    /** bps of a ratio: (ask − bid)/mid × 10⁴, written over (ask + bid) so the /2 in mid cancels. */
    private static final BigDecimal TWENTY_THOUSAND = BigDecimal.valueOf(20_000);

    /** Scale of the dimensionless bps ratio before it becomes a double. */
    private static final int BPS_SCALE = 8;

    private QuotedSpreadCost() {
    }

    /**
     * The round-trip cost in basis points of notional implied by one two-sided quote, or empty when
     * the quote cannot state one (missing side, non-positive price, crossed or locked market). Empty
     * is deliberately distinct from zero: a locked market has a measured zero cost, an absent quote
     * has no measurement at all and must not be turned into one (ADR-0016 / invariant 7).
     *
     * <p>Worked example: bid 99.90, ask 100.10 → 20000 × 0.20 / 200.00 = <b>20.0000 bps</b>, i.e. the
     * 20 bps touch costs 20 bps to cross and come back. bid 99.99, ask 100.01 → 20000 × 0.02 / 200.00
     * = <b>2.0000 bps</b>.
     */
    public static OptionalDouble roundTripBps(BigDecimal bid, BigDecimal ask) {
        if (bid == null || ask == null || bid.signum() <= 0 || ask.compareTo(bid) < 0) {
            return OptionalDouble.empty(); // no quote, or a crossed book — nothing measured
        }
        BigDecimal sum = ask.add(bid);
        if (sum.signum() <= 0) {
            return OptionalDouble.empty();
        }
        BigDecimal bps = TWENTY_THOUSAND.multiply(ask.subtract(bid))
                .divide(sum, BPS_SCALE, RoundingMode.HALF_EVEN);
        return OptionalDouble.of(bps.doubleValue());
    }

    /**
     * The ADR-0075 per-name cost map, with every name that has no measured round trip charged its own
     * quoted one instead of the desk blend — but never less than that blend, so this is strictly a
     * tightening (see the class doc).
     *
     * @param measured        instrumentId → the desk's own MEASURED round trip in bps (ADR-0075); a
     *                        POSITIVE entry is returned untouched, a fill always beating a quote. A
     *                        non-positive (or non-finite) entry is not a cost and is treated as absent
     *                        (ADR-0106), so the name takes the quoted/blend fallback below
     * @param deskBlendBps    the blended measured round trip an unmeasured name is charged today — the
     *                        floor below which this may never charge anyone
     * @param quotedRoundTrip instrumentId → the round trip its live quote implies, for the price-quoted
     *                        names only (a rate-quoted "bp" is an additive basis point of RATE, not a
     *                        fraction of notional, so the caller excludes them exactly as the TCA map
     *                        does)
     * @return a new map; the inputs are not modified
     */
    public static Map<String, Double> withQuotedFallback(Map<String, Double> measured,
                                                         double deskBlendBps,
                                                         Map<String, Double> quotedRoundTrip) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (measured != null) {
            for (var e : measured.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                double m = e.getValue();
                // ADR-0106: a round trip cannot pay the desk. A non-positive reading is arrival-mark
                // drift booked as execution, not a cost — drop it so the name takes the fallback.
                if (!Double.isFinite(m) || m <= 0.0) {
                    continue;
                }
                out.put(e.getKey(), m);
            }
        }
        if (quotedRoundTrip == null) {
            return out;
        }
        for (var e : quotedRoundTrip.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || out.containsKey(e.getKey())) {
                continue; // measured beats quoted, always
            }
            double quoted = e.getValue();
            if (!Double.isFinite(quoted)) {
                continue;
            }
            // One-way: an unmeasured name is charged the WIDER of the two, so no name's hurdle can
            // fall below what it is today and the desk-wide minimum cannot move.
            out.put(e.getKey(), Math.max(deskBlendBps, quoted));
        }
        return out;
    }
}

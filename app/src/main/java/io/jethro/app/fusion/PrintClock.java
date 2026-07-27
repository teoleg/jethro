package io.jethro.app.fusion;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Admits a mark to a continuous sensor only when the <b>market's own clock has advanced</b> since that
 * sensor last consumed the name — i.e. only when a genuinely new print happened (ADR-0113).
 *
 * <p><b>The problem this solves.</b> The mark cache is a LAST-VALUE conflation point: it republishes an
 * instrument's most recent price on whatever cadence the adapter polls, whether or not the tape printed.
 * Its {@code stale} flag is a <em>warm-load</em> marker (set by {@code MarkCache.loadStale}, cleared by
 * the first live update), not a freshness measure — so once any live tick has arrived it is false
 * forever, including for a name whose tape stopped hours ago. Both continuous sensors already carried
 * the comment "never advance the sensor's windows on a repeated stale price" guarded by exactly that
 * flag, which means the guard was <b>inert in the one situation it was written for</b>.
 *
 * <p>What that costs is not silence, it is corruption, and in two places:
 * <ul>
 *   <li><b>The scale estimator.</b> Both sensors divide their raw reading by an EWMA of its own typical
 *       magnitude. A run of repeated prices is a run of zero-magnitude readings, which drags that
 *       denominator toward zero. When the tape resumes, the first real move is divided by a near-zero
 *       scale and the name pins at the forecast cap — maximum conviction at the moment the sensor knows
 *       least. That is precisely the ADR-0066 failure, re-entered through a different door: there the
 *       denominator was an arbitrary anchor from one observation, here it is an anchor decayed by
 *       observations that never happened.</li>
 *   <li><b>The measured expectancy that gates money.</b> Each published reading is recorded as a
 *       directional call in the signal telemetry. A frozen name keeps publishing the same reading off
 *       the same price, so the desk books a fresh call every cycle and every one of them resolves at
 *       exactly zero realised return. Those structural zeros enter the cohort statistics that the
 *       ADR-0075 edge gate reads, shrinking a source's standard error with observations that carry no
 *       information — manufacturing significance in the test that decides whether the desk may put risk
 *       on.</li>
 * </ul>
 *
 * <p><b>The rule.</b> A mark is admitted iff its PROVIDER timestamp is strictly newer than the provider
 * timestamp of the last mark this sensor consumed for that instrument. Provider time is the market's
 * honest clock (invariant 5); ingest time only reflects our poll cadence, which is exactly why it cannot
 * answer this question — on this desk the frozen equities carry sub-second ingest ages against provider
 * clocks two hours old.
 *
 * <p><b>No dial, and nothing to calibrate.</b> This is not an age threshold — there is no "too old" to
 * choose, and therefore no invented number (CLAUDE.md: every number that gates money, risk or exposure
 * carries its source). It is the parameter-free statement "did the tape print since I last looked?", so
 * it reads a live feed, a 15-minute-delayed feed, a replay and a simulated clock identically and adapts
 * to each without an edit (invariant 9 — nothing here is sim-specific, and nothing is feed-specific).
 *
 * <p><b>It also makes ADR-0071's stated invariant true.</b> The warm-restart seed replays the durable
 * mark series, which is keyed by provider time and holds one point per distinct print — so the seed has
 * always advanced once per print. The live path advancing once per wall-clock interval meant the sensor
 * did NOT "see exactly the sequence it would have seen had the process been running"; it saw that
 * sequence with duplicates interleaved. Gating the live path on the same clock the seed is thinned by
 * makes the two halves of a sensor's history one series.
 *
 * <p><b>Consequence, deliberately.</b> A name whose tape has stopped stops being re-submitted to the
 * {@link ForecastRegistry}, so its view expires on the registry's existing freshness window and it drops
 * out of the planned cross-section — which is that window's documented purpose ("a source that has gone
 * quiet drops out rather than lingering as a phantom vote"), previously unreachable because the sensors
 * refreshed the view from a dead price. The desk holds no view on a name that is not trading. That is
 * the conservative direction: a name with no fresh view is planned flat, never larger.
 *
 * <p>There is no money, risk or exposure number in this class and it produces no size — it decides only
 * whether an observation is an observation. Confined to its sensor's single scheduled thread, hence the
 * plain {@link HashMap}.
 */
public final class PrintClock {

    /** instrument → provider timestamp of the last mark this sensor actually consumed. */
    private final Map<String, Long> lastPrintMillis = new HashMap<>();

    /**
     * Whether this mark is a new print for the sensor, recording it as consumed when it is.
     *
     * @param providerTimestamp the mark's provider timestamp — the market's clock, never ingest time.
     *                          A null timestamp is admitted: with no clock to judge by, declining to
     *                          measure must not silence a sensor outright (the same choice ADR-0112
     *                          makes when its estimator has no basis).
     * @return true when the sensor should advance its windows on this mark
     */
    public boolean advanced(String instrumentId, Instant providerTimestamp) {
        if (instrumentId == null) {
            return false;
        }
        if (providerTimestamp == null) {
            return true;
        }
        long at = providerTimestamp.toEpochMilli();
        Long previous = lastPrintMillis.get(instrumentId);
        if (previous != null && at <= previous) {
            return false; // the tape has not printed since we last looked — there is nothing to observe
        }
        lastPrintMillis.put(instrumentId, at);
        return true;
    }

    /** How many instruments have been consumed at least once (diagnostics/tests). */
    public int trackedInstruments() {
        return lastPrintMillis.size();
    }
}

package io.jethro.app.fusion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * When a sensor that is still COLD should replay the durable price history again (ADR-0131).
 *
 * <p><b>The defect this repairs.</b> The ADR-0071 warm restart replays a name's stored recent prices into
 * a sensor <em>the first time the sensor sees that name</em> — and, until this class, only then. First
 * sight is boot, which is the single worst moment to read the store: after a redeploy that followed an
 * outage, after a weekend, or before the open, the durable series ends in exactly the gap that made the
 * seed necessary. {@link SensorWarmup} then correctly declines to walk across the hole, hands over one or
 * two prices against a warm-up of hundreds, and the sensor is abandoned there — the seed never runs
 * again for the life of the process, no matter how much history the store subsequently accumulates. The
 * sensor is left to re-learn from live prints alone, which since ADR-0113 arrive on the tape's clock, and
 * every source it feeds is silent until it does. Observed live on 2026-07-30: every equity's trend and
 * reversion sensor seeded 0–4 prices of the 193/241 it needed at 07:47 ET, and both sources were still
 * publishing nothing for every name an hour and three quarters later — so each name carried a single
 * corroborating source, the ADR-0124 agreement scalar is 0 at one effective source, and every combined
 * forecast in the book was exactly zero with the desk flat and its whole exposure budget unused.
 *
 * <p><b>The rule.</b> A cold sensor re-seeds. On first sight it seeds immediately, exactly as before; if
 * it is still cold afterwards it seeds again every {@code cadence} sightings until it warms, and once
 * warm it is never re-seeded. The store is fed from the same mark stream the sensor consumes, so it is a
 * superset of what the sensor has already absorbed and a later read can only be a longer, more recent
 * series than the boot read was.
 *
 * <p><b>Why re-seeding is safe, stated as the caller's contract.</b> The replay is into a state the caller
 * has just RESET (each sensor's {@code forget}), because replaying on top of live state would count the
 * same prices twice. That reset is why the policy is strictly "while cold": a cold sensor publishes no
 * view and arms no stop, so re-deriving its state cannot move anything the desk is acting on. A warm one
 * is left untouched forever.
 *
 * <p><b>No number is introduced (invariant 7 / ADR-0016).</b> The retry cadence is not a dial — the caller
 * passes the sensor's own {@code warmupSamples()}, so a sensor re-attempts roughly once per warm-up span
 * of its own sightings: often enough that a name recovers within one warm-up of history becoming
 * available, rarely enough that a cold name costs one bounded history read per warm-up span. Nothing here
 * is a price, a size, a risk limit or an exposure.
 *
 * <p>Not thread-safe by design: each sensor lifecycle drives its own instance from its own single
 * scheduled thread, like the {@link PrintClock} and {@code seeded} set it replaces.
 */
public final class SensorReseed {

    /** Sightings between re-attempts for a name that is still cold — the sensor's own warm-up length. */
    private final int cadence;
    /** Sightings since this name's last seed attempt; absent once the name is warm. */
    private final Map<String, Integer> sinceAttempt = new HashMap<>();
    /** Names whose sensor has reported warm — never seeded again. */
    private final Set<String> warm = new HashSet<>();

    /**
     * @param cadence how many sightings of a still-cold name to let pass before re-seeding it. Pass the
     *                sensor's {@code warmupSamples()}: a count of samples, not a configured dial.
     */
    public SensorReseed(int cadence) {
        this.cadence = Math.max(1, cadence);
    }

    /**
     * Whether this sighting should (re-)seed the name from stored history. True on first sight, then
     * every {@code cadence} sightings while the name is still cold, and never once it is warm.
     */
    public boolean due(String instrumentId) {
        if (instrumentId == null || warm.contains(instrumentId)) {
            return false;
        }
        Integer seen = sinceAttempt.get(instrumentId);
        if (seen == null) {
            sinceAttempt.put(instrumentId, 0);
            return true; // first sight — seed immediately, exactly as ADR-0071 always did
        }
        // ABSENCE, not a count of zero, is what marks a name as never seen: resetting the counter to 0
        // after a retry must not make the next sighting look like a first sight all over again.
        int n = seen + 1;
        sinceAttempt.put(instrumentId, n < cadence ? n : 0);
        return n >= cadence;
    }

    /**
     * Records what the seed achieved. A warm sensor is retired from the retry set for good; a cold one
     * keeps its counter and is re-attempted after another {@code cadence} sightings.
     */
    public void record(String instrumentId, boolean isWarm) {
        if (instrumentId == null || !isWarm) {
            return;
        }
        warm.add(instrumentId);
        sinceAttempt.remove(instrumentId);
    }

    /** Names this policy has retired as warm — disclosure for diagnostics, never an input to sizing. */
    public int warmedNames() {
        return warm.size();
    }
}

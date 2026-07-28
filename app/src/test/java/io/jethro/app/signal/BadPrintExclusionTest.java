package io.jethro.app.signal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0109 — a bad print is not evidence. The exclusion reuses the desk's own corporate-action /
 * bad-print thresholds ({@code jethro.trading.mark-jump-bps}), so what is pinned here is the
 * FAIL-OPEN contract around them: a rule that removes evidence from the gate governing exposure must
 * never be able to remove MORE than it was configured to, and a mis-wired or absent configuration must
 * degrade to "count everything", never to "count nothing".
 *
 * <p>The SQL predicate itself is exercised against the live schema (it joins reference data), which this
 * repo has no database harness for; the arithmetic it implements — {@code |return| ≥ bps/1e4} — is the
 * same comparison the mark cache's jump guard already makes per update, one asset class at a time.
 */
class BadPrintExclusionTest {

    @Test
    void nullOrEmptyThresholdsFailOpen() {
        // A single DEFAULT=0 entry: zero disables the exclusion exactly as it disables the jump guard,
        // so every resolved observation still counts. The dangerous inverse — an empty threshold set
        // read as "nothing is a valid return" — would silently delete the desk's whole sample.
        assertThat(SignalTelemetryStore.capArrays(null)).containsExactly("DEFAULT", "0");
        assertThat(SignalTelemetryStore.capArrays(Map.of())).containsExactly("DEFAULT", "0");
    }

    @Test
    void thresholdsBecomeTwoParallelLists() {
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put("EQUITY", 2000);
        caps.put("FUTURE", 1000);
        caps.put("DEFAULT", 2000);
        assertThat(SignalTelemetryStore.capArrays(caps))
                .containsExactly("EQUITY,FUTURE,DEFAULT", "2000,1000,2000");
    }

    @Test
    void unusableEntriesAreDroppedRatherThanReachingTheQuery() {
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put("EQUITY", 2000);
        caps.put("not a class", 500);   // outside the reference-data alphabet
        caps.put("FX", null);            // no threshold configured
        assertThat(SignalTelemetryStore.capArrays(caps)).containsExactly("EQUITY", "2000");
    }

    @Test
    void aNegativeThresholdIsClampedToDisabledNotToExcludeEverything() {
        // -1 bps would otherwise read as "every move is a bad print" and empty the sample.
        assertThat(SignalTelemetryStore.capArrays(Map.of("EQUITY", -1)))
                .containsExactly("EQUITY", "0");
    }

    @Test
    void discardsAreEmptyWhenTheExclusionIsDisabled() {
        var telemetry = new SignalTelemetry(new SignalTelemetryStore(null),
                instrument -> java.util.Optional.empty(), java.util.List.of(3600),
                10.0, 7, 500, 60, Map.of());
        assertThat(telemetry.discards()).isEmpty();
    }
}

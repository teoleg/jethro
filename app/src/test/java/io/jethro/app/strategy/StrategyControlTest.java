package io.jethro.app.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-0052: override-or-config precedence, write validation, and audit provenance. */
class StrategyControlTest {

    private static StrategyProperties props() {
        // threshold 2.5σ, floor 2bp, lookback 24, target 25k, cooldown 60s, BOND order cap 150k,
        // stop 0.008, take 0.016, regime-scale 0.5.
        return new StrategyProperties(true, 5, 24, 2.5, new BigDecimal("2"), new BigDecimal("25000"),
                "ALPHA", Map.of(), true, 60, new BigDecimal("50000"), new BigDecimal("75000"), 15.0,
                Map.of("BOND", new BigDecimal("150000")), true, new BigDecimal("0.008"),
                new BigDecimal("0.016"), true, new BigDecimal("0.5"), new BigDecimal("250"), "mean-reversion",
                0.7, 0.015);
    }

    private static StrategyControl control() {
        return new StrategyControl(props(), StrategyOverrideStore.NONE);
    }

    @Test
    void effectiveIsConfigDefaultUntilOverridden() {
        StrategyControl c = control();
        assertEquals(2.5, c.thresholdSigmas());
        assertEquals(24, c.lookback());
        assertEquals(0, new BigDecimal("2").compareTo(c.minSignalBps()));
        assertEquals(0, c.state().stream().filter(StrategyControl.DialState::overridden).count());
    }

    @Test
    void overrideWinsOverConfig() {
        StrategyControl c = control();
        c.set("threshold-sigmas", "1.5", "oleg", "want to see small moves");
        assertEquals(1.5, c.thresholdSigmas());
        StrategyControl.DialState d = c.state().stream()
                .filter(s -> s.key().equals("threshold-sigmas")).findFirst().orElseThrow();
        assertTrue(d.overridden());
        assertEquals("1.5", d.effective());
        assertEquals("2.5", d.defaultValue()); // the config default is still reported next to it
    }

    @Test
    void resetRevertsToConfig() {
        StrategyControl c = control();
        c.set("min-signal-bps", "0", "oleg", null);
        assertEquals(0, BigDecimal.ZERO.compareTo(c.minSignalBps()));
        c.reset("min-signal-bps", "oleg");
        assertEquals(0, new BigDecimal("2").compareTo(c.minSignalBps()));
        assertFalse(c.state().stream().filter(s -> s.key().equals("min-signal-bps"))
                .findFirst().orElseThrow().overridden());
    }

    @Test
    void outOfBoundsIsRejected() {
        StrategyControl c = control();
        assertThrows(IllegalArgumentException.class, () -> c.set("threshold-sigmas", "50", "x", null));
        assertThrows(IllegalArgumentException.class, () -> c.set("lookback", "1", "x", null));
        assertThrows(IllegalArgumentException.class, () -> c.set("stop-loss-pct", "-0.1", "x", null));
        // a rejected write leaves the effective value untouched
        assertEquals(2.5, c.thresholdSigmas());
    }

    @Test
    void wrongKindIsRejected() {
        StrategyControl c = control();
        assertThrows(IllegalArgumentException.class, () -> c.set("allow-short", "maybe", "x", null));
        assertThrows(IllegalArgumentException.class, () -> c.set("lookback", "12.5", "x", null));
        assertThrows(IllegalArgumentException.class, () -> c.set("nonexistent-dial", "1", "x", null));
    }

    @Test
    void boolDialTogglesBehaviourFlags() {
        StrategyControl c = control();
        assertTrue(c.autoExecute());
        c.set("auto-execute", "false", "oleg", "pause auto-trading");
        assertFalse(c.autoExecute());
    }

    @Test
    void perClassOrderCapStaysConfigOnly_scalarOverrideAppliesElsewhere() {
        StrategyControl c = control();
        c.set("max-order-notional", "60000", "oleg", null);
        // BOND has a config-level per-class cap → unchanged by the scalar override (structural).
        assertEquals(0, new BigDecimal("150000").compareTo(c.maxOrderNotionalFor("BOND")));
        // EQUITY has no per-class cap → the live scalar cap applies.
        assertEquals(0, new BigDecimal("60000").compareTo(c.maxOrderNotionalFor("EQUITY")));
    }

    @Test
    void stopAndTakeDisableWhenSetToZero() {
        StrategyControl c = control();
        assertEquals(0, new BigDecimal("0.008").compareTo(c.stopLossPctOrNull()));
        c.set("stop-loss-pct", "0", "oleg", "disable stop");
        assertNull(c.stopLossPctOrNull());
    }

    @Test
    void auditRecordsProvenance() {
        RecordingStore store = new RecordingStore();
        StrategyControl c = new StrategyControl(props(), store);
        c.set("threshold-sigmas", "1.8", "oleg", "sweep");
        assertEquals(1, store.saves.size());
        Object[] row = store.saves.get(0);
        assertEquals("threshold-sigmas", row[0]);
        assertEquals("1.8", row[1]);
        assertEquals("oleg", row[2]);
        assertEquals("2.5", row[4]); // old effective value captured for the audit trail
    }

    /** Captures save() calls so the test can assert the audit row content. */
    private static final class RecordingStore implements StrategyOverrideStore {
        final List<Object[]> saves = new java.util.ArrayList<>();

        @Override public Map<String, String> load() {
            return Map.of();
        }

        @Override public void save(String param, String value, String actor, String note, String oldValue) {
            saves.add(new Object[]{param, value, actor, note, oldValue});
        }

        @Override public void delete(String param, String actor, String oldValue) {
        }

        @Override public List<Change> recentChanges(int limit) {
            return List.of();
        }
    }
}

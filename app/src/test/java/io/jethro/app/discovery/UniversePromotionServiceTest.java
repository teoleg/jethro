package io.jethro.app.discovery;

import io.jethro.refdata.RefDataRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-0060 Phase 2 write path: a promotion writes a MONITOR_ONLY instrument with PROVISIONAL, flagged
 *  money dials; is idempotent; and eviction removes only the stalest, non-pinned, un-traded discovered name. */
class UniversePromotionServiceTest {

    /** In-memory refdata gateway: records instruments and their attributes, no JDBC. */
    private static final class FakeGateway implements UniverseRefdataGateway {
        final Map<String, Map<String, String>> attrs = new LinkedHashMap<>();
        final Map<String, Map<String, String>> symbology = new LinkedHashMap<>();
        final Set<String> withFills = new LinkedHashSet<>();
        int refreshes = 0;

        @Override public boolean exists(String id) { return attrs.containsKey(id); }
        @Override public void writeMonitored(String id, String assetClass, String currency, BigDecimal mult,
                                             Map<String, String> sym, Map<String, String> a) {
            symbology.put(id, new LinkedHashMap<>(sym));
            attrs.put(id, new LinkedHashMap<>(a));
        }
        @Override public List<String> discoveredInstrumentIds() {
            List<String> out = new ArrayList<>();
            attrs.forEach((id, a) -> {
                if (RefDataRepository.SOURCE_DISCOVERED.equals(a.get(RefDataRepository.ATTR_SOURCE))) {
                    out.add(id);
                }
            });
            return out;
        }
        @Override public boolean hasFills(String id) { return withFills.contains(id); }
        @Override public boolean evict(String id) {
            if (!RefDataRepository.SOURCE_DISCOVERED.equals(
                    attrs.getOrDefault(id, Map.of()).get(RefDataRepository.ATTR_SOURCE))) {
                return false;
            }
            attrs.remove(id);
            symbology.remove(id);
            return true;
        }
        @Override public void refresh() { refreshes++; }
    }

    private static final class RecordingAudit implements PromotionAudit {
        final List<String> actions = new ArrayList<>();
        @Override public void record(long at, String epoch, String mode, String id, String action, String outcome,
                                     Double score, Integer days, String sources, String reason, boolean dryRun) {
            actions.add(action + ":" + id);
        }
    }

    private static UniverseCandidate cand(String id, double score, int sources, int distinctDays, long lastSeen) {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < sources; i++) {
            s.add("src" + i);
        }
        return new UniverseCandidate(id, score, sources, s, 1_000L, lastSeen, distinctDays, "sample");
    }

    private static DynamicUniverseProperties props(int cap) {
        // enabled, minScore, sustained, sources, perDay, maxMonitored, interval, blacklist, pin, write, adv, spread
        return new DynamicUniverseProperties(true, 25, 3, 2, 2, cap, 86_400, List.of(), List.of(), true,
                new BigDecimal("50000000"), new BigDecimal("20"));
    }

    private final CompanyDirectory companies = CompanyDirectory.fromClasspath("discovery/company-tickers.csv");
    private static final UniversePromotionPolicy.Verdict OK =
            new UniversePromotionPolicy.Verdict("X", UniversePromotionPolicy.Outcome.PROMOTE, "clears all gates");

    @Test
    void promotionWritesATradableDiscoveredInstrumentWithFlaggedProvisionalDials() {
        FakeGateway gw = new FakeGateway();
        RecordingAudit audit = new RecordingAudit();
        var svc = new UniversePromotionService(gw, companies, audit, props(50));

        boolean written = svc.promote(cand("PLTR", 40, 3, 5, 9_000L), OK, "epoch", "SIM", 1_000L);

        assertTrue(written);
        Map<String, String> a = gw.attrs.get("PLTR");
        assertEquals(RefDataRepository.SOURCE_DISCOVERED, a.get(RefDataRepository.ATTR_SOURCE),
                "provenance recorded — the evictable set, but a first-class tradable instrument");
        assertTrue(a.get("adv_provenance").startsWith("PROVISIONAL"), "adv is flagged provisional, not silent");
        assertTrue(a.get("spread_provenance").startsWith("PROVISIONAL"));
        assertEquals("50000000", a.get("adv_usd"));
        assertEquals("yahoo", gw.symbology.get("PLTR").keySet().iterator().next(),
                "gets a Yahoo symbol so it can be marked/traded, but no real-time WS symbol");
        assertFalse(gw.symbology.get("PLTR").containsKey("alpaca"), "not added to the real-time WS subscribe set");
        assertEquals(List.of("PROMOTED:PLTR"), audit.actions);
        assertEquals(1, gw.refreshes, "cache refreshed so the new instrument is visible");
    }

    @Test
    void promotionIsIdempotent() {
        FakeGateway gw = new FakeGateway();
        RecordingAudit audit = new RecordingAudit();
        var svc = new UniversePromotionService(gw, companies, audit, props(50));

        assertTrue(svc.promote(cand("PLTR", 40, 3, 5, 9_000L), OK, "e", "SIM", 1L));
        assertFalse(svc.promote(cand("PLTR", 40, 3, 5, 9_000L), OK, "e", "SIM", 2L),
                "re-promoting an existing name is a no-op");
        assertEquals(1, audit.actions.size(), "no second audit row for the idempotent re-promotion");
    }

    @Test
    void evictionRemovesTheStalestNonPinnedUntradedName() {
        FakeGateway gw = new FakeGateway();
        RecordingAudit audit = new RecordingAudit();
        var svc = new UniversePromotionService(gw, companies, audit, props(2)); // cap = 2

        svc.promote(cand("PLTR", 40, 3, 5, 9_000L), OK, "e", "SIM", 1L);
        svc.promote(cand("HOOD", 40, 3, 5, 8_000L), OK, "e", "SIM", 1L);
        svc.promote(cand("COIN", 40, 3, 5, 1_000L), OK, "e", "SIM", 1L); // stalest by lastSeen
        assertEquals(3, gw.discoveredInstrumentIds().size());

        Map<String, Long> lastSeen = new LinkedHashMap<>();
        lastSeen.put("PLTR", 9_000L);
        lastSeen.put("HOOD", 8_000L);
        lastSeen.put("COIN", 1_000L);
        List<String> evicted = svc.enforceCap(lastSeen, Set.of(), "e", "SIM", 2_000L);

        assertEquals(List.of("COIN"), evicted, "the least-recently-seen name is evicted to meet the cap");
        assertEquals(new TreeSet<>(List.of("HOOD", "PLTR")), new TreeSet<>(gw.discoveredInstrumentIds()));
        assertTrue(audit.actions.contains("EVICTED:COIN"));
    }

    @Test
    void evictionNeverRemovesAPinnedOrTradedName() {
        FakeGateway gw = new FakeGateway();
        RecordingAudit audit = new RecordingAudit();
        var svc = new UniversePromotionService(gw, companies, audit, props(1)); // cap = 1, so 2 must go...

        svc.promote(cand("PLTR", 40, 3, 5, 9_000L), OK, "e", "SIM", 1L);
        svc.promote(cand("HOOD", 40, 3, 5, 1_000L), OK, "e", "SIM", 1L); // stalest, but pinned
        svc.promote(cand("COIN", 40, 3, 5, 2_000L), OK, "e", "SIM", 1L); // next stalest, but has traded
        gw.withFills.add("COIN");

        Map<String, Long> lastSeen = Map.of("PLTR", 9_000L, "HOOD", 1_000L, "COIN", 2_000L);
        List<String> evicted = svc.enforceCap(lastSeen, Set.of("HOOD"), "e", "SIM", 3_000L);

        // Only PLTR is admissible for eviction (HOOD pinned, COIN traded); cap can't be fully met but
        // the protected names are never touched.
        assertEquals(List.of("PLTR"), evicted);
        assertTrue(gw.exists("HOOD"), "pinned name survives");
        assertTrue(gw.exists("COIN"), "traded name survives");
    }
}

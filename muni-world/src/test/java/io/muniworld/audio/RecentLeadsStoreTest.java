package io.muniworld.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recent-leads ring keeps the live tail newest-first and honours the read limit — the capture loop's
 * scratch surface for the UI/API (ADR-0014).
 */
class RecentLeadsStoreTest {

    private static TranscriptLeadService.Leads leadsOf(String match) {
        return new TranscriptLeadService.Leads("audio:cnbc",
                List.of(new TranscriptLeadService.Lead(TranscriptLeadService.Kind.KEYWORD, match, "ctx", 0, 0.5)));
    }

    @Test
    void newestFirstAndLimited() {
        RecentLeadsStore store = new RecentLeadsStore();
        store.add("cnbc", leadsOf("downgrade"));
        store.add("cnbc", leadsOf("refunding"));
        store.add("bloomberg", leadsOf("default"));

        List<RecentLeadsStore.Entry> all = store.recent(50);
        assertEquals(3, all.size());
        assertEquals("default", all.get(0).lead().match(), "most recent first");
        assertEquals("bloomberg", all.get(0).feed());

        assertEquals(1, store.recent(1).size(), "read limit honoured");
    }

    @Test
    void emptyByDefault() {
        assertTrue(new RecentLeadsStore().recent(10).isEmpty());
    }
}

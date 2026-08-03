package io.muniworld.audio;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A small in-memory ring of the most recent audio leads (ADR-0014), so the capture loop has somewhere to put
 * what it finds and the UI/API can read it back. DERIVED and ephemeral — leads worth keeping get verified and
 * persisted through the normal pipeline; this is just the live tail. Bounded so a long-running loop can't grow
 * unbounded. Thread-safe: the scheduler writes, HTTP readers read.
 */
@Component
public final class RecentLeadsStore {

    /** A stored lead with when it was captured and which feed it came from. */
    public record Entry(Instant at, String feed, TranscriptLeadService.Lead lead) {
    }

    private static final int MAX = 500;

    private final ConcurrentLinkedDeque<Entry> ring = new ConcurrentLinkedDeque<>();

    public void add(String feed, TranscriptLeadService.Leads leads) {
        Instant now = Instant.now();
        for (TranscriptLeadService.Lead l : leads.leads()) {
            ring.addFirst(new Entry(now, feed, l));
        }
        while (ring.size() > MAX) {
            ring.pollLast();
        }
    }

    /** The most recent leads, newest first (capped at {@code limit}). */
    public List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>(Math.min(limit, ring.size()));
        for (Entry e : ring) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }
}

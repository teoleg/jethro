package io.muniworld.audio;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A small ring of the most recent raw transcripts (ADR-0014), so you can SEE what the capture loop actually
 * heard and eyeball it against the TV before any analysis is designed. This is the validation surface: feed,
 * time, the recognized text, and the ASR confidence. Ephemeral/derived — the leads are the durable output;
 * this is just "what did it hear just now". Thread-safe: the scheduler writes, HTTP readers read.
 */
@Component
public final class RecentTranscriptsStore {

    /** One captured chunk's transcript: when, which feed, the recognized text, and how many segments. */
    public record Entry(Instant at, String feed, String text, int segments, double confidence) {
    }

    private static final int MAX = 50;

    private final ConcurrentLinkedDeque<Entry> ring = new ConcurrentLinkedDeque<>();

    public void add(String feed, Transcript t) {
        ring.addFirst(new Entry(Instant.now(), feed, t.fullText(), t.segments().size(), t.asrConfidence()));
        while (ring.size() > MAX) {
            ring.pollLast();
        }
    }

    /** The most recent transcripts, newest first (capped at {@code limit}). */
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

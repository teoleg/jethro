package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.Hypothesis;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministic idempotency guard for hypothesis triggers (ADR-0022 follow-up). The narrative
 * feed keeps a news item in its window across cycles, so the model re-proposes the SAME call
 * every cycle — reworded just enough to slip the {@code instrument|thesis} ledger key — and,
 * once a per-instrument autonomy cooldown lapses, re-trades it. That is the "reacts on the same
 * news multiple times" bug.
 *
 * <p>The floor is deterministic (invariant 7): a trigger is keyed by the NEWS that drove it —
 * {@code instrument | direction | sorted source-news ids} — so the same news never fires twice;
 * genuinely new news (a new id) is a new key and fires once. When the model gives no sources we
 * fall back to a normalized keyword signature of the thesis, which collapses rewordings of the
 * same story. Entries expire after a window so a recurring theme can legitimately re-trigger
 * later. The model is separately told the live calls (context {@code alreadyProposed}) so it
 * stops proposing them at all — the guard is the backstop, the prompt is the assist.
 */
final class HypothesisIdempotency {

    private static final int CAP = 4_000;
    /** Words too generic to distinguish one story from another — dropped from the signature. */
    private static final java.util.Set<String> STOP = java.util.Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "at", "by",
            "is", "are", "be", "as", "its", "it", "this", "that", "from", "into", "supports",
            "support", "long", "short", "buy", "sell", "trade", "position", "view", "price");

    private final long windowMillis;
    private final Map<String, Long> firedAt = new LinkedHashMap<>(); // key → first-fired millis

    HypothesisIdempotency(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** True when this call's news already fired a hypothesis inside the window (a repeat). */
    synchronized boolean isDuplicate(Hypothesis h, long now) {
        Long fired = firedAt.get(key(h));
        return fired != null && now - fired < windowMillis;
    }

    /** Records this call's trigger as fired (first occurrence wins the timestamp). */
    synchronized void markFired(Hypothesis h, long now) {
        firedAt.putIfAbsent(key(h), now);
        if (firedAt.size() > CAP) {
            var it = firedAt.entrySet().iterator();
            while (firedAt.size() > CAP && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** The trigger key: instrument + direction + the news that drove it (or a thesis signature). */
    static String key(Hypothesis h) {
        String signature = "";
        if (h.sources() != null && !h.sources().isEmpty()) {
            signature = h.sources().stream()
                    .map(s -> s == null ? "" : s.trim())
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(","));
        }
        if (signature.isEmpty()) {
            signature = thesisSignature(h.thesis()); // no sources → collapse reworded theses
        }
        return h.instrumentId() + "|" + h.direction().name() + "|" + signature;
    }

    /** Order-independent significant-keyword bag of a thesis — reworded repeats hash the same. */
    static String thesisSignature(String thesis) {
        if (thesis == null || thesis.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(thesis.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2 && !STOP.contains(w))
                .distinct()
                .sorted()
                .collect(Collectors.joining(" "));
    }
}

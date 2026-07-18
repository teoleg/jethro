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
 * <p>The floor is deterministic (invariant 7), keyed by the NEWS that drove the call:
 * {@code instrument | direction | sorted source-news ids}. That is the reliable mechanism — the
 * model is prompted to cite the narrative ids it used, so the same news never fires twice and
 * genuinely new news (a new id) fires once. When the model omits sources, the fallback is the
 * NORMALIZED thesis text, which only collapses near-identical repeats; recognising a *reworded*
 * same-story call is a semantic judgement, left to the model (told the live calls via context
 * {@code alreadyProposed}, with a prompt rule not to repeat them). Deterministic guard as the
 * backstop, the model as the assist. Entries expire after a window so a recurring theme can
 * legitimately re-trigger later.
 */
final class HypothesisIdempotency {

    private static final int CAP = 4_000;

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

    /** Normalized thesis text (case/punctuation/whitespace-folded) — catches near-identical
     *  repeats only; a genuinely reworded same-story call is the model's job to suppress. */
    static String thesisSignature(String thesis) {
        if (thesis == null || thesis.isBlank()) {
            return "";
        }
        return thesis.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}

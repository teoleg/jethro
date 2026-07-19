package io.jethro.app.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranked register of candidate additions to the tracked universe (ADR-0050 §7): both the news layer
 * (big outlets → propose additions to the base list) and social (enrich / flag an emerging name) feed
 * UNTRACKED instrument mentions here, weighted by source credibility. The register ranks them so the
 * ones "really cooking" float to the top as suggestions — it never adds an instrument or trades.
 *
 * <p>Score is a heuristic (mine, not a risk number): credibility-weighted mention sum plus a bonus per
 * DISTINCT source, so a name two independent outlets carry outranks a single-source burst. Mentions
 * decay is not modelled yet; the window is bounded by an eviction cap. Thread-safe (fed from the news
 * and social scheduler threads, read by the controller).
 */
public final class UniverseCandidates {

    /** Cross-source bonus per distinct source — a heuristic weight, not a money/risk dial. */
    private static final double DISTINCT_SOURCE_BONUS = 5.0;

    private static final class Agg {
        double weightSum;
        int mentions;
        final Set<String> sources = new LinkedHashSet<>();
        long firstSeen;
        long lastSeen;
        String sample;
    }

    private final int maxCandidates;
    private final Map<String, Agg> byInstrument = new LinkedHashMap<>();

    public UniverseCandidates(int maxCandidates) {
        this.maxCandidates = Math.max(16, maxCandidates);
    }

    /**
     * Record one untracked-instrument mention. {@code weight} carries source credibility (a trusted
     * news outlet &gt; a verified social account &gt; an anonymous post) — the caller sets it.
     */
    public synchronized void observe(String instrumentId, String source, double weight, String sample, long now) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return;
        }
        Agg a = byInstrument.computeIfAbsent(instrumentId, k -> {
            Agg n = new Agg();
            n.firstSeen = now;
            return n;
        });
        a.weightSum += Math.max(0, weight);
        a.mentions++;
        a.sources.add(source);
        a.lastSeen = now;
        if (a.sample == null) {
            a.sample = sample;
        }
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        if (byInstrument.size() <= maxCandidates) {
            return;
        }
        String weakest = null;
        double worst = Double.MAX_VALUE;
        for (var e : byInstrument.entrySet()) {
            double s = score(e.getValue());
            if (s < worst) {
                worst = s;
                weakest = e.getKey();
            }
        }
        if (weakest != null) {
            byInstrument.remove(weakest);
        }
    }

    private static double score(Agg a) {
        return a.weightSum + DISTINCT_SOURCE_BONUS * a.sources.size();
    }

    /** Top candidates, highest score first. */
    public synchronized List<UniverseCandidate> ranked(int limit) {
        List<UniverseCandidate> out = new ArrayList<>(byInstrument.size());
        byInstrument.forEach((id, a) -> out.add(new UniverseCandidate(
                id, score(a), a.mentions, Set.copyOf(a.sources), a.firstSeen, a.lastSeen, a.sample)));
        out.sort(Comparator.comparingDouble(UniverseCandidate::score).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}

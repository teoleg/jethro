package io.jethro.app.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Universe-discovery config (jethro.discovery, ADR-0045/0050 §7). Real RSS outlets propose additions
 * to the base tracked list; social enriches. Suggestions only — nothing adds an instrument or trades.
 * The weights/cap are heuristics (mine, tune freely), not risk/money numbers.
 */
@ConfigurationProperties(prefix = "jethro.discovery")
public record DiscoveryProperties(
        boolean enabled,
        long intervalSeconds,
        /** Curated outlet name → RSS feed URL (empty until you add yours — no scraping). */
        Map<String, String> outlets,
        /** Max candidate names retained (lowest-score evicted beyond this). */
        int maxCandidates,
        /** Weight of a news-outlet mention (outlets are more credible than social). */
        double newsWeight,
        /** Weight of a corroborated SOCIAL mention (per credible channel). */
        double socialWeight) {

    public long intervalSecondsOrDefault() {
        return intervalSeconds > 0 ? intervalSeconds : 300; // 5 min — news moves slower than the tape
    }

    public Map<String, String> outletsOrEmpty() {
        return outlets != null ? outlets : Map.of();
    }

    public int maxCandidatesOrDefault() {
        return maxCandidates > 0 ? maxCandidates : 100;
    }

    public double newsWeightOrDefault() {
        return newsWeight > 0 ? newsWeight : 3.0;
    }

    public double socialWeightOrDefault() {
        return socialWeight > 0 ? socialWeight : 1.0;
    }
}

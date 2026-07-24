package io.jethro.app.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Config for the dynamic discovery-driven universe (ADR-0060), prefix {@code jethro.universe.dynamic}.
 * Governs the daily promotion gate that turns discovery candidates into monitored names.
 *
 * <p><b>Money-dial provenance (CLAUDE.md):</b> the gate thresholds and the monitored cap gate admission
 * to the tracked set and, downstream, to trading. Their values are Oleg's, not mine — the defaults here
 * are conservative {@code PLACEHOLDER}s so a mis-set (missing) config fails SAFE (admits nothing) rather
 * than admitting on an invented number. See {@code application.properties} for the provenance comments.
 */
@ConfigurationProperties(prefix = "jethro.universe.dynamic")
public record DynamicUniverseProperties(
        /** Master switch. Default OFF: Phase 1 is a dry-run proposer; nothing runs unless turned on. */
        boolean enabled,
        /** Minimum discovery score to be eligible (credibility-weighted cross-source rank). */
        double minScore,
        /** Sustained: minimum distinct calendar days the name must have been mentioned on. */
        int minSustainedDays,
        /** Corroborated: minimum distinct credible sources naming it. */
        int minSources,
        /** Rate limit: max promotions granted per session-day. */
        int maxPromotionsPerDay,
        /** Hard cap on monitored discovered names (Phase 2 eviction target). */
        int maxMonitored,
        /** How often the controller re-evaluates. Default daily (ADR-0060: once per session-day). */
        long evaluationIntervalSeconds,
        /** Names a human has banned — never admitted regardless of score. */
        List<String> blacklist,
        /** Names a human has pinned — protected from eviction (Phase 2). */
        List<String> pinList) {

    // --- Conservative PLACEHOLDER defaults (Oleg to set). Chosen to admit almost nothing until tuned. ---

    public double minScoreOrDefault() {
        // PLACEHOLDER — Oleg. High bar: a cross-source name (2 outlets → +10 bonus) plus sustained
        // mention weight must clear this before it is even eligible. Arbitrary until Oleg tunes.
        return minScore > 0 ? minScore : 25.0;
    }

    public int minSustainedDaysOrDefault() {
        // PLACEHOLDER — Oleg. Must recur across at least this many distinct days (not a one-day spike).
        return minSustainedDays > 0 ? minSustainedDays : 3;
    }

    public int minSourcesOrDefault() {
        // PLACEHOLDER — Oleg. At least this many independent sources must corroborate.
        return minSources > 0 ? minSources : 2;
    }

    public int maxPromotionsPerDayOrDefault() {
        // PLACEHOLDER — Oleg. Slow, auditable growth: at most this many names admitted per day.
        return maxPromotionsPerDay > 0 ? maxPromotionsPerDay : 2;
    }

    public int maxMonitoredOrDefault() {
        // PLACEHOLDER — Oleg. Upper bound on monitored discovered names (Pi + Yahoo-poll budget).
        return maxMonitored > 0 ? maxMonitored : 50;
    }

    public long evaluationIntervalSecondsOrDefault() {
        return evaluationIntervalSeconds > 0 ? evaluationIntervalSeconds : 86_400; // daily
    }

    public Set<String> blacklistOrEmpty() {
        return blacklist != null ? Set.copyOf(blacklist) : Set.of();
    }

    public Set<String> pinListOrEmpty() {
        return pinList != null ? Set.copyOf(pinList) : Set.of();
    }

    public UniversePromotionPolicy.Thresholds toThresholds() {
        return new UniversePromotionPolicy.Thresholds(
                minScoreOrDefault(), minSustainedDaysOrDefault(), minSourcesOrDefault(), maxPromotionsPerDayOrDefault());
    }
}

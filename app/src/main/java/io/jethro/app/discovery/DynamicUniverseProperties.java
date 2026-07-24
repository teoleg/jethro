package io.jethro.app.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Config for the dynamic discovery-driven universe (ADR-0060), prefix {@code jethro.universe.dynamic}.
 * Governs the daily promotion gate that turns discovery candidates into monitored names.
 *
 * <p>The gate thresholds and the cap gate admission to the tracked universe (promotion = inclusion, not a
 * trade). Values live in {@code application.properties} and are tuned on the running system; the defaults
 * below are the fallback when a key is unset. Promotion does not force a trade — that is gated downstream
 * (OOS backtest, guardrail, breaker).
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
        List<String> pinList,
        /** When false (default), the gate runs but writes NOTHING to refdata — dry-run. Set true to let
         *  promotions actually write the discovered instrument into reference data. */
        boolean write,
        /** PROVISIONAL adv in USD stamped on a promoted name until measured from our own tape. */
        BigDecimal provisionalAdvUsd,
        /** PROVISIONAL bid/ask spread in bps stamped on a promoted name until measured. */
        BigDecimal provisionalSpreadBps) {

    // --- Fallback defaults when a key is unset (tuned in application.properties on the running system). ---

    public double minScoreOrDefault() {
        // Minimum credibility-weighted cross-source score to be eligible.
        return minScore > 0 ? minScore : 25.0;
    }

    public int minSustainedDaysOrDefault() {
        // Distinct calendar days a name must recur on. 1 = promote immediately on the checks (Oleg, 2026-07-24).
        return minSustainedDays > 0 ? minSustainedDays : 1;
    }

    public int minSourcesOrDefault() {
        // At least this many independent sources must corroborate (single-source-pump guard).
        return minSources > 0 ? minSources : 2;
    }

    public int maxPromotionsPerDayOrDefault() {
        // Rate limit: at most this many names admitted per session-day.
        return maxPromotionsPerDay > 0 ? maxPromotionsPerDay : 2;
    }

    public int maxMonitoredOrDefault() {
        // Upper bound on tracked discovered names (box + poll budget); stalest evicted when full.
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

    public boolean writeEnabled() {
        return write;
    }

    public BigDecimal provisionalAdvUsdOrDefault() {
        // Provisional small-cap liquidity-tier ADV stamped on a promoted name (flagged in refdata) until its
        // ADV is measured from its own tape; sizes/costs its orders meanwhile.
        return provisionalAdvUsd != null ? provisionalAdvUsd : new BigDecimal("50000000"); // $50M
    }

    public BigDecimal provisionalSpreadBpsOrDefault() {
        // Provisional wide spread tier stamped on a promoted name (flagged) until measured from its own tape.
        return provisionalSpreadBps != null ? provisionalSpreadBps : new BigDecimal("20"); // 20 bps
    }

    public UniversePromotionPolicy.Thresholds toThresholds() {
        return new UniversePromotionPolicy.Thresholds(
                minScoreOrDefault(), minSustainedDaysOrDefault(), minSourcesOrDefault(), maxPromotionsPerDayOrDefault());
    }
}

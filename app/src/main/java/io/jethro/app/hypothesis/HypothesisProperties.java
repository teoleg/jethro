package io.jethro.app.hypothesis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the LLM hypothesis layer (ADR-0022, jethro.hypothesis). The model proposes
 * structured theses on a cadence; the deterministic quant layer sizes/gates them (sizing
 * params come from {@code jethro.strategy}). Human-in-loop in this slice — no auto-execute.
 */
@ConfigurationProperties(prefix = "jethro.hypothesis")
public record HypothesisProperties(
        boolean enabled,
        long intervalSeconds,
        Integer maxPerCycle,
        Integer maxOutputTokens,
        Long narrativeSeed) {

    public int maxPerCycleOrDefault() {
        return maxPerCycle != null && maxPerCycle > 0 ? maxPerCycle : 3;
    }

    public int maxOutputTokensOrDefault() {
        return maxOutputTokens != null && maxOutputTokens > 0 ? maxOutputTokens : 400;
    }

    public long intervalSecondsOrDefault() {
        return intervalSeconds > 0 ? intervalSeconds : 90;
    }

    public long narrativeSeedOrDefault() {
        return narrativeSeed != null ? narrativeSeed : 42L;
    }
}

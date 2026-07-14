package io.jethro.app.hypothesis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Config for the LLM hypothesis layer (ADR-0022, jethro.hypothesis). The model proposes
 * structured theses on a cadence; the deterministic quant layer sizes/gates them (sizing
 * params come from {@code jethro.strategy}). Human-in-loop by default; {@link Autonomy}
 * opens a tight, deterministic envelope for hands-off simulated execution.
 */
@ConfigurationProperties(prefix = "jethro.hypothesis")
public record HypothesisProperties(
        boolean enabled,
        long intervalSeconds,
        Integer maxPerCycle,
        Integer maxOutputTokens,
        Long narrativeSeed,
        /** Min seconds between real news (Finnhub) refreshes — caps API calls independent of the
         *  hypothesis cadence. Ignored by the sim feed. Default 60. */
        Long narrativeRefreshSeconds,
        Integer backtestTicks,
        /** Out-of-sample seeds for the autonomy backtest gate (ADR-0027): the strategy must be
         *  net-positive on a MAJORITY of these independent paths (seeds disjoint from the live
         *  sim seed). Odd numbers make the median-majority exact. Default 5. */
        Integer oosSeeds,
        /** Book the AI hypothesis sleeve trades — kept separate from the momentum strategy's
         *  books so the two engines don't flatten each other's positions. Default "AI". */
        String book,
        Autonomy autonomy) {

    /**
     * The deterministic risk envelope for bounded autonomy (ADR-0022): a thesis auto-executes
     * (simulated, ADR-0019) only if ALL hold — autonomy on, the backtest supports it, conviction
     * ≥ min, order notional ≤ the (tight) autonomy cap, and the instrument is whitelisted (empty
     * = all). Outside the envelope it stays a human-review card. The model never widens this —
     * it's operator config, evaluated in code. Default OFF; must never front a real broker.
     */
    public record Autonomy(Boolean enabled, String minConviction, BigDecimal maxOrderNotional,
                           Long cooldownSeconds, List<String> whitelist) {

        public boolean enabledOrDefault() {
            return enabled != null && enabled;
        }

        public String minConvictionOrDefault() {
            return minConviction != null ? minConviction : "HIGH";
        }

        public BigDecimal maxOrderNotionalOrDefault() {
            return maxOrderNotional != null ? maxOrderNotional : new BigDecimal("10000");
        }

        public long cooldownSecondsOrDefault() {
            return cooldownSeconds != null ? cooldownSeconds : 300;
        }

        public List<String> whitelistOrEmpty() {
            return whitelist != null ? whitelist : List.of();
        }
    }

    /** Never-null autonomy view (all-default when the block is absent). */
    public Autonomy autonomyOrDefault() {
        return autonomy != null ? autonomy : new Autonomy(null, null, null, null, null);
    }

    public int backtestTicksOrDefault() {
        return backtestTicks != null && backtestTicks > 0 ? backtestTicks : 15_000;
    }

    public int oosSeedsOrDefault() {
        return oosSeeds != null && oosSeeds > 0 ? oosSeeds : 5;
    }

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

    public String bookOrDefault() {
        return book != null && !book.isBlank() ? book : "AI";
    }

    public long narrativeRefreshSecondsOrDefault() {
        return narrativeRefreshSeconds != null && narrativeRefreshSeconds > 0 ? narrativeRefreshSeconds : 60;
    }
}

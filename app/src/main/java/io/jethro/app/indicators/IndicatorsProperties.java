package io.jethro.app.indicators;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config for the top-bar market-indicators strip (jethro.indicators): a curated set of major
 * indices/ETFs (US + international) shown for context only — display, never tradeable positions.
 * Real (delayed) values come from Yahoo (ADR-0023); disabled in CI/tests so nothing hits the net.
 */
@ConfigurationProperties(prefix = "jethro.indicators")
public record IndicatorsProperties(Boolean enabled, Long pollSeconds, Map<String, String> symbols) {

    /** Curated default set (Yahoo symbol → label): US indices, ETFs, and international indices. */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("^GSPC", "S&P 500");
        DEFAULTS.put("^IXIC", "Nasdaq");
        DEFAULTS.put("^DJI", "Dow");
        DEFAULTS.put("^RUT", "Russell 2000");
        DEFAULTS.put("SPY", "SPY");
        DEFAULTS.put("QQQ", "QQQ");
        DEFAULTS.put("EFA", "EAFE (intl)");
        DEFAULTS.put("EEM", "Emerging");
        DEFAULTS.put("^FTSE", "FTSE 100");
        DEFAULTS.put("^GDAXI", "DAX");
        DEFAULTS.put("^N225", "Nikkei 225");
        DEFAULTS.put("^HSI", "Hang Seng");
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public long pollSecondsOrDefault() {
        return pollSeconds != null && pollSeconds > 0 ? pollSeconds : 60;
    }

    /** The configured symbol→label map, or the curated defaults when unset. */
    public Map<String, String> symbolsOrDefault() {
        return symbols != null && !symbols.isEmpty() ? symbols : DEFAULTS;
    }
}

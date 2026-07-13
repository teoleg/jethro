package io.jethro.app.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Assembly-level wiring config for trading-core (ADR-0015: wiring lives in app). */
@ConfigurationProperties(prefix = "jethro.trading")
public record TradingCoreProperties(
        boolean enabled,
        long simSeed,
        List<String> simInstruments,
        BigDecimal simStartPrice,
        /** Optional per-instrument start prices; instruments not listed use simStartPrice. */
        Map<String, BigDecimal> simStartPrices,
        /** Default annualized volatility for the walk (e.g. 0.20 = 20%). */
        Double simAnnualVol,
        /** Optional per-instrument annualized vol overrides (statistical params, not money). */
        Map<String, Double> simAnnualVols,
        /** Correlated market regimes (calm/trend/volatile/shock episodes); default true. */
        Boolean simRegimes,
        /** Factor-based SOFR curve sim publishing USD.SOFR.* tenor marks; default true. */
        Boolean simCurve,
        long simTickIntervalMillis,
        /** Market-data provider: "sim" (default), "yahoo" (ADR-0023), or "finnhub" (ADR-0024,
         *  real-time equities over WebSocket — needs finnhub-token). Dev/demo only. */
        String provider,
        /** Gap between individual Yahoo symbol requests, in ms (spread, not burst — avoids
         *  429s). Default 700; each symbol then refreshes every ~spacing×instrumentCount. */
        Long yahooRequestSpacingMillis,
        /** Finnhub API token (free key) for the real-time WebSocket feed; blank falls back to sim. */
        String finnhubToken,
        String lmdbPath,
        long lmdbMaxSizeMb,
        int bufferCapacity) {

    public String providerOrDefault() {
        return provider != null && !provider.isBlank() ? provider : "sim";
    }

    public long yahooRequestSpacingMillisOrDefault() {
        return yahooRequestSpacingMillis != null && yahooRequestSpacingMillis > 0
                ? yahooRequestSpacingMillis : 700;
    }

    public String finnhubTokenOrEmpty() {
        return finnhubToken != null ? finnhubToken.trim() : "";
    }

    /** Start price for one instrument: its override if present, else the default. */
    public BigDecimal startPriceFor(String instrumentId) {
        BigDecimal override = simStartPrices == null ? null : simStartPrices.get(instrumentId);
        return override != null ? override : simStartPrice;
    }

    public boolean simRegimesOrDefault() {
        return simRegimes == null || simRegimes;
    }

    public boolean simCurveOrDefault() {
        return simCurve == null || simCurve;
    }

    /** Annualized vol for one instrument: override, else default, else 20%. */
    public double annualVolFor(String instrumentId) {
        Double override = simAnnualVols == null ? null : simAnnualVols.get(instrumentId);
        if (override != null) {
            return override;
        }
        return simAnnualVol != null ? simAnnualVol : 0.20;
    }
}

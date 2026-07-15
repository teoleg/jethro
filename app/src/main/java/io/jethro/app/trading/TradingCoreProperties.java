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
        /** Sim engine (ADR-0026): "correlated" (cross-asset factor model, default) or "legacy"
         *  (independent per-instrument walks — kept for A/B and old-tape tests). */
        String simEngine,
        /** Optional path to a sim-calibration.json overriding the checked-in default. */
        String simCalibrationPath,
        /** Time compression: wall seconds per simulated trading day (default 120 — multi-day
         *  regimes play out in minutes). */
        Double simSecondsPerDay,
        long simTickIntervalMillis,
        /** Market-data provider: "sim" (default), "yahoo" (ADR-0023), or "finnhub" (ADR-0024,
         *  real-time equities over WebSocket — needs finnhub-token). Dev/demo only. */
        String provider,
        /** Gap between individual Yahoo symbol requests, in ms (spread, not burst — avoids
         *  429s). Default 700; each symbol then refreshes every ~spacing×instrumentCount. */
        Long yahooRequestSpacingMillis,
        /** Finnhub API token (free key) for the real-time WebSocket feed; blank falls back to sim. */
        String finnhubToken,
        /** Cap on Finnhub REST calls/minute shared across ALL endpoints (news + curve) — the free
         *  tier's account-wide limit is 60; default 55 leaves headroom. WS trades don't count. */
        Integer finnhubMaxCallsPerMinute,
        /** Use a REAL Treasury yield curve (Finnhub) instead of the factor sim, when a token is
         *  set. Default true; a failed startup probe falls back to the sim curve, logged. */
        Boolean realCurve,
        /** Seconds between real-curve refreshes (curves move slowly; keeps REST calls low). Default 120. */
        Long treasuryCurveRefreshSeconds,
        /** Session-calendar zone for LIVE feeds (ADR-0027): the trading day is the calendar date
         *  in this zone. Pure sim runs use the compressed sim calendar instead. Default
         *  America/New_York (the universe is US-centric). */
        String sessionZone,
        /** Hour (0-23, session-zone local) at which the LIVE-feed trading day rolls to the
         *  next one — 17 = the CME 17:00-ET futures settlement boundary. */
        Integer sessionRollHour,
        /** Corporate-action / bad-print guard: max single-update mark move in bps of the
         *  previous mark, per asset class (key = EQUITY/FUTURE/FX/BOND/SWAP, or DEFAULT).
         *  A bigger jump QUARANTINES the instrument until an operator clears it. 0 disables
         *  a class. Defaults: EQUITY/DEFAULT 2000 (20%), FUTURE/SWAP 1000, FX/BOND 800. */
        Map<String, Integer> markJumpBps,
        String lmdbPath,
        long lmdbMaxSizeMb,
        int bufferCapacity) {

    public String providerOrDefault() {
        return provider != null && !provider.isBlank() ? provider : "sim";
    }

    private static final Map<String, Integer> DEFAULT_MARK_JUMP_BPS = Map.of(
            "EQUITY", 2000, "FUTURE", 1000, "FX", 800, "BOND", 800, "SWAP", 1000, "DEFAULT", 2000);

    /** Jump-guard threshold (bps) for an asset class; null class → DEFAULT. Explicit config
     *  overrides per key; unlisted classes use the built-in defaults above. */
    public int markJumpBpsFor(String assetClass) {
        String key = assetClass != null ? assetClass : "DEFAULT";
        if (markJumpBps != null && markJumpBps.containsKey(key)) {
            return markJumpBps.get(key);
        }
        if (markJumpBps != null && assetClass == null && markJumpBps.containsKey("DEFAULT")) {
            return markJumpBps.get("DEFAULT");
        }
        return DEFAULT_MARK_JUMP_BPS.getOrDefault(key, DEFAULT_MARK_JUMP_BPS.get("DEFAULT"));
    }

    public int sessionRollHourOrDefault() {
        return sessionRollHour != null && sessionRollHour >= 0 && sessionRollHour <= 23
                ? sessionRollHour : 17;
    }

    /** Zone for the live-feed session calendar; a bad zone id fails fast at wiring time. */
    public java.time.ZoneId sessionZoneOrDefault() {
        return java.time.ZoneId.of(sessionZone != null && !sessionZone.isBlank()
                ? sessionZone : "America/New_York");
    }

    public long yahooRequestSpacingMillisOrDefault() {
        return yahooRequestSpacingMillis != null && yahooRequestSpacingMillis > 0
                ? yahooRequestSpacingMillis : 700;
    }

    public String finnhubTokenOrEmpty() {
        return finnhubToken != null ? finnhubToken.trim() : "";
    }

    public int finnhubMaxCallsPerMinuteOrDefault() {
        return finnhubMaxCallsPerMinute != null && finnhubMaxCallsPerMinute > 0 ? finnhubMaxCallsPerMinute : 55;
    }

    public boolean realCurveOrDefault() {
        return realCurve == null || realCurve;
    }

    public long treasuryCurveRefreshSecondsOrDefault() {
        return treasuryCurveRefreshSeconds != null && treasuryCurveRefreshSeconds > 0
                ? treasuryCurveRefreshSeconds : 120;
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

    public boolean correlatedSimOrDefault() {
        return simEngine == null || simEngine.isBlank() || "correlated".equalsIgnoreCase(simEngine);
    }

    public String simCalibrationPathOrNull() {
        return simCalibrationPath != null && !simCalibrationPath.isBlank() ? simCalibrationPath : null;
    }

    public double simSecondsPerDayOrDefault() {
        return simSecondsPerDay != null && simSecondsPerDay > 0 ? simSecondsPerDay : 120.0;
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

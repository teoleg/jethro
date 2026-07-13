package io.jethro.app.hypothesis;

import java.util.Map;

/**
 * Short human descriptions of the demo instruments, so the LLM knows WHAT a ticker is and stops
 * inventing an issuer for it (ADR-0022) — a future like {@code ZN} is a 10Y Treasury future, not a
 * stock called "Zapata Resources"; {@code USD_IRS_10Y} is a swap, not the 10Y Treasury. Reference
 * data carries no name field, so these are curated demo labels; anything not listed falls back to a
 * generic phrase from its asset class. Descriptive text only — never a number into sizing/risk
 * (invariant 1), the model just reads it.
 */
public final class InstrumentDescriptions {

    private InstrumentDescriptions() {
    }

    private static final Map<String, String> CURATED = Map.ofEntries(
            Map.entry("AAPL", "Apple Inc. common stock"),
            Map.entry("MSFT", "Microsoft Corp. common stock"),
            Map.entry("AMZN", "Amazon.com Inc. common stock"),
            Map.entry("GOOG", "Alphabet Inc. (Google) common stock"),
            Map.entry("SAP", "SAP SE common stock (Germany, EUR-denominated)"),
            Map.entry("ES", "E-mini S&P 500 equity-index future"),
            Map.entry("NQ", "E-mini Nasdaq-100 equity-index future"),
            Map.entry("ZT", "2-Year US Treasury Note future (rates)"),
            Map.entry("ZF", "5-Year US Treasury Note future (rates)"),
            Map.entry("ZN", "10-Year US Treasury Note future (rates)"),
            Map.entry("ZB", "30-Year US Treasury Bond future (rates)"),
            Map.entry("USD_IRS_5Y", "5-Year USD interest-rate swap (pay-fixed profits when rates rise)"),
            Map.entry("USD_IRS_10Y", "10-Year USD interest-rate swap (pay-fixed profits when rates rise)"),
            Map.entry("EURUSD", "EUR/USD spot FX (US dollars per 1 euro)"),
            Map.entry("GBPUSD", "GBP/USD spot FX (US dollars per 1 pound)"),
            Map.entry("USDJPY", "USD/JPY spot FX (yen per 1 US dollar)"));

    /** A description for the model, or a generic asset-class phrase, or null if nothing is known. */
    public static String of(String instrumentId, String assetClass) {
        String curated = CURATED.get(instrumentId);
        if (curated != null) {
            return curated;
        }
        if (assetClass == null) {
            return null;
        }
        return switch (assetClass) {
            case "EQUITY" -> "equity (company stock)";
            case "FUTURE" -> "exchange-traded future";
            case "FX" -> "spot FX currency pair";
            case "SWAP" -> "interest-rate swap (rates, not a stock)";
            case "BOND" -> "bond / rates instrument";
            default -> null;
        };
    }
}

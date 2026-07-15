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

    // Terse on purpose — every token is prompt the model must eval (slow on a small box).
    private static final Map<String, String> CURATED = Map.ofEntries(
            Map.entry("AAPL", "Apple stock"),
            Map.entry("MSFT", "Microsoft stock"),
            Map.entry("AMZN", "Amazon stock"),
            Map.entry("GOOG", "Alphabet (Google) stock"),
            Map.entry("SAP", "SAP SE stock (EUR)"),
            Map.entry("ES", "S&P 500 future"),
            Map.entry("NQ", "Nasdaq-100 future"),
            Map.entry("ZT", "2Y US Treasury future (rates)"),
            Map.entry("ZF", "5Y US Treasury future (rates)"),
            Map.entry("ZN", "10Y US Treasury future (rates)"),
            Map.entry("ZB", "30Y US Treasury future (rates)"),
            Map.entry("USD_IRS_5Y", "5Y USD rate swap (pay-fixed)"),
            Map.entry("USD_IRS_10Y", "10Y USD rate swap (pay-fixed)"),
            Map.entry("EURUSD", "EUR/USD FX"),
            Map.entry("GBPUSD", "GBP/USD FX"),
            Map.entry("USDJPY", "USD/JPY FX"));

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

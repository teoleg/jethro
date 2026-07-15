package io.jethro.app.hypothesis;

/**
 * Turns an instrument's reference-data {@code display_name} (V27) into the phrase the
 * narration model reads, so it reasons about the real instrument instead of inventing an
 * issuer for the ticker (ADR-0022) — a future like {@code ZN} is a 10Y Treasury future,
 * not a stock called "Zapata Resources". The names come from REFERENCE DATA, never a
 * hardcoded per-name map (review GAP-4); an instrument with no {@code display_name} row
 * falls back to a GENERIC asset-class phrase (a generic phrase, not a specific security).
 * Descriptive text only — never a number into sizing/risk (invariant 1).
 */
public final class InstrumentDescriptions {

    private InstrumentDescriptions() {
    }

    /**
     * @param displayName the instrument's refdata {@code display_name}, or null if it has none.
     * @param assetClass  the instrument's asset class, for the generic fallback.
     * @return the refdata name when present, else a generic asset-class phrase, else null.
     */
    public static String of(String displayName, String assetClass) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
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

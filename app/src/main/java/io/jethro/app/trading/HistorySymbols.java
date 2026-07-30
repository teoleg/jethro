package io.jethro.app.trading;

import java.util.Locale;
import java.util.Map;

/**
 * Internal instrument id → history-provider (Tiingo/Stooq) proxy symbol, shared by the hedge-covariance
 * seed ({@link HistorySeeder}) and the ADR-0053 training-bars loader.
 *
 * <p><b>DERIVED from the refdata master, never a hardcoded per-name list (invariant 9).</b> The universe
 * is dynamic — seeded by migrations and grown by ADR-0060 discovery promotion — so a component carrying
 * its own fixed ticker list silently misses every newly promoted or seeded name (which is exactly what
 * happened: the sim-instruments legacy list and the old static map both excluded the discovery names and
 * the ADR-0125 sector expansion). Instead the proxy is derived from a name's {@code assetClass}, with a
 * tiny {@link #OVERRIDES} map for the only cases a derivation genuinely cannot know: an ETF proxy for an
 * index future (SPY→ES, QQQ→NQ — free continuous-future history doesn't exist) and the one ticker that
 * differs at the provider (GOOG→GOOGL). Everything else follows from the asset class. Names with no free
 * proxy (Treasury futures, swaps) return null and are simply skipped. Return-based analytics (covariance,
 * features) are invariant to the proxy's level, so an ETF/ticker proxy is sound.
 */
public final class HistorySymbols {

    private HistorySymbols() {
    }

    /** The only provider-specific choices a derivation cannot infer from the asset class. */
    private static final Map<String, String> OVERRIDES = Map.of(
            "ES", "SPY",     // index future → liquid ETF proxy
            "NQ", "QQQ",
            "GOOG", "GOOGL"  // our GOOG is Alphabet class C; the provider keys the class A ticker
    );

    /**
     * History-provider proxy symbol for an instrument, derived from the refdata master's {@code id} +
     * {@code assetClass}. Equities → the plain ticker; FX → the lowercase pair; anything without a free
     * proxy → {@code null} (skip). A newly promoted/seeded name is picked up automatically — no edit here.
     */
    public static String proxyFor(String instrumentId, String assetClass) {
        if (instrumentId == null) {
            return null;
        }
        String override = OVERRIDES.get(instrumentId);
        if (override != null) {
            return override;
        }
        if ("EQUITY".equals(assetClass)) {
            return instrumentId;                          // plain ticker (AAPL, XOM, JNJ, …)
        }
        if ("FX".equals(assetClass)) {
            return instrumentId.toLowerCase(Locale.ROOT); // the Tiingo FX endpoint keys the lowercase pair
        }
        return null;                                      // FUTURE(non-proxied)/BOND/SWAP: no free proxy
    }
}

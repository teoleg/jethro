package io.jethro.app.trading;

import java.util.Map;

/**
 * Internal instrument id → history-provider symbol, shared by the hedge-covariance seed
 * ({@link HistorySeeder}) and the ADR-0053 training-bars loader. Equities/ETFs are the plain ticker
 * (an ETF proxy for the index futures — SPY→ES, QQQ→NQ); FX pairs are the lowercase pair the Tiingo
 * FX endpoint keys on. Instruments without a free proxy (Treasury futures, swaps) are absent and
 * simply skipped. Return-based analytics (covariance, features) are invariant to the proxy's level.
 */
public final class HistorySymbols {

    private HistorySymbols() {
    }

    public static final Map<String, String> PROXY = Map.ofEntries(
            Map.entry("AAPL", "AAPL"), Map.entry("MSFT", "MSFT"), Map.entry("AMZN", "AMZN"),
            Map.entry("GOOG", "GOOGL"), Map.entry("SAP", "SAP"), Map.entry("JNJ", "JNJ"),
            Map.entry("NVDA", "NVDA"), Map.entry("JPM", "JPM"),
            Map.entry("ES", "SPY"), Map.entry("NQ", "QQQ"),
            Map.entry("EURUSD", "eurusd"), Map.entry("GBPUSD", "gbpusd"),
            Map.entry("AUDUSD", "audusd"), Map.entry("USDJPY", "usdjpy"));
}

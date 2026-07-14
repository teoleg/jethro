package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live US Treasury PAR-yield curve assembled from streamed {@code USD.TSY.<tenor>} marks —
 * the DISTINCT government curve alongside the SOFR swap curve ({@link CurveService}); the
 * difference between the two is the swap spread, a real market quantity the desk watches.
 * Display/analytics data (double), never ledger money (invariant 1 untouched). Swaps keep
 * pricing off the SOFR curve; Treasury futures key off this one at the source.
 */
public final class TreasuryCurveView {

    /** Tenor grid: quote id → tenor in years. */
    private static final Map<String, Double> TENORS = Map.of(
            "USD.TSY.1Y", 1.0, "USD.TSY.2Y", 2.0, "USD.TSY.5Y", 5.0,
            "USD.TSY.10Y", 10.0, "USD.TSY.30Y", 30.0);

    public record TsyPoint(String quoteId, double tenorYears, double parYield) {
    }

    private final Map<String, Double> latestYields = new ConcurrentHashMap<>();

    /** True if the id is a Treasury curve quote this view consumes. */
    public static boolean isTsyQuote(String instrumentId) {
        return instrumentId != null && instrumentId.startsWith("USD.TSY.");
    }

    /** Accepts a par-yield quote in percent (e.g. 4.35 = 4.35%). Unknown ids ignored. */
    public void onRate(String quoteId, BigDecimal yieldPercent) {
        if (TENORS.containsKey(quoteId)) {
            latestYields.put(quoteId, yieldPercent.doubleValue() / 100.0);
        }
    }

    /** Current tenor points sorted by tenor; empty until every tenor has quoted. */
    public List<TsyPoint> snapshot() {
        if (latestYields.size() < TENORS.size()) {
            return List.of();
        }
        List<TsyPoint> points = new ArrayList<>();
        TENORS.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(e -> points.add(new TsyPoint(e.getKey(), e.getValue(), latestYields.get(e.getKey()))));
        return points;
    }
}

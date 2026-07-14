package io.jethro.app.risk;

import io.jethro.trading.riskpnl.BondFutureDurations;
import io.jethro.trading.riskpnl.CurveService;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.trading.riskpnl.SwapPricingService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bucketed (key-rate) DV01 per book — curve sensitivities as first-class risk state
 * (quant-engine step 4). One number per (book × curve node): the P&amp;L per +1bp move of
 * THAT node only, so a 2s10s steepener shows its offsetting legs where a single total
 * DV01 nets to nearly nothing.
 *
 * <ul>
 *   <li><b>Swaps</b> (the trade-dated book, V23): each trade's Strata parameter
 *       sensitivity, partitioned per curve node
 *       ({@link SwapPricingService#bucketedDv01Seasoned}) — buckets sum to the trade's
 *       total DV01 exactly. Pay-fixed positive (gains when rates rise).</li>
 *   <li><b>Treasury futures</b>: DV01 = netExposure × (−D) × 10⁻⁴ (the ScenarioEngine's
 *       convention — long futures lose when rates rise), allocated onto the two curve
 *       nodes bracketing the contract's CTD maturity by linear key-rate weights
 *       ({@link BondFutureDurations#keyTenorYears}). The two-node split keeps the bucket
 *       row summing to the position's exact DV01.</li>
 *   <li>Everything else carries no curve risk here; a rates position with no duration on
 *       file is SKIPPED AND COUNTED, never guessed (error-path rule).</li>
 * </ul>
 *
 * Analytics stay double inside Strata; every reported figure is an exact decimal at this
 * boundary (invariant 1).
 */
public final class Dv01Service {

    /** Read side of {@code swap_trades} (V23) — a port so the service tests without JDBC. */
    public interface SwapTradeSource {
        record Trade(String book, String instrument, String side, BigDecimal lots,
                     BigDecimal entryPar, LocalDate tradeDay) {
        }

        List<Trade> trades();

        SwapTradeSource NONE = List::of;
    }

    /** One book's bucket row; {@code buckets} is keyed by tenor label in grid order. */
    public record BookDv01(String book, Map<String, BigDecimal> buckets, BigDecimal total) {
    }

    public record Dv01View(List<String> tenors, List<BookDv01> books, BookDv01 firm,
                           String valuationDay, boolean curveLive, int skipped) {
    }

    /** Tenor in years per tradeable swap (V7/V9 defined products, as in SwapBookService). */
    private static final Map<String, Integer> SWAP_TENOR_YEARS =
            Map.of("USD_IRS_5Y", 5, "USD_IRS_10Y", 10);
    private static final double NOTIONAL_PER_LOT = 1_000_000.0;
    private static final int SCALE = io.jethro.domain.Decimals.PNL_SCALE;

    private final RiskProjection projection;
    private final SwapPricingService pricer;
    private final BondFutureDurations durations;
    private final SwapTradeSource swapTrades;
    private final Supplier<LocalDate> sessionDay;

    public Dv01Service(RiskProjection projection, SwapPricingService pricer,
                       BondFutureDurations durations, SwapTradeSource swapTrades,
                       Supplier<LocalDate> sessionDay) {
        this.projection = projection;
        this.pricer = pricer;
        this.durations = durations;
        this.swapTrades = swapTrades;
        this.sessionDay = sessionDay;
    }

    public Dv01View view(long nowMillis) {
        double[] grid = CurveService.nodeTenorYears();
        List<String> labels = new ArrayList<>(grid.length);
        for (double t : grid) {
            labels.add(label(t));
        }
        Map<String, BigDecimal[]> byBook = new LinkedHashMap<>();
        boolean curveLive = true;
        int skipped = 0;
        LocalDate valuation = sessionDay.get();

        // Swap legs: per-trade Strata bucketed sensitivity (empty ⇒ curve not live yet).
        for (SwapTradeSource.Trade trade : swapTrades.trades()) {
            Integer tenor = SWAP_TENOR_YEARS.get(trade.instrument());
            if (tenor == null) {
                skipped++; // unknown product — never guess a schedule
                continue;
            }
            var bucketed = pricer.bucketedDv01Seasoned(trade.tradeDay(), tenor,
                    "BUY".equals(trade.side()),                       // V9: BUY = pay fixed
                    trade.entryPar().movePointLeft(2).doubleValue(),  // par % → fraction
                    trade.lots().doubleValue() * NOTIONAL_PER_LOT, valuation);
            if (bucketed.isEmpty()) {
                curveLive = false;
                break; // no curve — swap legs unvalued this cycle (disclosed, not zeroed)
            }
            BigDecimal[] row = byBook.computeIfAbsent(trade.book(), k -> zeros(grid.length));
            List<SwapPricingService.TenorDv01> nodes = bucketed.get();
            for (int i = 0; i < grid.length && i < nodes.size(); i++) {
                row[i] = row[i].add(nodes.get(i).dv01());
            }
        }

        // Treasury futures: −D × netExposure × 1bp, key-rate split across bracketing nodes.
        for (PositionRisk p : projection.snapshot(nowMillis).positions()) {
            var keyTenor = durations.keyTenorYears(p.instrumentId());
            if (keyTenor.isEmpty() || p.quantity().signum() == 0) {
                continue; // not a bond future (or flat) — no curve risk booked here
            }
            if (!p.hasMark()) {
                skipped++;
                continue;
            }
            var duration = durations.modifiedDuration(p.instrumentId());
            if (duration.isEmpty()) {
                skipped++; // rates position with no duration on file — honest skip
                continue;
            }
            BigDecimal dv01 = p.netExposure().multiply(duration.get())
                    .movePointLeft(4).negate()
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal[] row = byBook.computeIfAbsent(p.bookId(), k -> zeros(grid.length));
            allocate(row, grid, keyTenor.get(), dv01);
        }

        List<BookDv01> books = new ArrayList<>();
        BigDecimal[] firm = zeros(grid.length);
        byBook.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    books.add(toRow(e.getKey(), labels, e.getValue()));
                    for (int i = 0; i < firm.length; i++) {
                        firm[i] = firm[i].add(e.getValue()[i]);
                    }
                });
        return new Dv01View(labels, books, toRow("FIRM", labels, firm),
                valuation.toString(), curveLive, skipped);
    }

    /** Linear key-rate allocation: all to one node at/outside the grid ends, else split
     *  across the two bracketing nodes with weights that sum to 1 exactly. */
    private static void allocate(BigDecimal[] row, double[] grid, double tenor, BigDecimal dv01) {
        if (tenor <= grid[0]) {
            row[0] = row[0].add(dv01);
            return;
        }
        if (tenor >= grid[grid.length - 1]) {
            row[grid.length - 1] = row[grid.length - 1].add(dv01);
            return;
        }
        int hi = 1;
        while (grid[hi] < tenor) {
            hi++;
        }
        int lo = hi - 1;
        BigDecimal wHi = BigDecimal.valueOf((tenor - grid[lo]) / (grid[hi] - grid[lo]))
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal upper = dv01.multiply(wHi).setScale(SCALE, RoundingMode.HALF_UP);
        row[hi] = row[hi].add(upper);
        row[lo] = row[lo].add(dv01.subtract(upper)); // remainder, so the split sums exactly
    }

    private static BookDv01 toRow(String book, List<String> labels, BigDecimal[] values) {
        Map<String, BigDecimal> buckets = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < labels.size(); i++) {
            buckets.put(labels.get(i), values[i]);
            total = total.add(values[i]);
        }
        return new BookDv01(book, buckets, total);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] out = new BigDecimal[n];
        java.util.Arrays.fill(out, BigDecimal.ZERO.setScale(SCALE));
        return out;
    }

    private static String label(double tenorYears) {
        return tenorYears == Math.rint(tenorYears) ? (int) tenorYears + "Y" : tenorYears + "Y";
    }
}

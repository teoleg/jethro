package io.jethro.app.risk;

import io.jethro.trading.riskpnl.SwapPricingService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The trade-dated swap book (ADR-0020 follow-up): each recorded swap trade priced as a
 * SEASONED swap on the live curve — fixed leg at its OWN entry par, remaining schedule from
 * its OWN trade day — so PV carries roll-down and per-trade DV01 shrinks as trades age
 * (the avg-cost ledger's fresh-tenor approximation cannot see either). No compression:
 * a SELL after a BUY is an offsetting receive-fixed trade; net PV/DV01 sum exactly. This
 * is the precise rates-desk VIEW; the fills-projected ledger stays the P&L source of truth
 * (invariant 3), with the difference between the two being exactly the approximation error
 * the ledger's documented convention accepts.
 */
public final class SwapBookService {

    /** Tenor in years per tradeable swap (the V7/V9 defined products). */
    private static final Map<String, Integer> TENOR_YEARS = Map.of("USD_IRS_5Y", 5, "USD_IRS_10Y", 10);
    private static final double NOTIONAL_PER_LOT = 1_000_000.0;

    /** One dated trade with its live seasoned economics. */
    public record TradeView(String fillId, String instrument, String book, String side,
                            BigDecimal lots, BigDecimal entryPar, LocalDate tradeDay,
                            LocalDate maturity, BigDecimal presentValue, BigDecimal dv01,
                            boolean matured) {
    }

    public record BookView(List<TradeView> trades, BigDecimal totalPv, BigDecimal totalDv01,
                           String valuationDay, String note) {
    }

    private final JdbcTemplate jdbc;
    private final SwapPricingService pricer;
    private final Supplier<LocalDate> sessionDay;

    public SwapBookService(JdbcTemplate jdbc, SwapPricingService pricer, Supplier<LocalDate> sessionDay) {
        this.jdbc = jdbc;
        this.pricer = pricer;
        this.sessionDay = sessionDay;
    }

    public BookView value() {
        LocalDate valuation = sessionDay.get();
        List<TradeView> out = new ArrayList<>();
        BigDecimal totalPv = BigDecimal.ZERO;
        BigDecimal totalDv01 = BigDecimal.ZERO;
        boolean curveLive = true;
        var rows = jdbc.query("""
                select fill_id, instrument, book, side, lots, entry_par, trade_day
                from swap_trades order by trade_day, fill_id
                """, (rs, i) -> new Object[]{
                rs.getString("fill_id"), rs.getString("instrument"), rs.getString("book"),
                rs.getString("side"), rs.getBigDecimal("lots"), rs.getBigDecimal("entry_par"),
                rs.getObject("trade_day", LocalDate.class)});
        for (Object[] r : rows) {
            String instrument = (String) r[1];
            Integer tenor = TENOR_YEARS.get(instrument);
            if (tenor == null) {
                continue; // unknown product — never guess a schedule
            }
            BigDecimal lots = (BigDecimal) r[4];
            BigDecimal entryPar = (BigDecimal) r[5];
            LocalDate tradeDay = (LocalDate) r[6];
            LocalDate maturity = tradeDay.plusYears(tenor);
            boolean payFixed = "BUY".equals(r[3]); // V9: BUY = pay fixed
            var valued = pricer.valueSeasoned(tradeDay, tenor, payFixed,
                    entryPar.movePointLeft(2).doubleValue(),           // par % → fraction
                    lots.doubleValue() * NOTIONAL_PER_LOT, valuation);
            if (valued.isEmpty()) {
                curveLive = false;
                out.add(new TradeView((String) r[0], instrument, (String) r[2], (String) r[3],
                        lots, entryPar, tradeDay, maturity, null, null, false));
                continue;
            }
            boolean matured = !maturity.isAfter(valuation);
            var v = valued.get();
            out.add(new TradeView((String) r[0], instrument, (String) r[2], (String) r[3],
                    lots, entryPar, tradeDay, maturity, v.presentValue(), v.dv01(), matured));
            totalPv = totalPv.add(v.presentValue());
            totalDv01 = totalDv01.add(v.dv01());
        }
        return new BookView(out, totalPv, totalDv01, valuation.toString(),
                curveLive ? null : "curve not live yet — trades listed unvalued");
    }
}

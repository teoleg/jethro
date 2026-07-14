package io.jethro.app.indicators;

import io.jethro.app.trading.TradingCoreLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sim-mode market indicators (top strip): derived from the SIM tape itself — the market
 * regime, key futures/FX marks and Treasury yields, with day-over-day change measured
 * against the previous SESSION close from {@code daily_close} (the ADR-0027 calendar), so
 * the strip moves exactly like the simulated world and works fully offline. Display only —
 * never positions, P&L or the risk ledger.
 */
public final class SimIndicatorsSource implements IndicatorsSource {

    /** The strip, in display order: sim instrument id → label. */
    private static final Map<String, String> TILES = new LinkedHashMap<>();

    static {
        TILES.put("ES", "S&P fut");
        TILES.put("NQ", "Nasdaq fut");
        TILES.put("USD.TSY.2Y", "2Y TSY %");
        TILES.put("USD.TSY.10Y", "10Y TSY %");
        TILES.put("ZN", "ZN 10Y fut");
        TILES.put("EURUSD", "EURUSD");
        TILES.put("USDJPY", "USDJPY");
    }

    private static final long PREV_CLOSE_REFRESH_MILLIS = 30_000;

    private final TradingCoreLifecycle tradingCore; // nullable when trading is off
    private final JdbcTemplate jdbc;                // nullable → no day-over-day change
    private final AtomicReference<Map.Entry<Long, Map<String, BigDecimal>>> prevCloses = new AtomicReference<>();

    public SimIndicatorsSource(TradingCoreLifecycle tradingCore, JdbcTemplate jdbc) {
        this.tradingCore = tradingCore;
        this.jdbc = jdbc;
    }

    @Override
    public List<IndicatorsService.Indicator> latest() {
        var runtime = tradingCore != null ? tradingCore.runtime() : null;
        if (runtime == null) {
            return List.of();
        }
        List<IndicatorsService.Indicator> out = new ArrayList<>();
        // Regime first — the one thing a sim desk glances at before any level.
        String regime = tradingCore.regime();
        if (regime != null) {
            out.add(new IndicatorsService.Indicator("SIM.REGIME", "Regime", regime, null));
        }
        Map<String, BigDecimal> prev = previousSessionCloses();
        TILES.forEach((id, label) -> {
            var holder = runtime.markCache().get(id);
            if (holder == null || holder.priceScaled() <= 0) {
                out.add(new IndicatorsService.Indicator(id, label, null, null));
                return;
            }
            BigDecimal price = io.jethro.domain.Decimals.fromScaledLong(
                    holder.priceScaled(), io.jethro.domain.Decimals.PRICE_SCALE);
            Double changePct = null;
            BigDecimal prevClose = prev.get(id);
            if (prevClose != null && prevClose.signum() > 0) {
                changePct = price.subtract(prevClose)
                        .divide(prevClose, 6, RoundingMode.HALF_EVEN)
                        .movePointRight(2).doubleValue();
            }
            out.add(new IndicatorsService.Indicator(id, label,
                    price.setScale(id.startsWith("USD.TSY.") ? 3 : 2, RoundingMode.HALF_UP).toPlainString(),
                    changePct));
        });
        return out;
    }

    /** Previous SESSION's closes (latest day strictly before the current session day). */
    private Map<String, BigDecimal> previousSessionCloses() {
        if (jdbc == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        var cached = prevCloses.get();
        if (cached != null && now - cached.getKey() <= PREV_CLOSE_REFRESH_MILLIS) {
            return cached.getValue();
        }
        Map<String, BigDecimal> fresh = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    select instrument, close from daily_close
                    where day = (select max(day) from daily_close
                                 where day < (select max(day) from daily_close))
                    """, rs -> {
                fresh.put(rs.getString("instrument"), rs.getBigDecimal("close"));
            });
        } catch (Exception e) {
            // No history yet (first session) or DB hiccup — change chips just stay off.
        }
        prevCloses.set(Map.entry(now, fresh));
        return fresh;
    }
}

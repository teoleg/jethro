package io.jethro.app.risk;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.CovMath;
import io.jethro.trading.riskpnl.FxConversion;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.trading.riskpnl.SwapPricingService;
import io.jethro.trading.riskpnl.VarMath;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Historical-simulation VaR over the recorded daily closes (ADR-0027): TODAY's positions ×
 * the last {@value #WINDOW_DAYS} days' observed instrument returns ({@link VarMath}).
 * Price-quoted positions (equity/future/FX/bond) enter at their USD net exposure × price
 * return — for Treasury futures the observed price return IS the realized revaluation, so
 * convexity is priced implicitly.
 *
 * <p><b>Swap legs (rates VaR):</b> a swap position enters as a synthetic sensitivity leg —
 * exposure = the trade-dated book's total SEASONED DV01 for that instrument (USD per +1bp,
 * Strata-priced with each trade's remaining schedule), return series = the day-over-day
 * par-rate change in bp — so pnl_d = DV01 × Δbp_d, in dollars. CONVENTION, stated:
 * delta-only (daily Δy is single-digit bp — the convexity term is negligible at this
 * horizon; the ±100bp stress panel carries it), and the covered/skipped exposure
 * disclosure counts the |DV01| of swap legs, not gross notional — DV01 is the honest
 * "dollars at risk per bp", where notional would overstate a swap's risk ~1000×. Without
 * a live curve (or persistence) swaps fall back to {@code skippedExposure} at notional —
 * unmeasured risk is disclosed, never dropped. The same synthetic legs feed the EWMA
 * covariance, so parametric VaR and correlation-to-portfolio price rates risk too.
 * History accrues one row per calendar day the platform runs.
 */
public final class VarService {

    /** 250 trading days ≈ 1 year — the Basel/FRTB convention (ADR-0041; was 60). Usable early
     *  because the real-history seed fills daily_close; accrual alone would take a year. */
    static final int WINDOW_DAYS = 250;
    /** Calendar-day fetch reach for the window: 250 trading days span ~365 calendar + holiday margin. */
    private static final int FETCH_CALENDAR_DAYS = 380;
    static final int MIN_OBSERVATIONS = 20;
    /** Synthetic-leg key prefix: exposure is DV01 (USD/bp), "return" is Δbp. */
    static final String DV01_PREFIX = "dv01:";
    private static final java.util.Set<String> PRICE_QUOTED =
            java.util.Set.of("EQUITY", "FUTURE", "FX", "BOND");

    private final JdbcTemplate jdbc;
    private final RiskProjection projection;
    private final InstrumentRefSource refs;
    private final SwapPricingService pricer; // nullable → swaps stay unmeasured (disclosed)
    private final Dv01Service.SwapTradeSource swapTrades;
    private final SwapTenorSource tenors; // swap tenor from refdata (GAP-4)
    private final Supplier<LocalDate> sessionDay;

    private final java.util.concurrent.atomic.AtomicReference<Map.Entry<Long, Optional<CovMath.Covariance>>>
            covCache = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<Map.Entry<Long, Map<String, BigDecimal>>>
            dv01Cache = new java.util.concurrent.atomic.AtomicReference<>();

    /** Without swap pricing: swap positions surface in skippedExposure (pre-existing shape). */
    public VarService(JdbcTemplate jdbc, RiskProjection projection) {
        this(jdbc, projection, id -> Optional.empty(), null,
                Dv01Service.SwapTradeSource.NONE, SwapTenorSource.NONE, LocalDate::now);
    }

    public VarService(JdbcTemplate jdbc, RiskProjection projection, InstrumentRefSource refs,
                      SwapPricingService pricer, Dv01Service.SwapTradeSource swapTrades,
                      SwapTenorSource tenors, Supplier<LocalDate> sessionDay) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.refs = refs;
        this.pricer = pricer;
        this.swapTrades = swapTrades;
        this.tenors = tenors != null ? tenors : SwapTenorSource.NONE;
        this.sessionDay = sessionDay;
    }

    /** EWMA covariance over the same daily history (30s memo — off any hot path). */
    private Optional<CovMath.Covariance> covariance() {
        long now = System.currentTimeMillis();
        var cached = covCache.get();
        if (cached != null && now - cached.getKey() <= 30_000) {
            return cached.getValue();
        }
        Optional<CovMath.Covariance> fresh = CovMath.ewmaCovariance(dayVectors(), MIN_OBSERVATIONS);
        covCache.set(Map.entry(now, fresh));
        return fresh;
    }

    /** Parametric (variance–covariance) VaR beside the historical one — assumption disclosed. */
    public Optional<CovMath.ParametricVar> parametric() {
        return covariance().map(cov -> CovMath.parametricVar(measurableExposures(), cov));
    }

    /**
     * The instrument's correlation with the CURRENT portfolio's returns (ρ_ip) — the input
     * for marginal-risk sizing. Empty during warm-up / uncovered instruments — callers fall
     * back to standalone vol-targeting, disclosed.
     */
    public Optional<java.math.BigDecimal> correlationToPortfolio(String instrumentId) {
        return covariance().flatMap(cov ->
                cov.correlationToPortfolio(instrumentId, measurableExposures())
                        .map(java.math.BigDecimal::valueOf));
    }

    /** The current EWMA covariance (30s cached), for the hedge advisor (ADR-0038). Empty during
     *  warm-up (fewer than {@code MIN_OBSERVATIONS} covered days). */
    public Optional<CovMath.Covariance> covarianceSnapshot() {
        return covariance();
    }

    /** USD exposures per instrument (price-quoted names at net exposure; swap legs as DV01 under
     *  {@link #DV01_PREFIX}) — the hedge advisor filters these by asset class. */
    public Map<String, BigDecimal> exposuresUsd() {
        return measurableExposures();
    }

    /** USD exposures of the measurable positions (incl. swap DV01 legs) — shared input. */
    private Map<String, BigDecimal> measurableExposures() {
        ConsolidatedRisk snapshot = projection.snapshot(System.currentTimeMillis());
        FxConversion fx = projection.fx();
        Map<String, BigDecimal> exposures = new LinkedHashMap<>();
        Map<String, BigDecimal> dv01 = seasonedDv01ByInstrument();
        for (PositionRisk p : snapshot.positions()) {
            if (p.quantity().signum() == 0 || !p.hasMark()) {
                continue;
            }
            if (PRICE_QUOTED.contains(p.assetClass()) && fx.canConvert(p.currency(), "USD")) {
                exposures.merge(p.instrumentId(), fx.convert(p.netExposure(), p.currency(), "USD"),
                        BigDecimal::add);
            } else if ("SWAP".equals(p.assetClass()) && dv01.containsKey(p.instrumentId())) {
                exposures.putIfAbsent(DV01_PREFIX + p.instrumentId(), dv01.get(p.instrumentId()));
            }
        }
        return exposures;
    }

    public VarMath.VarResult compute() {
        ConsolidatedRisk snapshot = projection.snapshot(System.currentTimeMillis());
        FxConversion fx = projection.fx();
        Map<String, BigDecimal> exposures = new LinkedHashMap<>();
        Map<String, BigDecimal> dv01 = seasonedDv01ByInstrument();
        for (PositionRisk p : snapshot.positions()) {
            if (p.quantity().signum() == 0 || !p.hasMark()) {
                continue;
            }
            if (PRICE_QUOTED.contains(p.assetClass()) && fx.canConvert(p.currency(), "USD")) {
                // A book may be long and short the same name across books — sum per instrument.
                exposures.merge(p.instrumentId(), fx.convert(p.netExposure(), p.currency(), "USD"),
                        BigDecimal::add);
            } else if ("SWAP".equals(p.assetClass()) && dv01.containsKey(p.instrumentId())) {
                // The whole instrument's seasoned book DV01, once (positions span books).
                exposures.putIfAbsent(DV01_PREFIX + p.instrumentId(), dv01.get(p.instrumentId()));
            } else {
                // No pricing route → enters WITHOUT history and lands in skippedExposure.
                exposures.merge("unmeasured:" + p.instrumentId(), p.netExposure(), BigDecimal::add);
            }
        }
        return VarMath.historicalVar(exposures, dayVectors(), MIN_OBSERVATIONS);
    }

    /** Total seasoned DV01 per swap instrument from the trade-dated book (30s memo);
     *  empty map when the curve/persistence isn't available — swaps then stay unmeasured. */
    private Map<String, BigDecimal> seasonedDv01ByInstrument() {
        if (pricer == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        var cached = dv01Cache.get();
        if (cached != null && now - cached.getKey() <= 30_000) {
            return cached.getValue();
        }
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        LocalDate valuation = sessionDay.get();
        for (var t : swapTrades.trades()) {
            Integer tenor = tenors.tenorYears(t.instrument()).orElse(null);
            if (tenor == null) {
                continue; // unknown product — its position falls back to unmeasured
            }
            var valued = pricer.valueSeasoned(t.tradeDay(), tenor,
                    "BUY".equals(t.side()),                       // V9: BUY = pay fixed
                    t.entryPar().movePointLeft(2).doubleValue(),  // par % → fraction
                    t.lots().doubleValue() * Dv01Service.NOTIONAL_PER_LOT, valuation);
            if (valued.isEmpty()) {
                totals.clear(); // curve not live — no swap leg is measurable this cycle
                break;
            }
            totals.merge(t.instrument(), valued.get().dv01(), BigDecimal::add);
        }
        dv01Cache.set(Map.entry(now, totals));
        return totals;
    }

    /** Consecutive-day return vectors from the recorded closes, oldest first. Swap par marks
     *  additionally emit their synthetic {@code dv01:} series: Δbp = (close − prev) × 100
     *  (marks are par rates in percent; 0.01 of a percentage point = 1bp). */
    private List<VarMath.DayVector> dayVectors() {
        // day → instrument → close, ordered by day. Restricted to the streams this session may measure
        // and tagged with the stream that produced each close, so a handover between the seed and the
        // session — or between two feed modes — never becomes a return (ADR-0073 / invariant 8).
        List<String> modes = DailyCloseSeries.admissibleModes();
        TreeMap<LocalDate, Map<String, DailyCloseSeries.Close>> closes = new TreeMap<>();
        jdbc.query("""
                select day, instrument, close, feed_mode from daily_close
                where feed_mode in (?, ?)
                  and day >= (select coalesce(max(day), current_date) from daily_close
                              where feed_mode in (?, ?)) - ?::int
                order by day
                """, rs -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            String instrument = rs.getString("instrument");
            var byInstrument = closes.computeIfAbsent(day, d -> new LinkedHashMap<>());
            var candidate = new DailyCloseSeries.Close(day, rs.getString("feed_mode"),
                    rs.getBigDecimal("close").doubleValue());
            var held = byInstrument.get(instrument);
            if (held == null || DailyCloseSeries.preferOver(held.feedMode(), candidate.feedMode())) {
                byInstrument.put(instrument, candidate);
            }
            // trading-day window needs a calendar-day reach + holiday margin
        }, modes.get(0), modes.get(1), modes.get(0), modes.get(1), FETCH_CALENDAR_DAYS);

        List<LocalDate> days = new ArrayList<>(closes.keySet());
        List<VarMath.DayVector> vectors = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            Map<String, DailyCloseSeries.Close> prev = closes.get(days.get(i - 1));
            Map<String, DailyCloseSeries.Close> curr = closes.get(days.get(i));
            Map<String, Double> returns = new LinkedHashMap<>();
            curr.forEach((id, close) -> {
                DailyCloseSeries.Close previous = prev.get(id);
                if (previous != null && previous.close() > 0
                        && DailyCloseSeries.sameStream(previous.feedMode(), close.feedMode())) {
                    double p = previous.close();
                    returns.put(id, close.close() / p - 1.0);
                    if (isSwap(id)) {
                        returns.put(DV01_PREFIX + id, (close.close() - p) * 100.0);
                    }
                }
            });
            if (!returns.isEmpty()) {
                vectors.add(new VarMath.DayVector(days.get(i), returns));
            }
        }
        // Keep only the newest WINDOW_DAYS vectors.
        return vectors.size() <= WINDOW_DAYS ? vectors
                : vectors.subList(vectors.size() - WINDOW_DAYS, vectors.size());
    }

    private boolean isSwap(String instrumentId) {
        return refs.find(instrumentId)
                .map(r -> "SWAP".equals(r.assetClass()))
                .orElse(false);
    }
}

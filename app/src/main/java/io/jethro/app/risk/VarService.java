package io.jethro.app.risk;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.CovMath;
import io.jethro.trading.riskpnl.FxConversion;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.trading.riskpnl.VarMath;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Historical-simulation VaR over the recorded daily closes (ADR-0027): TODAY's positions ×
 * the last {@value #WINDOW_DAYS} days' observed instrument returns ({@link VarMath}).
 * Price-quoted positions (equity/future/FX/bond) enter at their USD net exposure; swaps and
 * currency-unconvertible positions are passed WITHOUT return history so they surface in
 * {@code skippedExposure} — unmeasured risk is disclosed, never dropped (rates VaR via curve
 * history is the follow-up). History accrues one row per calendar day the platform runs.
 */
public final class VarService {

    static final int WINDOW_DAYS = 60;
    static final int MIN_OBSERVATIONS = 20;
    private static final java.util.Set<String> PRICE_QUOTED =
            java.util.Set.of("EQUITY", "FUTURE", "FX", "BOND");

    private final JdbcTemplate jdbc;
    private final RiskProjection projection;

    private final java.util.concurrent.atomic.AtomicReference<Map.Entry<Long, java.util.Optional<CovMath.Covariance>>>
            covCache = new java.util.concurrent.atomic.AtomicReference<>();

    public VarService(JdbcTemplate jdbc, RiskProjection projection) {
        this.jdbc = jdbc;
        this.projection = projection;
    }

    /** EWMA covariance over the same daily history (30s memo — off any hot path). */
    private java.util.Optional<CovMath.Covariance> covariance() {
        long now = System.currentTimeMillis();
        var cached = covCache.get();
        if (cached != null && now - cached.getKey() <= 30_000) {
            return cached.getValue();
        }
        java.util.Optional<CovMath.Covariance> fresh = CovMath.ewmaCovariance(dayVectors(), MIN_OBSERVATIONS);
        covCache.set(Map.entry(now, fresh));
        return fresh;
    }

    /** Parametric (variance–covariance) VaR beside the historical one — assumption disclosed. */
    public java.util.Optional<CovMath.ParametricVar> parametric() {
        return covariance().map(cov -> CovMath.parametricVar(measurableExposures(), cov));
    }

    /**
     * The instrument's correlation with the CURRENT portfolio's returns (ρ_ip) — the input
     * for marginal-risk sizing. Empty during warm-up / uncovered instruments — callers fall
     * back to standalone vol-targeting, disclosed.
     */
    public java.util.Optional<java.math.BigDecimal> correlationToPortfolio(String instrumentId) {
        return covariance().flatMap(cov ->
                cov.correlationToPortfolio(instrumentId, measurableExposures())
                        .map(java.math.BigDecimal::valueOf));
    }

    /** USD exposures of the measurable (price-quoted, convertible) positions — shared input. */
    private Map<String, BigDecimal> measurableExposures() {
        ConsolidatedRisk snapshot = projection.snapshot(System.currentTimeMillis());
        FxConversion fx = projection.fx();
        Map<String, BigDecimal> exposures = new LinkedHashMap<>();
        for (PositionRisk p : snapshot.positions()) {
            if (p.quantity().signum() == 0 || !p.hasMark()) {
                continue;
            }
            if (PRICE_QUOTED.contains(p.assetClass()) && fx.canConvert(p.currency(), "USD")) {
                exposures.merge(p.instrumentId(), fx.convert(p.netExposure(), p.currency(), "USD"),
                        BigDecimal::add);
            }
        }
        return exposures;
    }

    public VarMath.VarResult compute() {
        ConsolidatedRisk snapshot = projection.snapshot(System.currentTimeMillis());
        FxConversion fx = projection.fx();
        Map<String, BigDecimal> exposures = new LinkedHashMap<>();
        for (PositionRisk p : snapshot.positions()) {
            if (p.quantity().signum() == 0 || !p.hasMark()) {
                continue;
            }
            boolean measurable = PRICE_QUOTED.contains(p.assetClass()) && fx.canConvert(p.currency(), "USD");
            BigDecimal usd = measurable
                    ? fx.convert(p.netExposure(), p.currency(), "USD")
                    : p.netExposure(); // enters WITHOUT history → lands in skippedExposure
            // A book may be long and short the same name across books — sum per instrument.
            exposures.merge(measurable ? p.instrumentId() : "unmeasured:" + p.instrumentId(),
                    usd, BigDecimal::add);
        }
        return VarMath.historicalVar(exposures, dayVectors(), MIN_OBSERVATIONS);
    }

    /** Consecutive-day return vectors from the recorded closes, oldest first. */
    private List<VarMath.DayVector> dayVectors() {
        // day → instrument → close, ordered by day.
        TreeMap<LocalDate, Map<String, Double>> closes = new TreeMap<>();
        jdbc.query("""
                select day, instrument, close from daily_close
                where day >= (select coalesce(max(day), current_date) from daily_close) - ?::int
                order by day
                """, rs -> {
            closes.computeIfAbsent(rs.getObject("day", LocalDate.class), d -> new LinkedHashMap<>())
                    .put(rs.getString("instrument"), rs.getBigDecimal("close").doubleValue());
        }, WINDOW_DAYS + 30); // fetch margin over the window for holiday gaps

        List<LocalDate> days = new ArrayList<>(closes.keySet());
        List<VarMath.DayVector> vectors = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            Map<String, Double> prev = closes.get(days.get(i - 1));
            Map<String, Double> curr = closes.get(days.get(i));
            Map<String, Double> returns = new LinkedHashMap<>();
            curr.forEach((id, close) -> {
                Double p = prev.get(id);
                if (p != null && p > 0) {
                    returns.put(id, close / p - 1.0);
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
}

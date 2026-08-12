package io.muniworld.curve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for the benchmark curve and the volatility estimates (ADR-0017, schema V5).
 *
 * <p>Best-effort like every muni-world repository: the jar must boot and serve with no Postgres, so a
 * {@link DataAccessException} is caught and reported, never thrown at a caller. Nothing here is on a
 * request-latency path.
 */
@Repository
public class CurveRepository {   // non-final: @Repository beans are CGLIB-proxied for exception translation

    private static final Logger log = LoggerFactory.getLogger(CurveRepository.class);

    private final JdbcTemplate jdbc;

    public CurveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Store one day's curve fit plus its materialised tenor points. Returns false when the DB is down. */
    public boolean upsertFit(String source, LocalDate asOf, BigDecimal[] betas, BigDecimal tau1,
                             BigDecimal tau2, Map<BigDecimal, BigDecimal> points) {
        try {
            jdbc.update("""
                    INSERT INTO muni.curve_fit (source, as_of, beta0, beta1, beta2, beta3, tau1, tau2)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source, as_of) DO UPDATE SET
                      beta0 = EXCLUDED.beta0, beta1 = EXCLUDED.beta1,
                      beta2 = EXCLUDED.beta2, beta3 = EXCLUDED.beta3,
                      tau1  = EXCLUDED.tau1,  tau2  = EXCLUDED.tau2""",
                    source, asOf, betas[0], betas[1], betas[2], betas[3], tau1, tau2);
            for (Map.Entry<BigDecimal, BigDecimal> e : points.entrySet()) {
                jdbc.update("""
                        INSERT INTO muni.curve_point (source, as_of, tenor_years, zero_rate)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (source, as_of, tenor_years) DO UPDATE SET
                          zero_rate = EXCLUDED.zero_rate""",
                        source, asOf, e.getKey(), e.getValue());
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("curve upsert failed for {} {}: {}", source, asOf, e.getMostSpecificCause().toString());
            return false;
        }
    }

    /** Batch form — one transaction-per-statement is fine here, but the JDBC round trips are not. */
    public int upsertFits(String source, List<GswCsvParser.Row> rows, List<BigDecimal> tenors) {
        int written = 0;
        for (GswCsvParser.Row r : rows) {
            NelsonSiegelSvensson curve = r.curve();
            Map<BigDecimal, BigDecimal> pts = new LinkedHashMap<>();
            for (BigDecimal t : tenors) {
                pts.put(t, curve.zeroRate(t.doubleValue()));
            }
            if (upsertFit(source, r.date(),
                    new BigDecimal[] {bd(r.beta0()), bd(r.beta1()), bd(r.beta2()), bd(r.beta3())},
                    bd(r.tau1()), bd(r.tau2()), pts)) {
                written++;
            }
        }
        return written;
    }

    /**
     * The stored history of one tenor, oldest first, as basis points — the input the vol estimator wants.
     * Empty when the DB is down or nothing is ingested yet.
     */
    public List<Double> rateHistoryBp(String source, BigDecimal tenorYears) {
        try {
            return jdbc.query("""
                    SELECT zero_rate FROM muni.curve_point
                    WHERE source = ? AND tenor_years = ?
                    ORDER BY as_of""",
                    (rs, i) -> rs.getBigDecimal(1).movePointRight(4).doubleValue(),   // rate → bp
                    source, tenorYears);
        } catch (DataAccessException e) {
            log.warn("rateHistoryBp failed: {}", e.getMostSpecificCause().toString());
            return List.of();
        }
    }

    /** The most recent as-of date stored for a source, or empty when there is none. */
    public Optional<LocalDate> latestAsOf(String source) {
        try {
            List<LocalDate> d = jdbc.query("SELECT max(as_of) FROM muni.curve_fit WHERE source = ?",
                    (rs, i) -> rs.getObject(1, LocalDate.class), source);
            return d.isEmpty() || d.get(0) == null ? Optional.empty() : Optional.of(d.get(0));
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * The stored fit for a source on a date ({@code asOf} null = the most recent), as a plain map so the
     * curve can be rebuilt and evaluated at any maturity. Null when nothing is stored — the caller reports
     * that as unavailable rather than substituting a default curve (ADR-0011).
     */
    public Map<String, Object> fitFor(String source, String asOf) {
        try {
            String sql = """
                    SELECT as_of, beta0, beta1, beta2, beta3, tau1, tau2
                    FROM muni.curve_fit
                    WHERE source = ?
                    """ + (asOf == null ? "ORDER BY as_of DESC LIMIT 1" : "AND as_of = ?");
            Object[] args = asOf == null ? new Object[] {source}
                    : new Object[] {source, LocalDate.parse(asOf)};
            List<Map<String, Object>> got = jdbc.query(sql, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("asOf", String.valueOf(rs.getObject("as_of", LocalDate.class)));
                for (String c : new String[] {"beta0", "beta1", "beta2", "beta3", "tau1", "tau2"}) {
                    m.put(c, rs.getBigDecimal(c));
                }
                return m;
            }, args);
            return got.isEmpty() ? null : got.get(0);
        } catch (DataAccessException | java.time.format.DateTimeParseException e) {
            log.warn("fitFor({}, {}) failed: {}", source, asOf, e.toString());
            return null;
        }
    }

    /** How many curve days are stored for a source. */
    public int fitCount(String source) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*)::int FROM muni.curve_fit WHERE source = ?", Integer.class, source);
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    /** Store a vol estimate with its band. Band fields may be null — an unmeasurable band stays absent. */
    public boolean upsertVol(String series, String kind, LocalDate asOf, int windowDays,
                             RealizedVol.Estimate est, RealizedVol.Band band) {
        try {
            jdbc.update("""
                    INSERT INTO muni.rate_vol
                      (series, kind, as_of, window_days, sigma, observations, excluded,
                       p10, p50, p90, windows)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (series, kind, as_of, window_days) DO UPDATE SET
                      sigma = EXCLUDED.sigma, observations = EXCLUDED.observations,
                      excluded = EXCLUDED.excluded, p10 = EXCLUDED.p10, p50 = EXCLUDED.p50,
                      p90 = EXCLUDED.p90, windows = EXCLUDED.windows""",
                    series, kind, asOf, windowDays, est.sigma(), est.observations(), est.excluded(),
                    band == null ? null : band.p10(), band == null ? null : band.p50(),
                    band == null ? null : band.p90(), band == null ? null : band.windows());
            return true;
        } catch (DataAccessException e) {
            log.warn("vol upsert failed for {} {}: {}", series, kind, e.getMostSpecificCause().toString());
            return false;
        }
    }

    /** The latest vol estimate per kind, for the readiness panel. Empty when none / DB down. */
    public List<Map<String, Object>> latestVols() {
        try {
            return new ArrayList<>(jdbc.query("""
                    SELECT DISTINCT ON (series, kind, window_days)
                           series, kind, as_of, window_days, sigma, observations, excluded,
                           p10, p50, p90, windows
                    FROM muni.rate_vol
                    ORDER BY series, kind, window_days, as_of DESC""",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("series", rs.getString("series"));
                        m.put("kind", rs.getString("kind"));
                        m.put("asOf", String.valueOf(rs.getObject("as_of", LocalDate.class)));
                        m.put("windowDays", rs.getInt("window_days"));
                        m.put("sigma", rs.getBigDecimal("sigma"));
                        m.put("observations", rs.getInt("observations"));
                        m.put("excluded", rs.getInt("excluded"));
                        m.put("p10", rs.getBigDecimal("p10"));
                        m.put("p50", rs.getBigDecimal("p50"));
                        m.put("p90", rs.getBigDecimal("p90"));
                        m.put("windows", rs.getObject("windows"));
                        return m;
                    }));
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    private static BigDecimal bd(double d) {
        return BigDecimal.valueOf(d).setScale(8, java.math.RoundingMode.HALF_UP);
    }
}

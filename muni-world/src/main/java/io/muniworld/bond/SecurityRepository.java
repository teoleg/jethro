package io.muniworld.bond;

import io.muniworld.domain.Bond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;

/**
 * The SYSTEM-OF-RECORD store for bond terms — the {@code muni.security} table (ADR-0006), written when an
 * Official Statement is loaded. The LMDB index (ADR-0013) is the DERIVED read layer over this; here is where
 * "results loaded in DB" actually land.
 *
 * <p><b>Postgres-optional by design.</b> muni-world must boot and serve its UI with no live Postgres
 * (Flyway may be off, or the DB down) — hard requirement of the offline jar. So every call here is
 * best-effort: a {@link DataAccessException} (DB down, or the table absent because Flyway is off) is caught,
 * logged once, and swallowed, so the LMDB indexing path always still runs. {@link #count()} returns an empty
 * {@link OptionalLong} when the DB isn't reachable, which the status endpoint reports honestly as "off".
 */
@Repository
public class SecurityRepository {   // non-final: @Repository beans are CGLIB-proxied for exception translation

    private static final Logger log = LoggerFactory.getLogger(SecurityRepository.class);

    /**
     * Idempotent upsert (hard invariant 6): re-loading the same OS overwrites the row, never duplicates it.
     *
     * <p><b>COALESCE on every field, and that is the whole point.</b> Two sources write this table and they
     * know different things: the N-PORT fund feed carries cusip/issuer/coupon/maturity and NOTHING else,
     * while an Official Statement carries the call schedule, tax status and provenance. With a plain
     * {@code = EXCLUDED.x} the daily fund pass re-wrote all ~5,000 rows with null call_date, tax_status and
     * source_id — silently DESTROYING every call schedule extracted from a document, which is exactly the
     * data the whole OS pipeline exists to obtain. It also erased source_id, so issuers already done
     * reappeared on the coverage plan and the readiness counts moved on their own.
     *
     * <p>The rule now: a source that does not carry a field cannot erase it. A source that DOES carry one
     * still overwrites (a corrected OS supplies a non-null value and wins), so this is not a write-once
     * table — it is a merge that never trades knowledge for ignorance.
     */
    private static final String UPSERT = """
            INSERT INTO muni.security
              (cusip, issuer, coupon, maturity_date, dated_date, price, tax_status,
               call_date, call_price, rating, geo_fips, source_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (cusip) DO UPDATE SET
              issuer        = COALESCE(EXCLUDED.issuer,        muni.security.issuer),
              coupon        = COALESCE(EXCLUDED.coupon,        muni.security.coupon),
              maturity_date = COALESCE(EXCLUDED.maturity_date, muni.security.maturity_date),
              dated_date    = COALESCE(EXCLUDED.dated_date,    muni.security.dated_date),
              price         = COALESCE(EXCLUDED.price,         muni.security.price),
              tax_status    = COALESCE(EXCLUDED.tax_status,    muni.security.tax_status),
              call_date     = COALESCE(EXCLUDED.call_date,     muni.security.call_date),
              call_price    = COALESCE(EXCLUDED.call_price,    muni.security.call_price),
              rating        = COALESCE(EXCLUDED.rating,        muni.security.rating),
              geo_fips      = COALESCE(EXCLUDED.geo_fips,      muni.security.geo_fips),
              source_id     = COALESCE(EXCLUDED.source_id,     muni.security.source_id),
              updated_at    = now()
            """;

    /** The upsert statement — package-private so the no-erasure rule is TESTED, not just intended. */
    static String upsertSql() {
        return UPSERT;
    }

    private static final RowMapper<Bond> ROW = (rs, i) -> new Bond(
            rs.getString("cusip"),
            rs.getString("issuer"),
            rs.getBigDecimal("coupon"),
            rs.getObject("maturity_date", LocalDate.class),
            rs.getObject("dated_date", LocalDate.class),
            rs.getBigDecimal("price"),
            rs.getString("tax_status"),
            rs.getObject("call_date", LocalDate.class),
            rs.getBigDecimal("call_price"),
            rs.getString("rating"),
            rs.getString("geo_fips"));

    private static final RowMapper<Row> DETAILED = (rs, i) -> new Row(
            ROW.mapRow(rs, i),
            new Detail(
                    rs.getString("coupon_kind"),
                    rs.getObject("in_default", Boolean.class),
                    rs.getObject("intr_arrears", Boolean.class),
                    rs.getObject("held_funds", Integer.class),
                    rs.getBigDecimal("held_par"),
                    rs.getBigDecimal("val_per100"),
                    rs.getObject("val_as_of", LocalDate.class)));

    // When the DB is down, every JDBC call blocks on Hikari's connection-timeout. Loading N bonds must not pay
    // that N times, nor must the 5s status poll pay it each tick. So health is CACHED: probe at most once per
    // RECHECK_MS, and skip the DB entirely (instantly) while it's known-down. Starts pessimistic (unhealthy)
    // so nothing blocks until the first probe. Flipping Flyway on / the DB coming up is picked up within 15s.
    private static final long RECHECK_MS = 15_000;

    private final JdbcTemplate jdbc;
    private volatile boolean warned;
    private volatile boolean healthy;
    private volatile long lastProbe;

    public SecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when Postgres is reachable AND the security table exists (Flyway has run) — cached, see above. */
    public boolean available() {
        long now = System.currentTimeMillis();
        if (now - lastProbe > RECHECK_MS) {
            lastProbe = now;
            healthy = probe();
        }
        return healthy;
    }

    /**
     * Handle a failed query. A {@link org.springframework.jdbc.BadSqlGrammarException} means the SQL is
     * WRONG, not that the database is gone — flipping the health flag for it made one coding mistake
     * masquerade as an outage and silently degrade every other read for the next probe window. Log it
     * loudly and leave health alone; anything else is treated as a connectivity problem as before.
     */
    private void queryFailed(String what, DataAccessException e) {
        if (e instanceof org.springframework.jdbc.BadSqlGrammarException) {
            log.error("BUG: malformed SQL in {} — this is a code defect, not a DB outage: {}", what,
                    e.getMessage());
            return;
        }
        healthy = false;
        warnOnce(e);
    }

    private boolean probe() {
        if (jdbc == null) {
            return false;   // no JdbcTemplate wired (e.g. a unit test) → DB path is a no-op, index still runs
        }
        try {
            jdbc.queryForObject("SELECT count(*) FROM muni.security", Long.class);
            return true;
        } catch (DataAccessException e) {
            warnOnce(e);
            return false;
        }
    }

    /** Fund-attested filing detail beside a bond's terms (V3 columns) — see {@code Detail}. */
    public record Detail(String couponKind, Boolean inDefault, Boolean intArrears,
                         Integer heldFunds, java.math.BigDecimal heldPar,
                         java.math.BigDecimal valPer100, LocalDate valAsOf) {
    }

    /** A bond with its filing detail, as one read. Detail fields are null where nothing was filed. */
    public record Row(Bond bond, Detail detail) {
    }

    /**
     * Write the ADR-0016 filing detail for a CUSIP (the bond row must already exist — this is the second
     * half of an ingest write, never a row creator). Best-effort like every DB call here.
     */
    public boolean updateDetail(String cusip, Detail d) {
        if (!available()) {
            return false;
        }
        try {
            jdbc.update("""
                    UPDATE muni.security SET coupon_kind = ?, in_default = ?, intr_arrears = ?,
                      held_funds = ?, held_par = ?, val_per100 = ?, val_as_of = ?, updated_at = now()
                    WHERE cusip = ?""",
                    d.couponKind(), d.inDefault(), d.intArrears(), d.heldFunds(), d.heldPar(),
                    d.valPer100(), d.valAsOf(), cusip);
            return true;
        } catch (DataAccessException e) {
            healthy = false;
            warnOnce(e);
            return false;
        }
    }

    /** One dated valuation point for a CUSIP, already par-weighted across the funds that filed it. */
    public record ValuationPoint(LocalDate asOf, java.math.BigDecimal valPer100,
                                 java.math.BigDecimal heldPar, int funds) {
    }

    /** Batch-write one fund's filing valuations: rows of {cusip, asOf, cik, par, valUsd}. Idempotent. */
    public int upsertValuations(List<Object[]> rows) {
        if (!available() || rows.isEmpty()) {
            return 0;
        }
        try {
            jdbc.batchUpdate("""
                    INSERT INTO muni.valuation_history (cusip, as_of, cik, par, val_usd)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (cusip, as_of, cik) DO UPDATE SET par = EXCLUDED.par,
                      val_usd = EXCLUDED.val_usd""", rows);
            return rows.size();
        } catch (DataAccessException e) {
            healthy = false;
            warnOnce(e);
            return 0;
        }
    }

    /**
     * A CUSIP's valuation series, par-weighted across funds per period — exact NUMERIC arithmetic in SQL
     * (invariant 1), oldest first. Periods where no fund reported par are omitted rather than invented.
     */
    public List<ValuationPoint> valuationSeries(String cusip) {
        if (!available()) {
            return List.of();
        }
        try {
            return jdbc.query("""
                    SELECT as_of, ROUND(SUM(val_usd) * 100 / SUM(par), 6) AS val_per100,
                           SUM(par) AS held_par, COUNT(DISTINCT cik) AS funds
                    FROM muni.valuation_history
                    WHERE cusip = ? AND par IS NOT NULL AND par > 0 AND val_usd IS NOT NULL
                    GROUP BY as_of ORDER BY as_of""",
                    (rs, i) -> new ValuationPoint(rs.getObject("as_of", LocalDate.class),
                            rs.getBigDecimal("val_per100"), rs.getBigDecimal("held_par"),
                            rs.getInt("funds")),
                    cusip);
        } catch (DataAccessException e) {
            healthy = false;
            return List.of();
        }
    }

    /**
     * One issuer's document-coverage line: how many of its bonds are held, how much par, and how many
     * already carry a call date (i.e. an Official Statement has been read for them).
     */
    public record CoverageRow(String cusip6, String issuer, int bonds, java.math.BigDecimal heldPar,
                              int withCall, int fromOs, String sampleCusip) {
    }

    /**
     * The universe grouped by ISSUER (CUSIP-6), biggest first — the answer to "how many documents do I
     * actually need?". Every bond from one issuer shares its CUSIP-6, and one Official Statement covers a
     * whole series, so this ranks where a single download buys the most coverage.
     */
    public List<CoverageRow> coverage(int limit, boolean includeDone) {
        if (!available()) {
            return List.of();
        }
        try {
            return jdbc.query(coverageSql(includeDone),
                    (rs, i) -> new CoverageRow(rs.getString("cusip6"), rs.getString("issuer"),
                            rs.getInt("bonds"), rs.getBigDecimal("held_par"), rs.getInt("with_call"),
                            rs.getInt("from_os"), rs.getString("sample_cusip")),
                    limit);
        } catch (DataAccessException e) {
            queryFailed("coverage()", e);
            return List.of();
        }
    }

    /**
     * The coverage statement. Package-private so its SHAPE can be tested without a database — this query
     * shipped as "...count(source_id) = 0ORDER BY count(*) DESC" because two Java text blocks were glued
     * together with no separator. It threw on every call, returned an empty list, and left the owner
     * looking at an empty plan while every bond sat untouched in the table. Concatenated SQL carries its
     * own newlines now, and the test asserts they are there.
     *
     * <p>{@code HAVING count(source_id) = 0} keeps only issuers whose Official Statement has NOT been
     * read, which is what makes the plan a to-do list rather than a ledger.
     */
    static String coverageSql(boolean includeDone) {
        return """
                SELECT substring(cusip, 1, 6) AS cusip6,
                       max(issuer)            AS issuer,
                       count(*)::int          AS bonds,
                       sum(held_par)          AS held_par,
                       count(call_date)::int  AS with_call,
                       count(source_id)::int  AS from_os,
                       min(cusip)             AS sample_cusip
                FROM muni.security
                GROUP BY substring(cusip, 1, 6)
                """
                + (includeDone ? "" : "HAVING count(source_id) = 0\n")
                + "ORDER BY count(*) DESC\nLIMIT ?";
    }


    /** Universe-wide totals: bonds, distinct issuers, and how many bonds already have a call date. */
    public java.util.Optional<int[]> coverageTotals() {
        if (!available()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(jdbc.queryForObject("""
                    SELECT count(*)::int,
                           count(DISTINCT substring(cusip, 1, 6))::int,
                           count(call_date)::int,
                           count(source_id)::int,
                           (count(DISTINCT substring(cusip, 1, 6))
                             FILTER (WHERE source_id IS NOT NULL))::int
                    FROM muni.security""",
                    (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3),
                                          rs.getInt(4), rs.getInt(5)}));
        } catch (DataAccessException e) {
            queryFailed("coverageTotals()", e);
            return java.util.Optional.empty();
        }
    }

    /**
     * Stamp the artifact an Official Statement's rows came from (ADR-0005 provenance handle). This is what
     * makes "we have already read this issuer's OS" a FACT rather than an inference: call dates cannot
     * carry that meaning, because plenty of serial bonds are genuinely non-callable and would look
     * forever-uncovered. Batched; best-effort like every DB call here.
     */
    public int markSourced(List<String> cusips, String sourceId) {
        if (!available() || cusips.isEmpty() || sourceId == null || sourceId.isBlank()) {
            return 0;
        }
        try {
            List<Object[]> args = new java.util.ArrayList<>(cusips.size());
            for (String c : cusips) {
                args.add(new Object[] {sourceId, c});
            }
            jdbc.batchUpdate("UPDATE muni.security SET source_id = ?, updated_at = now() WHERE cusip = ?",
                    args);
            return cusips.size();
        } catch (DataAccessException e) {
            healthy = false;
            warnOnce(e);
            return 0;
        }
    }

    /**
     * What the lattice/OAS work (muni ADR-0002's Kalotay-inspired north star) can actually be run on
     * TODAY, counted from the data rather than estimated. Each field is a real precondition:
     *
     * <ul>
     *   <li><b>terms</b> — coupon + maturity: the cash flows. Without these there is no bond to value.</li>
     *   <li><b>callable</b> — a call date: the OPTION. An OAS engine with no option to value is a YTM
     *       calculator with extra steps, so this is the field that decides whether the work is worth
     *       doing at all.</li>
     *   <li><b>priced</b> — a filing valuation or an ingested price: OAS is SOLVED FROM a price. Quarterly
     *       filing marks make it an as-of-quarter-end OAS, which is honest and still useful.</li>
     *   <li><b>withTax</b> — tax status: needed for the de-minimis / tax-option analysis.</li>
     *   <li><b>modelable</b> — terms AND a price AND (a call OR a document proving there is none). The
     *       last clause matters: a bond read from an OS with no call is genuinely non-callable, which is a
     *       FACT, whereas a bond with no document simply has unknown optionality.</li>
     *   <li><b>periods</b> — distinct quarterly valuation dates, i.e. how long a time series exists.</li>
     * </ul>
     */
    public java.util.Optional<java.util.Map<String, Object>> modelReadiness() {
        if (!available()) {
            return java.util.Optional.empty();
        }
        try {
            java.util.Map<String, Object> out = jdbc.queryForObject("""
                    SELECT count(*)::int AS bonds,
                           count(*) FILTER (WHERE coupon IS NOT NULL
                                              AND maturity_date IS NOT NULL)::int AS terms,
                           count(*) FILTER (WHERE call_date IS NOT NULL)::int AS callable,
                           count(*) FILTER (WHERE val_per100 IS NOT NULL
                                               OR price IS NOT NULL)::int AS priced,
                           count(*) FILTER (WHERE tax_status IS NOT NULL)::int AS with_tax,
                           count(*) FILTER (WHERE source_id IS NOT NULL)::int AS from_os,
                           count(*) FILTER (WHERE coupon IS NOT NULL
                                              AND maturity_date IS NOT NULL
                                              AND (val_per100 IS NOT NULL OR price IS NOT NULL)
                                              AND source_id IS NOT NULL)::int AS modelable,
                           count(*) FILTER (WHERE coupon IS NOT NULL
                                              AND maturity_date IS NOT NULL
                                              AND (val_per100 IS NOT NULL OR price IS NOT NULL)
                                              AND source_id IS NOT NULL
                                              AND call_date IS NOT NULL)::int AS modelable_callable
                    FROM muni.security""",
                    (rs, i) -> {
                        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("bonds", rs.getInt("bonds"));
                        m.put("terms", rs.getInt("terms"));
                        m.put("callable", rs.getInt("callable"));
                        m.put("priced", rs.getInt("priced"));
                        m.put("withTax", rs.getInt("with_tax"));
                        m.put("fromOs", rs.getInt("from_os"));
                        m.put("modelable", rs.getInt("modelable"));
                        m.put("modelableCallable", rs.getInt("modelable_callable"));
                        return m;
                    });
            if (out == null) {
                return java.util.Optional.empty();
            }
            Integer periods = jdbc.queryForObject(
                    "SELECT count(DISTINCT as_of)::int FROM muni.valuation_history", Integer.class);
            out.put("valuationPeriods", periods == null ? 0 : periods);
            return java.util.Optional.of(out);
        } catch (DataAccessException e) {
            queryFailed("modelReadiness()", e);
            return java.util.Optional.empty();
        }
    }

    /** Read a one-time-job marker ({@code muni.ingest_state}); empty when unset or the DB is off. */
    public java.util.Optional<String> state(String key) {
        if (!available()) {
            return java.util.Optional.empty();
        }
        try {
            List<String> v = jdbc.query("SELECT value FROM muni.ingest_state WHERE key = ?",
                    (rs, i) -> rs.getString(1), key);
            return v.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(v.get(0));
        } catch (DataAccessException e) {
            healthy = false;
            return java.util.Optional.empty();
        }
    }

    /** Set a one-time-job marker. Returns false (and the job will re-run later) when the DB is off. */
    public boolean setState(String key, String value) {
        if (!available()) {
            return false;
        }
        try {
            jdbc.update("""
                    INSERT INTO muni.ingest_state (key, value, updated_at) VALUES (?, ?, now())
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                    key, value);
            return true;
        } catch (DataAccessException e) {
            healthy = false;
            return false;
        }
    }

    /** Persist one bond's terms. Returns true if it landed in Postgres; false (no-op) when the DB is off. */
    public boolean upsert(Bond b, String sourceId) {
        if (!available()) {
            return false;   // known-down → skip instantly; the bond still goes to the LMDB index
        }
        try {
            jdbc.update(UPSERT,
                    b.cusip(), b.issuer(), b.coupon(), b.maturity(), b.dated(), b.price(), b.taxStatus(),
                    b.callDate(), b.callPrice(), b.rating(), b.geoFips(), sourceId);
            return true;
        } catch (DataAccessException e) {
            healthy = false;   // it just went down mid-load — stop trying until the next probe
            warnOnce(e);
            return false;
        }
    }

    /** Row count in {@code muni.security}, or empty when the DB isn't reachable (Flyway off / DB down). */
    public OptionalLong count() {
        if (!available()) {
            return OptionalLong.empty();
        }
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM muni.security", Long.class);
            return n == null ? OptionalLong.empty() : OptionalLong.of(n);
        } catch (DataAccessException e) {
            healthy = false;
            return OptionalLong.empty();
        }
    }

    /**
     * A page of bonds for the browser: optional text filter, whitelisted sort, LIMIT/OFFSET.
     *
     * <p>Paging is done in SQL, not in Java: with thousands of securities, shipping the whole table to the
     * UI to slice it there is the thing that makes a bond browser unusable. The sort column is chosen from
     * a fixed map — never interpolated from the request — so this cannot become a SQL-injection seam.
     */
    public List<Row> page(String query, String sortColumn, boolean asc, int limit, int offset) {
        if (!available()) {
            return List.of();
        }
        String where = query == null || query.isBlank() ? "" : " WHERE cusip ILIKE ? OR issuer ILIKE ? ";
        String sql = "SELECT * FROM muni.security" + where
                + " ORDER BY " + sortColumn + (asc ? " ASC" : " DESC") + " NULLS LAST LIMIT ? OFFSET ?";
        try {
            if (where.isEmpty()) {
                return jdbc.query(sql, DETAILED, limit, offset);
            }
            String like = "%" + query.strip() + "%";
            return jdbc.query(sql, DETAILED, like, like, limit, offset);
        } catch (DataAccessException e) {
            healthy = false;
            return List.of();
        }
    }

    /** One bond with its filing detail — the detail view's read. Empty when absent or the DB is off. */
    public java.util.Optional<Row> findDetailed(String cusip) {
        if (!available()) {
            return java.util.Optional.empty();
        }
        try {
            List<Row> rows = jdbc.query("SELECT * FROM muni.security WHERE cusip = ?", DETAILED, cusip);
            return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
        } catch (DataAccessException e) {
            healthy = false;
            return java.util.Optional.empty();
        }
    }

    /** How many bonds match {@code query} — the page count the UI shows, not an estimate. */
    public OptionalLong countMatching(String query) {
        if (!available()) {
            return OptionalLong.empty();
        }
        try {
            Long n = query == null || query.isBlank()
                    ? jdbc.queryForObject("SELECT count(*) FROM muni.security", Long.class)
                    : jdbc.queryForObject(
                            "SELECT count(*) FROM muni.security WHERE cusip ILIKE ? OR issuer ILIKE ?",
                            Long.class, "%" + query.strip() + "%", "%" + query.strip() + "%");
            return n == null ? OptionalLong.empty() : OptionalLong.of(n);
        } catch (DataAccessException e) {
            healthy = false;
            return OptionalLong.empty();
        }
    }

    /** The most-recently-loaded bonds (system-of-record view), newest first. Empty when the DB isn't reachable. */
    public List<Bond> recent(int limit) {
        if (!available()) {
            return List.of();
        }
        try {
            return jdbc.query("SELECT * FROM muni.security ORDER BY updated_at DESC LIMIT ?", ROW, limit);
        } catch (DataAccessException e) {
            healthy = false;
            return List.of();
        }
    }

    private void warnOnce(DataAccessException e) {
        if (!warned) {
            warned = true;
            log.warn("Postgres unavailable for muni.security — bonds stay in the LMDB index only until the DB is "
                    + "up + Flyway is on (set MUNI_FLYWAY_ENABLED=true). Cause: {}", e.getMostSpecificCause().toString());
        }
    }
}

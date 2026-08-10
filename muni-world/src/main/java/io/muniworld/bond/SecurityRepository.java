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

    // Idempotent upsert (hard invariant 6): re-loading the same OS overwrites the row, never duplicates it.
    private static final String UPSERT = """
            INSERT INTO muni.security
              (cusip, issuer, coupon, maturity_date, dated_date, price, tax_status,
               call_date, call_price, rating, geo_fips, source_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (cusip) DO UPDATE SET
              issuer = EXCLUDED.issuer, coupon = EXCLUDED.coupon, maturity_date = EXCLUDED.maturity_date,
              dated_date = EXCLUDED.dated_date, price = EXCLUDED.price, tax_status = EXCLUDED.tax_status,
              call_date = EXCLUDED.call_date, call_price = EXCLUDED.call_price, rating = EXCLUDED.rating,
              geo_fips = EXCLUDED.geo_fips, source_id = EXCLUDED.source_id, updated_at = now()
            """;

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

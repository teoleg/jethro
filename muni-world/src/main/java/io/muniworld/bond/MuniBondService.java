package io.muniworld.bond;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.muniworld.domain.Bond;
import io.muniworld.domain.BondMath;
import io.muniworld.domain.BondRow;
import io.muniworld.domain.PriceQuote;
import io.muniworld.price.MuniPriceStore;
import io.muniworld.store.MuniKeys;
import io.muniworld.store.MuniSearchIndex;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores bonds in the LMDB B-tree index (ADR-0013) as JSON blobs keyed by CUSIP, and reads them back as
 * {@link BondRow}s with the economic indicators computed on the fly ({@link BondMath}). The index's
 * ordered keys give the by-issuer (CUSIP-6 prefix) and by-geography (FIPS prefix) scans the table uses.
 */
@Service
public final class MuniBondService {

    private final MuniSearchIndex index;
    private final ObjectMapper mapper;
    private final MuniPriceStore prices;
    private final SecurityRepository repo;

    public MuniBondService(MuniSearchIndex index, ObjectMapper mapper, MuniPriceStore prices,
                           SecurityRepository repo) {
        this.index = index;
        this.mapper = mapper;
        this.prices = prices;
        this.repo = repo;
    }

    /**
     * Store a bond. Postgres ({@code muni.security}) is the system of record and is written first (ADR-0006);
     * the LMDB B-tree (ADR-0013) is the derived read index and is always written so the UI works even with no
     * live Postgres. The DB write is best-effort — {@link SecurityRepository} swallows a DB-down error so the
     * index still lands.
     */
    public void index(Bond b) {
        // System of record, best-effort (no-ops when the DB is off). source_id is stamped separately by
        // the OS extractor once its rows land — it is a fact about the DOCUMENT, and the N-PORT path that
        // also calls this has no document behind it.
        repo.upsert(b, null);
        try {
            long couponScaled = MuniKeys.couponScaled(b.coupon());
            index.indexSecurity(b.cusip(), b.maturity(), couponScaled, b.geoFips(),
                    mapper.writeValueAsBytes(b));
        } catch (Exception e) {
            throw new RuntimeException("failed to index bond " + b.cusip(), e);
        }
    }

    /**
     * The most-recently-loaded bonds as display rows. Prefers Postgres (the system of record, ordered by load
     * time); falls back to the LMDB index when the DB isn't reachable, so the UI always shows what's loaded.
     */
    public List<BondRow> recent(int limit) {
        if (repo.available()) {
            return repo.recent(limit).stream().map(this::toRow).toList();
        }
        List<BondRow> out = new ArrayList<>();
        for (byte[] json : index.allValues(limit)) {
            out.add(toRow(toBond(json)));
        }
        return out;
    }

    /**
     * A page of the bond universe for the browser: text filter, sort, paging — all done in the DB.
     *
     * <p>The sort key is mapped through a fixed whitelist here, so no request text ever reaches SQL.
     * Returns the rows AND the total match count, because a pager without a total is a pager you cannot
     * navigate. Postgres only: the LMDB index has no ordering by these columns, and reporting a partial
     * result as if it were the universe would be worse than saying the DB is down.
     */
    public java.util.Map<String, Object> page(String query, String sort, boolean asc, int page, int size) {
        String column = sortColumn(sort);
        int limit = Math.min(Math.max(size, 1), 500);      // a page, not a table dump
        int offset = Math.max(page, 0) * limit;
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("page", Math.max(page, 0));
        out.put("size", limit);
        out.put("sort", sort == null ? "updated_at" : sort);
        out.put("asc", asc);
        if (!repo.available()) {
            out.put("total", null);
            out.put("rows", List.of());
            out.put("note", "Postgres is not reachable — the bond browser reads the system of record. "
                    + "The LMDB index still holds " + indexCount() + " securities.");
            return out;
        }
        java.util.OptionalLong total = repo.countMatching(query);   // one count query, not two
        out.put("total", total.isPresent() ? total.getAsLong() : null);
        out.put("rows", repo.page(query, column, asc, limit, offset).stream()
                .map(r -> toRow(r.bond(), r.detail())).toList());
        return out;
    }

    /**
     * Request sort key → real column name, from a CLOSED set. The sort key is the one piece of the browse
     * request that reaches SQL as an identifier (it cannot be a bind parameter), so anything unrecognised
     * falls back to the default rather than being passed through — the injection seam is closed here.
     */
    static String sortColumn(String sort) {
        return switch (sort == null ? "" : sort) {
            case "cusip" -> "cusip";
            case "issuer" -> "issuer";
            case "coupon" -> "coupon";
            case "maturity" -> "maturity_date";
            default -> "updated_at";
        };
    }

    /** A CUSIP's quarterly valuation series (par-weighted across funds) — see the repository. */
    public List<SecurityRepository.ValuationPoint> valuationSeries(String cusip) {
        return repo.valuationSeries(cusip);
    }

    /**
     * Document coverage: the universe grouped by issuer, biggest first, plus totals — so "how many OS
     * documents do I need?" is answered from the data instead of feared. One document covers a whole
     * series, so the top rows are where a single download buys the most.
     */
    public java.util.Map<String, Object> coverage(int limit, boolean includeDone) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        var totals = repo.coverageTotals();
        out.put("bonds", totals.map(t -> t[0]).orElse(null));
        out.put("issuers", totals.map(t -> t[1]).orElse(null));
        out.put("withCall", totals.map(t -> t[2]).orElse(null));
        out.put("fromOs", totals.map(t -> t[3]).orElse(null));
        out.put("issuersDone", totals.map(t -> t[4]).orElse(null));
        out.put("includeDone", includeDone);
        out.put("rows", repo.coverage(Math.min(Math.max(limit, 1), 200), includeDone));
        return out;
    }

    /** Analytics readiness, measured from the data — see the repository for what each count means. */
    public java.util.Map<String, Object> modelReadiness() {
        return repo.modelReadiness().orElseGet(() -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("note", "Postgres is not reachable — readiness is measured against the system of record.");
            return m;
        });
    }

    /** Stamp the OS artifact these CUSIPs were read from — the "we have this issuer's document" fact. */
    public int markSourced(java.util.List<String> cusips, String sourceId) {
        return repo.markSourced(cusips, sourceId);
    }

    /** Row count in Postgres, or {@code empty} when the DB isn't reachable (Flyway off / DB down). */
    public java.util.OptionalLong dbCount() {
        return repo.count();
    }

    /** Row count in the derived LMDB index. */
    public long indexCount() {
        return index.count();
    }

    public Optional<BondRow> get(String cusip) {
        // Postgres carries the filing detail (V3 columns) the LMDB json predates, so prefer it; the
        // index remains the offline fallback with terms-only rows.
        Optional<SecurityRepository.Row> pg = repo.findDetailed(cusip);
        if (pg.isPresent()) {
            return pg.map(r -> toRow(r.bond(), r.detail()));
        }
        return index.get(cusip).map(this::toBond).map(this::toRow);
    }

    /** All bonds for an issuer (CUSIP-6 prefix), as rows with indicators. */
    public List<BondRow> byIssuer(String cusip6) {
        return rows(index.byIssuer(cusip6));
    }

    /** All bonds in a geography (FIPS prefix), as rows with indicators. */
    public List<BondRow> byGeography(String fipsPrefix) {
        return rows(index.byGeography(fipsPrefix));
    }

    private List<BondRow> rows(List<String> cusips) {
        List<BondRow> out = new ArrayList<>(cusips.size());
        for (String c : cusips) {
            index.get(c).ifPresent(bytes -> out.add(toRow(toBond(bytes))));
        }
        return out;
    }

    private Bond toBond(byte[] json) {
        try {
            return mapper.readValue(json, Bond.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to read bond json", e);
        }
    }

    /** Terms-only row (LMDB fallback / OS loads) — no filing detail attached. */
    public BondRow toRow(Bond b) {
        return toRow(b, null);
    }

    /** Compute the display row (indicators as of today's settlement), carrying the filing detail. */
    public BondRow toRow(Bond b, SecurityRepository.Detail d) {
        double coupon = b.coupon().doubleValue();
        LocalDate settle = LocalDate.now();
        // A call with no stated price used to display "@100" — an invented money number wearing the
        // clothes of a document fact. An OS footnote often gives the call DATE and no redemption price;
        // say so rather than assuming par.
        String call = b.callDate() == null ? "—"
                : b.callDate() + (b.callPrice() == null ? " @price not stated"
                                                        : " @" + b.callPrice().toPlainString());

        // Current price: the price store (ADR-0015 — MSRB trade prints etc.) is the market authority; fall
        // back to a price carried on the bond itself (e.g. a direct/CSV ingest). OS-terms bonds have neither
        // until a quote lands → BLANK economics (NaN → "—"), never a stale/invented number.
        Optional<PriceQuote> q = prices.get(b.cusip());
        BigDecimal px = q.map(PriceQuote::price).orElse(b.price());
        String asOf = q.map(x -> x.asOf().toString()).orElse(null);
        String pxSource = q.map(PriceQuote::source).orElse(b.price() == null ? null : "ingest");

        // Filing detail is display fact, never an analytics input: the valuation is as-of a filing
        // period, so yields/duration still wait for a REAL current price (ADR-0016 discipline).
        Double valPer100 = d == null || d.valPer100() == null ? null : d.valPer100().doubleValue();
        String valAsOf = d == null || d.valAsOf() == null ? null : d.valAsOf().toString();
        String couponKind = d == null ? null : d.couponKind();
        Boolean inDefault = d == null ? null : d.inDefault();
        Boolean intArrears = d == null ? null : d.intArrears();
        Integer heldFunds = d == null ? null : d.heldFunds();
        Double heldPar = d == null || d.heldPar() == null ? null : d.heldPar().doubleValue();

        if (px == null) {
            double nan = Double.NaN;
            return new BondRow(
                    b.cusip(), b.issuer(), coupon, b.maturity().toString(), nan,
                    nan, nan, nan, nan, nan,
                    r(BondMath.accrued(coupon, settle, b.maturity()), 3),
                    b.taxStatus(), call, b.rating(), null, null,
                    couponKind, inDefault, intArrears, heldFunds, heldPar, valPer100, valAsOf);
        }
        double price = px.doubleValue();
        double callPrice = b.callPrice() == null ? 0.0 : b.callPrice().doubleValue();
        return new BondRow(
                b.cusip(), b.issuer(), coupon, b.maturity().toString(), price,
                r(BondMath.currentYield(coupon, price), 3),
                r(BondMath.ytm(coupon, settle, b.maturity(), price), 3),
                r(BondMath.ytw(coupon, settle, b.maturity(), price, b.callDate(), callPrice), 3),
                r(BondMath.modDuration(coupon, settle, b.maturity(), price), 2),
                r(BondMath.convexity(coupon, settle, b.maturity(), price), 2),
                r(BondMath.accrued(coupon, settle, b.maturity()), 3),
                b.taxStatus(), call, b.rating(), asOf, pxSource,
                couponKind, inDefault, intArrears, heldFunds, heldPar, valPer100, valAsOf);
    }

    private static double r(double v, int dp) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }
}

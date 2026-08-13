package io.muniworld.web;

import io.muniworld.bond.MuniBondService;
import io.muniworld.domain.Bond;
import io.muniworld.domain.BondRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The bonds table API — bonds by CUSIP with economic indicators, over the LMDB index (ADR-0013). Rows
 * carry coupon/maturity/price plus computed current yield, YTM, yield-to-worst, modified duration,
 * convexity and accrued (MuniBondService/BondMath).
 */
@RestController
public final class MuniBondController {

    private final MuniBondService bonds;
    private final io.muniworld.bond.OasService oas;

    public MuniBondController(MuniBondService bonds, io.muniworld.bond.OasService oas) {
        this.bonds = bonds;
        this.oas = oas;
    }

    /**
     * The OAS for one bond (ADR-0018): solved on a BDT lattice against the curve of the price's own date,
     * at the measured σ and its p10/p50/p90 band. {@code available:false} carries the named refusal reason.
     */
    @GetMapping("/api/muni/bonds/{cusip}/oas")
    public Map<String, Object> oas(@PathVariable String cusip,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean strict) {
        return oas.oas(cusip.toUpperCase(java.util.Locale.ROOT), strict);
    }

    /** Bonds for an issuer (CUSIP-6 prefix), with indicators. */
    @GetMapping("/api/muni/bonds/issuer/{cusip6}")
    public List<BondRow> byIssuer(@PathVariable String cusip6) {
        return bonds.byIssuer(cusip6);
    }

    /** Bonds in a geography (FIPS prefix), with indicators. */
    @GetMapping("/api/muni/bonds/geo/{fipsPrefix}")
    public List<BondRow> byGeography(@PathVariable String fipsPrefix) {
        return bonds.byGeography(fipsPrefix);
    }

    /** The most-recently-loaded bonds (from Postgres when up, else the LMDB index) — what the UI shows after a
     *  folder load, so the result of loading is visible without knowing a CUSIP-6 to search. */
    @GetMapping("/api/muni/bonds/recent")
    public List<BondRow> recent(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        return bonds.recent(Math.min(Math.max(limit, 1), 500));
    }

    /**
     * The bond BROWSER: one page of the universe, filtered and sorted in the DB.
     * {@code {page,size,sort,asc,total,rows}} — the total is what makes the pager navigable.
     */
    @GetMapping("/api/muni/bonds")
    public Map<String, Object> page(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "updated_at") String sort,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean asc,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return bonds.page(q, sort, asc, page, size);
    }

    /** One bond, everything stored about it — the detail view behind a row click. */
    @GetMapping("/api/muni/bonds/{cusip}")
    public org.springframework.http.ResponseEntity<BondRow> one(@PathVariable String cusip) {
        return bonds.get(cusip.toUpperCase(java.util.Locale.ROOT))
                .map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    /**
     * A CUSIP's quarterly valuation history — dated, fund-attested marks from every N-PORT on EDGAR,
     * par-weighted across the funds that held it each period. Oldest first; empty when none filed.
     */
    @GetMapping("/api/muni/bonds/{cusip}/valuations")
    public List<io.muniworld.bond.SecurityRepository.ValuationPoint> valuations(@PathVariable String cusip) {
        return bonds.valuationSeries(cusip.toUpperCase(java.util.Locale.ROOT));
    }

    /** Which Official Statements to fetch next: the universe by issuer, biggest first, plus totals. */
    @GetMapping("/api/muni/bonds/coverage")
    public Map<String, Object> coverage(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "25") int limit,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeDone) {
        return bonds.coverage(limit, includeDone);
    }

    /** Can the lattice/OAS work start? The preconditions, counted from the data. */
    @GetMapping("/api/muni/bonds/readiness")
    public Map<String, Object> readiness() {
        return bonds.modelReadiness();
    }

    /** Index a bond (the loader/connector seam; also lets tools push a bond in). */
    @PostMapping("/api/muni/bonds")
    public Map<String, Object> index(@RequestBody Bond bond) {
        bonds.index(bond);
        return Map.of("indexed", bond.cusip());
    }
}

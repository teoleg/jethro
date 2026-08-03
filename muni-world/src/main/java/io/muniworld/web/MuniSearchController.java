package io.muniworld.web;

import io.muniworld.store.MuniKeys;
import io.muniworld.store.MuniSearchIndex;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search API over the ADR-0013 LMDB B-tree index — the ordered-key lookups (issuer prefix, maturity/coupon
 * ranges, geography prefix, point lookup) the UI and analysis layer call. Read-only searches, plus a
 * write endpoint the loader/indexer (and tests) use to push a security in. Returns CUSIP lists as JSON.
 */
@RestController
public final class MuniSearchController {

    private final MuniSearchIndex index;

    public MuniSearchController(MuniSearchIndex index) {
        this.index = index;
    }

    /** All CUSIPs for an issuer — prefix scan on the CUSIP-6 (or any leading fragment). */
    @GetMapping("/api/muni/search/issuer/{cusip6}")
    public List<String> byIssuer(@PathVariable String cusip6) {
        return index.byIssuer(cusip6);
    }

    /** CUSIPs in a geography — FIPS prefix (state `34`, county `34003`, place `3403900000`). */
    @GetMapping("/api/muni/search/geo/{fipsPrefix}")
    public List<String> byGeography(@PathVariable String fipsPrefix) {
        return index.byGeography(fipsPrefix);
    }

    /** CUSIPs maturing in [from, to] inclusive (ISO dates). */
    @GetMapping("/api/muni/search/maturity")
    public List<String> byMaturity(@RequestParam String from, @RequestParam String to) {
        return index.byMaturityRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    /** CUSIPs whose coupon (percent) is in [lo, hi] inclusive, e.g. lo=4.0&hi=5.0. */
    @GetMapping("/api/muni/search/coupon")
    public List<String> byCoupon(@RequestParam double lo, @RequestParam double hi) {
        return index.byCouponRange(
                MuniKeys.couponScaled(BigDecimal.valueOf(lo)),
                MuniKeys.couponScaled(BigDecimal.valueOf(hi)));
    }

    /** Point lookup: the value blob stored for a CUSIP. */
    @GetMapping("/api/muni/search/cusip/{cusip}")
    public Map<String, Object> byCusip(@PathVariable String cusip) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cusip", cusip);
        index.get(cusip).ifPresentOrElse(
                b -> {
                    out.put("found", true);
                    out.put("value", new String(b, StandardCharsets.UTF_8));
                },
                () -> out.put("found", false));
        return out;
    }

    /** Index one security into the LMDB trees (used by the loader/indexer and tests). */
    @PostMapping("/api/muni/index")
    public Map<String, Object> index(@RequestBody IndexRequest r) {
        index.indexSecurity(
                r.cusip(),
                r.maturity() == null || r.maturity().isBlank() ? null : LocalDate.parse(r.maturity()),
                r.coupon() == null ? -1 : MuniKeys.couponScaled(r.coupon()),
                r.geoFips(),
                r.value() == null ? null : r.value().getBytes(StandardCharsets.UTF_8));
        return Map.of("indexed", r.cusip());
    }

    /** Index request body. {@code coupon} is a percent (e.g. 4.5); stored as an exact scaled long. */
    public record IndexRequest(String cusip, String maturity, BigDecimal coupon, String geoFips, String value) {
    }
}

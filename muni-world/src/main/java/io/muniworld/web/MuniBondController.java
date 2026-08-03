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

    public MuniBondController(MuniBondService bonds) {
        this.bonds = bonds;
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

    /** Index a bond (the loader/connector seam; also lets tools push a bond in). */
    @PostMapping("/api/muni/bonds")
    public Map<String, Object> index(@RequestBody Bond bond) {
        bonds.index(bond);
        return Map.of("indexed", bond.cusip());
    }
}

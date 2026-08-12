package io.muniworld.web;

import io.muniworld.curve.CurveRepository;
import io.muniworld.curve.GswCurveIngest;
import io.muniworld.curve.NelsonSiegelSvensson;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The benchmark curve and volatility API (ADR-0017) — the two model inputs that come from the market rather
 * than from a document, and the provenance of each.
 */
@RestController
public final class CurveController {

    private final GswCurveIngest ingest;
    private final CurveRepository repo;

    public CurveController(GswCurveIngest ingest, CurveRepository repo) {
        this.ingest = ingest;
        this.repo = repo;
    }

    /** Curve ingest state, stored history depth, and the latest vol estimates with their bands. */
    @GetMapping("/api/muni/curve")
    public Map<String, Object> status() {
        return ingest.summary();
    }

    /**
     * Evaluate the benchmark curve at an arbitrary maturity — the closed-form advantage of storing the fit
     * rather than only a tenor grid. Returns the zero rate and the discount factor.
     */
    @GetMapping("/api/muni/curve/zero")
    public Map<String, Object> zero(@RequestParam double years,
                                    @RequestParam(defaultValue = "") String asOf) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("years", years);
        Map<String, Object> fit = repo.fitFor(GswCurveIngest.SOURCE, asOf.isBlank() ? null : asOf);
        if (fit == null) {
            out.put("available", false);
            // Refusal is a valid answer (ADR-0011): no curve ingested means no rate, not a default rate.
            out.put("reason", "no curve stored for " + GswCurveIngest.SOURCE
                    + (asOf.isBlank() ? " yet" : " on " + asOf));
            return out;
        }
        NelsonSiegelSvensson curve = new NelsonSiegelSvensson(
                d(fit, "beta0"), d(fit, "beta1"), d(fit, "beta2"), d(fit, "beta3"),
                d(fit, "tau1"), d(fit, "tau2"));
        out.put("available", true);
        out.put("asOf", fit.get("asOf"));
        out.put("source", GswCurveIngest.SOURCE);
        out.put("zeroRate", curve.zeroRate(years));
        out.put("discountFactor", curve.discountFactor(years));
        out.put("shortRate", curve.shortRate());
        out.put("compounding", "continuous");
        // Named so nobody reads a Treasury zero as a tax-exempt one (ADR-0017 §2).
        out.put("basis", "TAXABLE_TREASURY — not a tax-exempt muni curve; the muni ratio leg is measured "
                + "from the N-PORT panel as Official Statement coverage grows");
        return out;
    }

    private static double d(Map<String, Object> fit, String key) {
        return ((BigDecimal) fit.get(key)).doubleValue();
    }
}

package io.muniworld.web;

import io.muniworld.curve.BdtLattice;
import io.muniworld.curve.CurveRepository;
import io.muniworld.curve.GswCurveIngest;
import io.muniworld.curve.LatticeBondPricer;
import io.muniworld.curve.ModelAnalytics;
import io.muniworld.curve.NelsonSiegelSvensson;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model workbench — "book mode" (ADR-0018). Every input is EXPLICIT: a stated curve (flat, or the
 * latest ingested GSW), a stated σ, stated bond terms. Nothing is read from a bond record, so a worked
 * example from Kalotay's book can be reproduced number-for-number with the book's own inputs — which is
 * also the strongest validation the lattice can get.
 *
 * <p>The response exposes every intermediate: the step grid, the zero/discount curve per step, the short-
 * rate lattice itself, both value legs, the option value between them, the OAS solve when a price is
 * given, effective duration/convexity from the ±25bp bumps, and refunding efficiency. The workbench page
 * renders these with the logic spelled out next to each number.
 *
 * <p>Inputs here are the USER'S assumptions, echoed back as such — the workbench never stores them and
 * never mixes them into bond records (the no-invented-numbers rule holds because provenance is explicit:
 * "you typed this").
 */
@RestController
public final class ModelWorkbenchController {

    private final CurveRepository curves;
    private final io.muniworld.bond.OasService oasService;

    public ModelWorkbenchController(CurveRepository curves, io.muniworld.bond.OasService oasService) {
        this.curves = curves;
        this.oasService = oasService;
    }

    /**
     * "Load a real bond": the workbench view driven entirely by COLLECTED data — the bond's stored terms,
     * its filing price, the curve of that price's date and the measured σ — assembled by the exact same
     * code path as the per-bond OAS (OasService), so the two can never disagree. The response is the same
     * full-visibility shape as {@link #analyze}, plus the provenance of every input.
     */
    @GetMapping("/api/muni/model/bond")
    public Map<String, Object> bond(@RequestParam String cusip) {
        Map<String, Object> in = oasService.workbenchInputs(
                cusip.toUpperCase(java.util.Locale.ROOT).trim());
        if (!Boolean.TRUE.equals(in.get("available"))) {
            return in;      // the same named refusal the OAS block shows
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fit = (Map<String, Object>) in.get("fit");
        NelsonSiegelSvensson curve = new NelsonSiegelSvensson(
                dv(fit, "beta0"), dv(fit, "beta1"), dv(fit, "beta2"), dv(fit, "beta3"),
                dv(fit, "tau1"), dv(fit, "tau2"));
        Double callYears = (Double) in.get("callYears");
        Map<String, Object> out = run(curve,
                GswCurveIngest.SOURCE + " " + in.get("curveAsOf") + " — the curve of the PRICE's own date",
                ((java.math.BigDecimal) in.get("sigmaPct")).doubleValue(),
                ((Number) in.get("couponPct")).doubleValue(),
                (Double) in.get("years"),
                callYears,
                in.get("callPricePer100") == null ? 100.0 : ((Number) in.get("callPricePer100")).doubleValue(),
                in.get("pricePer100") == null ? null : ((Number) in.get("pricePer100")).doubleValue(),
                0);
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("cusip", in.get("cusip"));
        prov.put("issuer", in.get("issuer"));
        prov.put("price", in.get("pricePer100") + " as-of " + in.get("priceAsOf")
                + " (" + in.get("priceSource") + ")");
        prov.put("sigma", in.get("sigmaSeries") + " — measured, not assumed");
        prov.put("assumedParCall", in.get("assumedParCall"));
        out.put("provenance", prov);
        return out;
    }

    /**
     * Analyze one explicitly-specified bond.
     *
     * @param flatPct     flat continuous zero curve in percent (book style) — mutually exclusive with gsw
     * @param gsw         true = use the latest ingested GSW fit instead of a flat curve
     * @param sigmaPct    lognormal short-rate vol in percent (e.g. 15)
     * @param couponPct   annual coupon, percent, semiannual pay
     * @param years       years to maturity
     * @param callYears   years to first call; omit for a non-callable bond
     * @param callPrice   call strike per 100 (default 100 — the modern-muni par-call convention)
     * @param pricePer100 optional market price; when present the OAS is solved from it
     * @param spreadBp    spread used for valuation when no price is given (default 0 = on-curve fair value)
     */
    @GetMapping("/api/muni/model/analyze")
    public Map<String, Object> analyze(
            @RequestParam(required = false) Double flatPct,
            @RequestParam(defaultValue = "false") boolean gsw,
            @RequestParam double sigmaPct,
            @RequestParam double couponPct,
            @RequestParam double years,
            @RequestParam(required = false) Double callYears,
            @RequestParam(defaultValue = "100") double callPrice,
            @RequestParam(required = false) Double pricePer100,
            @RequestParam(defaultValue = "0") double spreadBp) {

        Map<String, Object> out = new LinkedHashMap<>();
        if (years <= 0 || sigmaPct < 0 || (flatPct == null) == !gsw) {
            out.put("available", false);
            out.put("reason", "need years > 0, sigma >= 0, and exactly one of flatPct / gsw=true");
            return out;
        }

        // ---- the curve, from the stated source ------------------------------------------------------
        NelsonSiegelSvensson curve;
        String curveLabel;
        if (gsw) {
            Map<String, Object> fit = curves.fitFor(GswCurveIngest.SOURCE, null);
            if (fit == null) {
                out.put("available", false);
                out.put("reason", "gsw=true but no GSW curve is ingested yet — see the curve panel");
                return out;
            }
            curve = new NelsonSiegelSvensson(dv(fit, "beta0"), dv(fit, "beta1"), dv(fit, "beta2"),
                    dv(fit, "beta3"), dv(fit, "tau1"), dv(fit, "tau2"));
            curveLabel = "GSW " + fit.get("asOf") + " (latest ingested Fed fit)";
        } else {
            curve = new NelsonSiegelSvensson(flatPct, 0, 0, 0, 1, 1);
            curveLabel = "flat " + flatPct + "% continuous (your stated assumption)";
        }
        return run(curve, curveLabel, sigmaPct, couponPct, years, callYears, callPrice, pricePer100,
                spreadBp);
    }

    /** The shared computation behind both modes — typed inputs and a loaded real bond. */
    private Map<String, Object> run(NelsonSiegelSvensson curve, String curveLabel, double sigmaPct,
                                    double couponPct, double years, Double callYears, double callPrice,
                                    Double pricePer100, double spreadBp) {
        Map<String, Object> out = new LinkedHashMap<>();
        // ---- geometry + cash flows (same conventions as the per-bond path, ADR-0018 §3) --------------
        int n = Math.max(2, (int) Math.round(2 * years));
        double dt = years / n;
        double[] stepDf = new double[n];
        List<Map<String, Object>> stepRows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double t = (i + 1) * dt;
            stepDf[i] = curve.discountFactor(t).doubleValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", round(t, 4));
            row.put("zeroPct", curve.zeroRate(t).movePointRight(2).setScale(4, RoundingMode.HALF_UP));
            row.put("df", round(stepDf[i], 8));
            stepRows.add(row);
        }
        double couponPerStep = couponPct / 2.0;
        double sigma = sigmaPct / 100.0;
        Double strike = callYears == null ? null : callPrice;
        int firstCallStep = callYears == null ? Integer.MAX_VALUE
                : Math.max(1, (int) Math.ceil(callYears / dt - 1e-9));
        boolean currentlyCallable = callYears != null && callYears <= 0;

        // ---- the spread: solved from a price, or stated ----------------------------------------------
        BdtLattice lattice = BdtLattice.calibrate(stepDf, sigma, dt);
        double spread = spreadBp / 10_000.0;
        Map<String, Object> oasBlock = null;
        if (pricePer100 != null) {
            double solved = LatticeBondPricer.solveOas(lattice, couponPerStep, strike, firstCallStep,
                    pricePer100);
            oasBlock = new LinkedHashMap<>();
            oasBlock.put("targetPrice", pricePer100);
            if (Double.isNaN(solved)) {
                oasBlock.put("solved", false);
                oasBlock.put("reason", "price is outside the ±1,000bp solve range");
            } else {
                oasBlock.put("solved", true);
                oasBlock.put("oasBp", round(solved * 10_000, 2));
                spread = solved;      // analytics below are AT the solved OAS, like the per-bond path
            }
        }

        ModelAnalytics.Result a = ModelAnalytics.analyze(stepDf, sigma, dt, couponPerStep, strike,
                firstCallStep, currentlyCallable, spread);

        // ---- the lattice itself, made visible (capped so a 30y tree stays readable) ------------------
        int shown = Math.min(n, 8);
        List<List<BigDecimal>> tree = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            List<BigDecimal> col = new ArrayList<>();
            for (int j = 0; j <= i; j++) {
                col.add(round(lattice.rate(i, j) * 100, 4));
            }
            tree.add(col);
        }

        out.put("available", true);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("curve", curveLabel);
        inputs.put("sigmaPct", sigmaPct);
        inputs.put("couponPct", couponPct);
        inputs.put("years", years);
        inputs.put("callYears", callYears);
        inputs.put("callPricePer100", callYears == null ? null : callPrice);
        inputs.put("pricePer100", pricePer100);
        inputs.put("spreadBpUsed", round(spread * 10_000, 2));
        out.put("inputs", inputs);
        out.put("steps", n);
        out.put("dtYears", round(dt, 6));
        out.put("stepCurve", stepRows);
        out.put("latticeShownSteps", shown);
        out.put("latticeRatesPct", tree);
        out.put("oas", oasBlock);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("straightPer100", round(a.straight(), 6));
        values.put("callablePer100", round(a.callable(), 6));
        values.put("optionValuePer100", round(a.optionValue(), 6));
        values.put("effDuration", round(a.effDuration(), 4));
        values.put("effConvexity", round(a.effConvexity(), 4));
        values.put("currentlyCallable", currentlyCallable);
        values.put("refundingEfficiencyPct", a.refundingEfficiency() == null ? null
                : round(a.refundingEfficiency() * 100, 2));
        out.put("values", values);
        out.put("conventions", List.of(
                "BDT lognormal lattice, constant sigma, exact zero-curve calibration (ADR-0018)",
                "semiannual fixed coupons; n = max(2, round(2·years)) steps landing exactly on maturity",
                "call: American on/after callYears, issuer minimises value; default strike 100 = par-call convention",
                "duration/convexity: OAS-constant, ±25bp parallel bumps, lattice recalibrated per bump",
                "refunding efficiency: (straight − callPrice) / optionValue, defined only when currently callable; "
                + "Kalotay's rule of thumb refunds above ~90%"));
        return out;
    }

    private static BigDecimal round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    private static double dv(Map<String, Object> fit, String key) {
        return ((BigDecimal) fit.get(key)).doubleValue();
    }
}

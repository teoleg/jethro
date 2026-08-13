package io.muniworld.bond;

import io.muniworld.curve.BdtLattice;
import io.muniworld.curve.CurveRepository;
import io.muniworld.curve.GswCurveIngest;
import io.muniworld.curve.LatticeBondPricer;
import io.muniworld.curve.NelsonSiegelSvensson;
import io.muniworld.domain.Bond;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The OAS calculation (ADR-0018): assemble a bond's inputs, refuse loudly when any is missing or unfit, and
 * otherwise solve the option-adjusted spread on a BDT lattice calibrated to the GSW curve as-of the PRICE's
 * date — at the measured lognormal σ and at its p10/p50/p90 band, never at one hidden number.
 *
 * <p>Every convention the result depends on is echoed in the output (steps, price basis, call handling,
 * curve date, basis honesty). Refusal is a first-class outcome with a named reason — an OAS computed on a
 * guessed input would be worse than no OAS (ADR-0011).
 */
@Service
public class OasService {

    /** OAS is reported in BASIS POINTS at 2dp, rounded once (ADR-0017 §4 / ADR-0018 §3). */
    private static final int OAS_SCALE = 2;

    private final SecurityRepository securities;
    private final CurveRepository curves;

    public OasService(SecurityRepository securities, CurveRepository curves) {
        this.securities = securities;
        this.curves = curves;
    }

    /** Compute (or refuse, with the reason) the OAS for one CUSIP. Shapes match the API response. */
    public Map<String, Object> oas(String cusip) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cusip", cusip);

        Optional<SecurityRepository.Row> rowOpt = securities.findDetailed(cusip);
        if (rowOpt.isEmpty()) {
            return refuse(out, "bond not found (or DB down)");
        }
        Bond b = rowOpt.get().bond();
        SecurityRepository.Detail d = rowOpt.get().detail();

        // ---- the refusal gates (ADR-0018 §5), most fundamental first --------------------------------
        if (b.coupon() == null || b.maturity() == null) {
            return refuse(out, "terms incomplete — coupon or maturity missing");
        }
        if (d == null || d.valPer100() == null || d.valAsOf() == null) {
            return refuse(out, "no price — no fund filing valuation stored for this CUSIP");
        }
        if (d.couponKind() != null && !d.couponKind().isBlank()
                && !"fixed".equalsIgnoreCase(d.couponKind().strip())) {
            return refuse(out, "coupon is " + d.couponKind()
                    + " — the v1 model prices fixed-rate bonds only, never approximates a floater");
        }
        if (Boolean.TRUE.equals(d.inDefault()) || Boolean.TRUE.equals(d.intArrears())) {
            return refuse(out, "a fund attested default/arrears — the price reflects credit distress and a "
                    + "rate-model OAS on it would be noise");
        }
        boolean osRead = d.sourceId() != null;
        boolean callable = b.callDate() != null;
        if (!callable && !osRead) {
            return refuse(out, "callability UNKNOWN — no Official Statement read for this bond; treating "
                    + "unknown as non-callable would invent the most important term in the model. Load the "
                    + "issuer's OS from the coverage plan.");
        }
        LocalDate priceDate = d.valAsOf();
        if (!b.maturity().isAfter(priceDate)) {
            return refuse(out, "bond matured on/before the price date (" + priceDate + ")");
        }

        // ---- the market inputs (ADR-0017) -----------------------------------------------------------
        Map<String, Object> fit = curves.fitOnOrBefore(GswCurveIngest.SOURCE, priceDate);
        if (fit == null) {
            return refuse(out, "no benchmark curve on/before " + priceDate
                    + " — has the GSW ingest run? (see the curve panel)");
        }
        Map<String, Object> vol = curves.latestVol("GSW:1Y", "LOGNORMAL");
        if (vol == null) {
            return refuse(out, "rate volatility not measured yet — it lands with the curve ingest");
        }

        // ---- lattice geometry (ADR-0018 §3) ----------------------------------------------------------
        // CONVENTION: ACT/365.25 for the year fraction; near-semiannual steps landing exactly on maturity.
        double years = ChronoUnit.DAYS.between(priceDate, b.maturity()) / 365.25;
        int n = Math.max(2, (int) Math.round(2 * years));
        double dt = years / n;
        NelsonSiegelSvensson curve = new NelsonSiegelSvensson(
                dv(fit, "beta0"), dv(fit, "beta1"), dv(fit, "beta2"), dv(fit, "beta3"),
                dv(fit, "tau1"), dv(fit, "tau2"));
        double[] stepDf = new double[n];
        for (int i = 0; i < n; i++) {
            stepDf[i] = curve.discountFactor((i + 1) * dt).doubleValue();
        }
        double couponPerStep = b.coupon().doubleValue() / 2.0;   // percent coupon → per-100 per semiannual step
        Double callPrice = null;
        int firstCallStep = Integer.MAX_VALUE;
        boolean assumedParCall = false;
        if (callable) {
            // CONVENTION: a stated call date with no stated price is a par call (the modern-muni norm) —
            // flagged in the output, never silent.
            assumedParCall = b.callPrice() == null;
            callPrice = assumedParCall ? 100.0 : b.callPrice().doubleValue();
            double yearsToCall = ChronoUnit.DAYS.between(priceDate, b.callDate()) / 365.25;
            firstCallStep = Math.max(1, (int) Math.ceil(yearsToCall / dt - 1e-9));
        }
        double targetClean = d.valPer100().doubleValue();

        // ---- solve at the measured σ and across its band (ADR-0018 §4) ------------------------------
        Map<String, Object> results = new LinkedHashMap<>();
        BigDecimal sigmaMeasured = (BigDecimal) vol.get("sigma");
        Map<String, Object> atMeasured = solveAt(sigmaMeasured, stepDf, dt, couponPerStep, callPrice,
                firstCallStep, targetClean);
        results.put("measured", atMeasured);
        for (String p : List.of("p10", "p50", "p90")) {
            BigDecimal s = (BigDecimal) vol.get(p);
            results.put(p, s == null ? null
                    : solveAt(s, stepDf, dt, couponPerStep, callPrice, firstCallStep, targetClean));
        }

        out.put("available", true);
        out.put("callable", callable);
        out.put("oasBpBySigma", results);
        // ---- the book-level analytics at the measured-σ OAS (option value, duration, refunding) -----
        if (Boolean.TRUE.equals(atMeasured.get("solved"))) {
            double oasSpread = ((BigDecimal) atMeasured.get("oasBp")).doubleValue() / 10_000.0;
            boolean currentlyCallable = callable && !b.callDate().isAfter(priceDate);
            io.muniworld.curve.ModelAnalytics.Result a = io.muniworld.curve.ModelAnalytics.analyze(
                    stepDf, sigmaMeasured.doubleValue(), dt, couponPerStep, callPrice, firstCallStep,
                    currentlyCallable, oasSpread);
            Map<String, Object> analytics = new LinkedHashMap<>();
            analytics.put("straightPer100", round(a.straight(), 6));
            analytics.put("callablePer100", round(a.callable(), 6));
            analytics.put("optionValuePer100", round(a.optionValue(), 6));
            analytics.put("effDuration", round(a.effDuration(), 4));
            analytics.put("effConvexity", round(a.effConvexity(), 4));
            analytics.put("currentlyCallable", currentlyCallable);
            analytics.put("refundingEfficiencyPct", a.refundingEfficiency() == null ? null
                    : round(a.refundingEfficiency() * 100, 2));
            out.put("analytics", analytics);
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("cleanPricePer100", d.valPer100());
        inputs.put("priceAsOf", String.valueOf(priceDate));
        inputs.put("priceSource", "N-PORT par-weighted filing valuation (" + d.heldFunds() + " fund(s))");
        inputs.put("curveAsOf", fit.get("asOf"));
        inputs.put("curveSource", GswCurveIngest.SOURCE);
        inputs.put("couponPct", b.coupon());
        inputs.put("maturity", String.valueOf(b.maturity()));
        inputs.put("callDate", callable ? String.valueOf(b.callDate()) : null);
        inputs.put("callPricePer100", callPrice);
        inputs.put("assumedParCall", assumedParCall);
        inputs.put("sigmaSeries", "GSW:1Y LOGNORMAL, " + vol.get("windowDays") + "d window, as-of "
                + vol.get("asOf"));
        inputs.put("sigmaMeasured", sigmaMeasured);
        inputs.put("steps", n);
        inputs.put("dtYears", BigDecimal.valueOf(dt).setScale(6, RoundingMode.HALF_UP));
        out.put("inputs", inputs);
        out.put("conventions", List.of(
                "model: BDT lognormal lattice, constant sigma, exact zero-curve calibration (ADR-0018)",
                "price treated as CLEAN (fund fair-value practice); N-PORT does not state accrued handling",
                "coupons semiannual fixed; steps ACT/365.25, landing exactly on maturity",
                "call: American on/after the OS call date, issuer minimises value",
                "OAS solved within ±1,000bp; a price outside returns unsolvable, never a clamped fit — "
                + "every constant is registered in docs/model-assumptions.md",
                "BASIS: taxable Treasury — tax-exempt bonds typically show NEGATIVE OAS on this basis; "
                + "rank bonds against each other, do not read absolute cheapness until the muni-ratio "
                + "leg is measured (ADR-0017 §2)"));
        return out;
    }

    /** One solve at one σ. NaN (target price unreachable within ±1,000bp) reports as unsolvable. */
    private static Map<String, Object> solveAt(BigDecimal sigma, double[] stepDf, double dt,
                                               double couponPerStep, Double callPrice, int firstCallStep,
                                               double targetClean) {
        BdtLattice lattice = BdtLattice.calibrate(stepDf, sigma.doubleValue(), dt);
        double spread = LatticeBondPricer.solveOas(lattice, couponPerStep, callPrice, firstCallStep,
                targetClean);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sigma", sigma);
        if (Double.isNaN(spread)) {
            r.put("solved", false);
            r.put("reason", "price " + targetClean + " is outside the ±1,000bp solve range — likely a "
                    + "distressed or mis-stated mark, reported rather than force-fit");
            return r;
        }
        r.put("solved", true);
        // The one rounding of the transcendental result: continuous spread → basis points at 2dp.
        r.put("oasBp", BigDecimal.valueOf(spread * 10_000).setScale(OAS_SCALE, RoundingMode.HALF_UP));
        return r;
    }

    /**
     * The SAME assembled inputs the OAS path uses, exposed for the model workbench's "load a real bond"
     * mode — one assembly code path, so a workbench view of a bond can never disagree with its OAS block.
     * Returns {@code available:false} with the same named refusals, or the explicit-input equivalents:
     * the curve fit of the price's date, the measured σ, and the bond's terms as year counts.
     */
    public Map<String, Object> workbenchInputs(String cusip) {
        Map<String, Object> full = oas(cusip);
        if (!Boolean.TRUE.equals(full.get("available"))) {
            return full;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> in = (Map<String, Object>) full.get("inputs");
        SecurityRepository.Row row = securities.findDetailed(cusip).orElseThrow();
        LocalDate priceDate = row.detail().valAsOf();
        Map<String, Object> fit = curves.fitOnOrBefore(GswCurveIngest.SOURCE, priceDate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("cusip", cusip);
        out.put("issuer", row.bond().issuer());
        out.put("fit", fit);
        out.put("curveAsOf", in.get("curveAsOf"));
        out.put("sigmaPct", ((BigDecimal) in.get("sigmaMeasured")).multiply(new BigDecimal(100)));
        out.put("sigmaSeries", in.get("sigmaSeries"));
        out.put("couponPct", in.get("couponPct"));
        out.put("years", ChronoUnit.DAYS.between(priceDate, row.bond().maturity()) / 365.25);
        out.put("callYears", row.bond().callDate() == null ? null
                : ChronoUnit.DAYS.between(priceDate, row.bond().callDate()) / 365.25);
        out.put("callPricePer100", in.get("callPricePer100"));
        out.put("assumedParCall", in.get("assumedParCall"));
        out.put("pricePer100", in.get("cleanPricePer100"));
        out.put("priceAsOf", in.get("priceAsOf"));
        out.put("priceSource", in.get("priceSource"));
        return out;
    }

    /** The single rounding of a transcendental result at its declared scale (ADR-0017 §4). */
    private static BigDecimal round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> refuse(Map<String, Object> out, String reason) {
        out.put("available", false);
        out.put("reason", reason);
        return out;
    }

    private static double dv(Map<String, Object> fit, String key) {
        return ((BigDecimal) fit.get(key)).doubleValue();
    }
}

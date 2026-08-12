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
        results.put("measured", solveAt(sigmaMeasured, stepDf, dt, couponPerStep, callPrice,
                firstCallStep, targetClean));
        for (String p : List.of("p10", "p50", "p90")) {
            BigDecimal s = (BigDecimal) vol.get(p);
            results.put(p, s == null ? null
                    : solveAt(s, stepDf, dt, couponPerStep, callPrice, firstCallStep, targetClean));
        }

        out.put("available", true);
        out.put("callable", callable);
        out.put("oasBpBySigma", results);
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

    private static Map<String, Object> refuse(Map<String, Object> out, String reason) {
        out.put("available", false);
        out.put("reason", reason);
        return out;
    }

    private static double dv(Map<String, Object> fit, String key) {
        return ((BigDecimal) fit.get(key)).doubleValue();
    }
}

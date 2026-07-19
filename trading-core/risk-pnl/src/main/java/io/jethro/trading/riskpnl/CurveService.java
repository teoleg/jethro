package io.jethro.trading.riskpnl;

import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.date.DayCounts;
import com.opengamma.strata.basics.date.Tenor;
import com.opengamma.strata.basics.index.OvernightIndices;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.market.curve.Curve;
import com.opengamma.strata.market.curve.Curves;
import com.opengamma.strata.market.curve.InterpolatedNodalCurve;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolators;
import com.opengamma.strata.pricer.rate.ImmutableRatesProvider;
import com.opengamma.strata.pricer.swap.DiscountingSwapProductPricer;
import com.opengamma.strata.product.common.BuySell;
import com.opengamma.strata.product.swap.ResolvedSwap;
import com.opengamma.strata.product.swap.type.FixedOvernightSwapConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Live USD SOFR zero curve assembled from streamed tenor quotes (quant-engine phase 4).
 * The sim (or a real provider) publishes {@code USD.SOFR.<tenor>} marks quoted in percent —
 * these are <b>PAR swap rates</b> (what actually trades, V9 convention). The service
 * <b>bootstraps</b> them to a Strata zero curve (ADR-0041): the zero nodes are calibrated by
 * fixed-point iteration {@code z ← z + (quote − parRate(curve))} until the Strata-priced par
 * rate of each tenor's OIS reproduces its quote (tol 1e-9) — Strata does all pricing (ADR-0020);
 * the solver is the only loop owned here. Par ≠ zero on a sloped curve (tens of bp at the long
 * end), so loading quotes as zeros — the pre-ADR-0041 behaviour — biased long-tenor PV/DV01.
 * If calibration ever fails to converge, the quote-as-zero curve is returned with a warning —
 * the risk path never dies on a solver.
 *
 * <p>The no-arg constructor keeps the legacy quote-as-zero behaviour as an explicit TEST-FIXTURE
 * mode: pricer tests supply a known zero curve; calibration has its own round-trip test.
 *
 * <p>Rates/DFs are statistical market data, computed in double — never ledger money
 * (invariant 1 untouched).
 */
public final class CurveService {

    private static final Logger log = LoggerFactory.getLogger(CurveService.class);
    private static final ReferenceData REF_DATA = ReferenceData.standard();
    private static final double TOL = 1e-9;
    private static final int MAX_ITERATIONS = 20;

    /** Tenor grid: quote id → tenor in years. */
    private static final Map<String, Double> TENORS = Map.of(
            "USD.SOFR.1Y", 1.0, "USD.SOFR.2Y", 2.0, "USD.SOFR.5Y", 5.0,
            "USD.SOFR.10Y", 10.0, "USD.SOFR.30Y", 30.0);

    public record CurvePoint(String quoteId, double tenorYears, double zeroRate, double discountFactor) {
    }

    private final Map<String, Double> latestRates = new ConcurrentHashMap<>();
    private final boolean bootstrap;
    private final Supplier<LocalDate> valuationDate;

    /** TEST-FIXTURE / legacy mode: quotes are used AS zero rates (no bootstrap). The app wires
     *  the bootstrapping constructor — see ADR-0041. */
    public CurveService() {
        this(false, LocalDate::now);
    }

    /** @param bootstrap true = calibrate zeros so each tenor's Strata par rate reproduces its
     *                   quote (ADR-0041 — the production wiring); false = quotes AS zeros.
     *  @param valuationDate the day OIS schedules are built for (session day in the app). */
    public CurveService(boolean bootstrap, Supplier<LocalDate> valuationDate) {
        this.bootstrap = bootstrap;
        this.valuationDate = valuationDate != null ? valuationDate : LocalDate::now;
    }

    /** True if the id is a curve quote this service consumes. */
    public static boolean isCurveQuote(String instrumentId) {
        return instrumentId != null && instrumentId.startsWith("USD.SOFR.");
    }

    /** Accepts a tenor quote in percent (e.g. 4.215632 = 4.215632%). Unknown ids ignored. */
    public void onRate(String quoteId, BigDecimal ratePercent) {
        if (TENORS.containsKey(quoteId)) {
            latestRates.put(quoteId, ratePercent.doubleValue() / 100.0);
        }
    }

    /** The calibrated Strata curve, once every tenor has quoted; empty before that. */
    public Optional<Curve> curve() {
        return curveWithShiftBps(0.0);
    }

    /**
     * The curve with a parallel shift of {@code shiftBps} basis points — applied to the PAR
     * quotes and re-bootstrapped (a scenario shifts what the market quotes; ADR-0041), so
     * full-revaluation scenarios carry convexity in par space. In fixture mode the shift is
     * applied to the zeros directly (pre-ADR-0041 behaviour).
     */
    public Optional<Curve> curveWithShiftBps(double shiftBps) {
        if (latestRates.size() < TENORS.size()) {
            return Optional.empty();
        }
        double shift = shiftBps / 10_000.0;
        double[] tenors = TENORS.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] quotes = new double[tenors.length];
        int i = 0;
        for (double t : tenors) {
            quotes[i++] = rateForTenor(t) + shift;
        }
        if (!bootstrap) {
            return Optional.of(zeroCurve(tenors, quotes));
        }
        return Optional.of(calibrate(tenors, quotes));
    }

    /**
     * Fixed-point bootstrap: start from par-as-zero, then repeat {@code z_i += quote_i − par_i}
     * with par rates Strata-priced on the candidate curve, until every tenor reproduces its
     * quote within {@link #TOL}. The par→level map is near-identity, so convergence is
     * geometric (typically < 10 rounds). Non-convergence falls back to par-as-zero, disclosed.
     */
    private Curve calibrate(double[] tenors, double[] quotes) {
        LocalDate valDate = valuationDate.get();
        double[] zeros = quotes.clone();
        ResolvedSwap[] swaps = new ResolvedSwap[tenors.length];
        for (int i = 0; i < tenors.length; i++) {
            // Fixed rate is irrelevant to parRate; notional 1M is a scale-free choice.
            swaps[i] = FixedOvernightSwapConventions.USD_FIXED_1Y_SOFR_OIS
                    .createTrade(valDate, Tenor.ofYears((int) tenors[i]), BuySell.BUY,
                            1_000_000, quotes[i], REF_DATA)
                    .getProduct().resolve(REF_DATA);
        }
        try {
            for (int round = 0; round < MAX_ITERATIONS; round++) {
                Curve candidate = zeroCurve(tenors, zeros);
                ImmutableRatesProvider provider = ImmutableRatesProvider.builder(valDate)
                        .discountCurve(Currency.USD, candidate)
                        .overnightIndexCurve(OvernightIndices.USD_SOFR, candidate)
                        .build();
                double worst = 0;
                for (int i = 0; i < tenors.length; i++) {
                    double par = DiscountingSwapProductPricer.DEFAULT.parRate(swaps[i], provider);
                    double miss = quotes[i] - par;
                    zeros[i] += miss;
                    worst = Math.max(worst, Math.abs(miss));
                }
                if (worst < TOL) {
                    return zeroCurve(tenors, zeros);
                }
            }
            log.warn("SOFR curve bootstrap did not converge in {} rounds — falling back to "
                    + "quote-as-zero for this build", MAX_ITERATIONS);
        } catch (RuntimeException e) {
            log.warn("SOFR curve bootstrap failed ({}) — falling back to quote-as-zero", e.toString());
        }
        return zeroCurve(tenors, quotes);
    }

    private static Curve zeroCurve(double[] tenors, double[] zeros) {
        return InterpolatedNodalCurve.of(
                Curves.zeroRates("USD-SOFR", DayCounts.ACT_365F),
                DoubleArray.copyOf(tenors), DoubleArray.copyOf(zeros),
                CurveInterpolators.LINEAR);
    }

    /** The curve's tenor node grid (years, sorted) — the DV01 bucket axis (quant-engine step 4). */
    public static double[] nodeTenorYears() {
        return TENORS.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
    }

    /** Zero rate at any tenor (interpolated), if the curve is available. */
    public Optional<Double> zeroRate(double tenorYears) {
        return curve().map(c -> c.yValue(tenorYears));
    }

    /** Continuous-compounding discount factor e^(−z·t), if the curve is available. */
    public Optional<Double> discountFactor(double tenorYears) {
        return zeroRate(tenorYears).map(z -> Math.exp(-z * tenorYears));
    }

    /** Current tenor points (CALIBRATED zeros + DFs), sorted; empty until all tenors quote. */
    public List<CurvePoint> snapshot() {
        Optional<Curve> built = curve();
        if (built.isEmpty()) {
            return List.of();
        }
        Curve c = built.get();
        List<CurvePoint> points = new ArrayList<>();
        TENORS.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(e -> {
                    double z = c.yValue(e.getValue());
                    points.add(new CurvePoint(e.getKey(), e.getValue(), z, Math.exp(-z * e.getValue())));
                });
        return points;
    }

    private double rateForTenor(double tenorYears) {
        for (Map.Entry<String, Double> e : TENORS.entrySet()) {
            if (e.getValue() == tenorYears) {
                return latestRates.get(e.getKey());
            }
        }
        throw new IllegalArgumentException("unknown tenor " + tenorYears);
    }
}

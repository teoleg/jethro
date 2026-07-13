package io.jethro.trading.riskpnl;

import com.opengamma.strata.basics.date.DayCounts;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.market.curve.Curve;
import com.opengamma.strata.market.curve.Curves;
import com.opengamma.strata.market.curve.InterpolatedNodalCurve;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolators;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live USD SOFR zero curve assembled from streamed tenor quotes (quant-engine phase 4).
 * The sim (or a real provider) publishes {@code USD.SOFR.<tenor>} marks quoted in
 * percent; this service holds the latest quote per tenor and builds a <b>Strata</b>
 * {@link InterpolatedNodalCurve} (zero rates, ACT/365F, linear interpolation) — the
 * analytics substrate, not hand-rolled curve math (ADR-0020). Swap PV/DV01 prices off
 * this curve next; discount factors are {@code e^(−z·t)} continuous compounding.
 *
 * <p>Rates/DFs are statistical market data, computed in double — never ledger money
 * (invariant 1 untouched).
 */
public final class CurveService {

    /** Tenor grid: quote id → tenor in years. */
    private static final Map<String, Double> TENORS = Map.of(
            "USD.SOFR.1Y", 1.0, "USD.SOFR.2Y", 2.0, "USD.SOFR.5Y", 5.0,
            "USD.SOFR.10Y", 10.0, "USD.SOFR.30Y", 30.0);

    public record CurvePoint(String quoteId, double tenorYears, double zeroRate, double discountFactor) {
    }

    private final Map<String, Double> latestRates = new ConcurrentHashMap<>();

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
     * The curve with a parallel shift of every zero rate by {@code shiftBps} basis points —
     * the input to full-revaluation scenarios (quant-engine step 2, second slice): re-pricing
     * a swap on this shocked curve captures convexity a first-order DV01 shock cannot.
     */
    public Optional<Curve> curveWithShiftBps(double shiftBps) {
        if (latestRates.size() < TENORS.size()) {
            return Optional.empty();
        }
        double shift = shiftBps / 10_000.0;
        double[] tenors = TENORS.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] rates = new double[tenors.length];
        int i = 0;
        for (double t : tenors) {
            rates[i++] = rateForTenor(t) + shift;
        }
        return Optional.of(InterpolatedNodalCurve.of(
                Curves.zeroRates("USD-SOFR", DayCounts.ACT_365F),
                DoubleArray.ofUnsafe(tenors), DoubleArray.ofUnsafe(rates),
                CurveInterpolators.LINEAR));
    }

    /** Zero rate at any tenor (interpolated), if the curve is available. */
    public Optional<Double> zeroRate(double tenorYears) {
        return curve().map(c -> c.yValue(tenorYears));
    }

    /** Continuous-compounding discount factor e^(−z·t), if the curve is available. */
    public Optional<Double> discountFactor(double tenorYears) {
        return zeroRate(tenorYears).map(z -> Math.exp(-z * tenorYears));
    }

    /** Current tenor points for the UI/API, sorted by tenor; empty until all tenors quote. */
    public List<CurvePoint> snapshot() {
        if (latestRates.size() < TENORS.size()) {
            return List.of();
        }
        List<CurvePoint> points = new ArrayList<>();
        TENORS.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(e -> {
                    double z = latestRates.get(e.getKey());
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

package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the fused target book (ADR-0055 phase 4), the pure heart of "combine all sources before any
 * order decision": for each instrument it weights the fresh per-source forecasts, combines them
 * (ForecastCombiner), sizes a deterministic target (TargetPlanner), and computes the Gârleanu-Pedersen
 * order delta against the current firm position. One combined target per name ⇒ the deltas are
 * inherently netted across every sleeve. Pure and testable; the caller decides whether a delta is
 * routed (shadow vs live) — this class never places an order (ADR-0016 / invariant 7).
 */
public final class FusionPlanner {

    private FusionPlanner() {
    }

    /** One source's contribution to a name's combined forecast. */
    public record Contribution(String source, double forecast, double weight) {
    }

    /** The fused plan for one instrument. All quantities signed (+ long / − short). */
    public record Target(String instrument, double combinedForecast, int sources, double diversificationMultiplier,
                        BigDecimal price, BigDecimal targetQty, BigDecimal currentQty, BigDecimal deltaQty,
                        List<Contribution> contributions) {
    }

    /** Tunables for one planning pass. */
    public record Params(double assumedCorrelation, BigDecimal unitNotional, double bufferFraction,
                        double adjustmentRate) {
    }

    /**
     * @param forecastsByInstrument fresh per-source forecasts (from {@link ForecastRegistry})
     * @param weightFor             source → weight (evidence-based; equal is the phase-4 placeholder)
     * @param priceFor              instrument → current mark (null/≤0 skips sizing for that name)
     * @param currentQtyFor         instrument → current firm position quantity
     */
    public static List<Target> plan(Map<String, List<Forecast>> forecastsByInstrument,
                                    Function<String, Double> weightFor,
                                    Function<String, BigDecimal> priceFor,
                                    Function<String, BigDecimal> currentQtyFor,
                                    Params params) {
        List<Target> out = new ArrayList<>();
        for (var entry : forecastsByInstrument.entrySet()) {
            String instrument = entry.getKey();
            List<ForecastCombiner.Weighted> weighted = new ArrayList<>();
            List<Contribution> contributions = new ArrayList<>();
            for (Forecast f : entry.getValue()) {
                double w = Math.max(0, weightFor.apply(f.source()));
                weighted.add(new ForecastCombiner.Weighted(f, w));
                contributions.add(new Contribution(f.source(), f.value(), w));
            }
            ForecastCombiner.Combined combined =
                    ForecastCombiner.combine(instrument, weighted, params.assumedCorrelation());
            BigDecimal price = priceFor.apply(instrument);
            BigDecimal current = currentQtyFor.apply(instrument);
            if (current == null) {
                current = BigDecimal.ZERO;
            }
            BigDecimal target = TargetPlanner.targetQuantity(combined.value(), params.unitNotional(), price);
            BigDecimal delta = TargetPlanner.orderDelta(target, current, params.bufferFraction(), params.adjustmentRate());
            out.add(new Target(instrument, combined.value(), combined.activeSources(),
                    combined.diversificationMultiplier(), price, target, current, delta, contributions));
        }
        out.sort((a, b) -> Double.compare(Math.abs(b.combinedForecast()), Math.abs(a.combinedForecast())));
        return out;
    }
}

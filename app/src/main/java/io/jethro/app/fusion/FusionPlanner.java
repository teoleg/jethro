package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the fused target book (ADR-0055 phase 4), the pure heart of "combine all sources before any
 * order decision": for each instrument it weights the fresh per-source forecasts, combines them
 * (ForecastCombiner), sizes a deterministic target (TargetPlanner), and computes the Gârleanu-Pedersen
 * order delta against the position held in the books this layer routes into (ADR-0091 — the hedge
 * overlay is not one of them). One combined target per name ⇒ the deltas are inherently netted across
 * every strategy sleeve. Pure and testable; the caller decides whether a delta is
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

    /**
     * Tunables for one planning pass.
     *
     * <p>{@code adjustmentRate} at or below zero means DERIVE: the loop sets it per cycle from the
     * horizon the evidence selected, so the desk holds a view for exactly as long as its edge was
     * measured over (ADR-0080's identity at ADR-0082's horizon). A positive value pins it.
     */
    public record Params(double assumedCorrelation, BigDecimal unitNotional, double bufferFraction,
                        double adjustmentRate) {

        /** The same tunables at a different partial-adjustment rate. */
        public Params withAdjustmentRate(double rate) {
            return new Params(assumedCorrelation, unitNotional, bufferFraction, rate);
        }
    }

    /**
     * @param forecastsByInstrument fresh per-source forecasts (from {@link ForecastRegistry})
     * @param heldInstruments       names the desk currently HOLDS in a book this layer routes into.
     *                              Planned even with no fresh forecast — see below — so a position
     *                              whose sources have gone silent still gets a target (ADR-0065).
     * @param weightFor             source → weight (evidence-based; equal is the phase-4 placeholder)
     * @param priceFor              instrument → current mark (null/≤0 skips sizing for that name)
     * @param multiplierFor         instrument → contract multiplier from the instrument master
     *                              (ADR-0078). Cash-at-risk becomes a quantity only after dividing by
     *                              the money value of one unit, {@code price × multiplier}; null/≤0
     *                              means the contract spec is unknown and the name is planned flat
     *                              rather than sized as if it were a share.
     * @param currentQtyFor         instrument → the quantity currently held in the books this layer
     *                              ROUTES INTO (ADR-0091). This is what the gap is measured against,
     *                              so it must be inventory the desk can actually trade: a position
     *                              held by the hedge overlay is not, and counting it makes the planner
     *                              open an equal and opposite leg in a strategy book every time the
     *                              hedger acts. See {@code FusionConfig.routedBookPositions}.
     */
    public static List<Target> plan(Map<String, List<Forecast>> forecastsByInstrument,
                                    Collection<String> heldInstruments,
                                    Function<String, Double> weightFor,
                                    Function<String, BigDecimal> priceFor,
                                    Function<String, BigDecimal> multiplierFor,
                                    Function<String, BigDecimal> currentQtyFor,
                                    Params params) {
        // ADR-0065: the target book spans {names with a view} ∪ {names we hold}. Planning only the
        // first set is what orphans a position: when its sources fall silent or the OOS selector
        // drops the name, it vanishes from the cross-section and nothing ever revisits it. A held
        // name with no fresh view has an implicit target of ZERO — no view, no position — and is
        // worked down by the same partial adjustment as any other target. Held names are added
        // AFTER the forecast set so a name with a view keeps its (unchanged) treatment.
        Map<String, List<Forecast>> spanned = new LinkedHashMap<>(forecastsByInstrument);
        if (heldInstruments != null) {
            for (String held : heldInstruments) {
                if (held != null) {
                    spanned.putIfAbsent(held, List.of());
                }
            }
        }
        List<Target> out = new ArrayList<>();
        for (var entry : spanned.entrySet()) {
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
            BigDecimal multiplier = multiplierFor == null ? null : multiplierFor.apply(instrument);
            BigDecimal target = TargetPlanner.targetQuantity(combined.value(), params.unitNotional(),
                    price, multiplier);
            BigDecimal delta = TargetPlanner.orderDelta(target, current, params.bufferFraction(), params.adjustmentRate());
            out.add(new Target(instrument, combined.value(), combined.activeSources(),
                    combined.diversificationMultiplier(), price, target, current, delta, contributions));
        }
        out.sort((a, b) -> Double.compare(Math.abs(b.combinedForecast()), Math.abs(a.combinedForecast())));
        return out;
    }
}

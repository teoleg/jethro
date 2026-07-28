package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Caps the fused book's ex-ante volatility at the book's OWN typical planned volatility (ADR-0104) —
 * the absolute risk anchor the desk has never had.
 *
 * <h2>The gap this closes</h2>
 * Every size control upstream is <em>relative</em>. {@link VolatilityBudget} (ADR-0083) redistributes
 * the per-name cash budget across names and is budget-neutral by construction;
 * {@link PortfolioRiskNormaliser} (ADR-0079) scales the book by how much of it is one bet, capped at 1.
 * Neither says how much risk the BOOK should carry: the level falls out of
 * {@code unit-notional-usd × (how many names happen to pass the gate) × (how strong their forecasts
 * happen to be)}. Both of those vary cycle to cycle, so the book's planned risk — and therefore the
 * gross exposure the desk's PnL is earned per unit of — is an accident of the cross-section rather than
 * a decision. That is why measured gross has swung several-fold between consecutive evaluations while
 * nothing about the desk's appetite changed.
 *
 * <h2>The brake</h2>
 * Over the names {@code C} the measured covariance {@code Σ} covers, with signed USD notionals
 * {@code eᵢ} — the same construction, coverage rules and money boundary as
 * {@link PortfolioRiskNormaliser}:
 * <pre>
 *   σ_planned = √( Σᵢ Σⱼ eᵢ eⱼ Σᵢⱼ )        this cycle's book, at its measured correlation
 *   σ_ref     = median{ σ_planned }          over the last {@code span} planning cycles, this one included
 *   brake     = min(1, σ_ref / σ_planned)
 * </pre>
 * and every covered target is multiplied by {@code brake}.
 *
 * <p><b>The reference is not a number anybody chose.</b> It is the median of the desk's own planned-risk
 * series, measured on the stream it is trading — so the control self-calibrates to any feed's volatility
 * and introduces no money/risk figure (invariant 7 / ADR-0016). What it asserts is only this: the desk
 * does not carry more risk than it typically carries. Volatility targeting in exactly this shape — hold
 * risk constant, scale the book down when its own risk runs hot — is the standard result of Harvey,
 * Hoyle, Rattray, Sargaison &amp; van Hemert ("The Impact of Volatility Targeting", <i>JPM</i> 2018) and
 * Moreira &amp; Muir ("Volatility-Managed Portfolios", <i>JF</i> 2017): returns are not proportional to
 * risk across risk states, so trimming the hot states raises return per unit of risk.
 *
 * <h2>Properties (all exact, all provable)</h2>
 * <ul>
 *   <li><b>Never grows the book.</b> {@code min(1, ·)} discards the case where planned risk sits below
 *       its own median. One-way, like ADR-0076 / ADR-0079 / ADR-0083 / ADR-0086 before it: an estimated
 *       covariance can shrink the desk but can never lever it up.</li>
 *   <li><b>Exactly on target when it binds.</b> σ is homogeneous of degree 1 in {@code e}, so scaling
 *       every covered notional by {@code σ_ref/σ_planned} leaves the covered book at σ_ref precisely.</li>
 *   <li><b>Sign- and view-preserving.</b> The multiplier is positive and uniform, so no name flips side
 *       and the cross-sectional shape of the forecast is untouched. This is a size control, not a view.</li>
 *   <li><b>No ratchet.</b> The series sampled is the RAW planned σ arriving at this step, never the
 *       braked one, so a cycle that was cut cannot drag the reference down and cut the next one further.</li>
 *   <li><b>Silent when it cannot measure.</b> Fewer than {@code minSample} observations, an incomplete
 *       covariance, or a degenerate σ all leave the book byte-identical — no measurement, no claim.</li>
 * </ul>
 *
 * <h2>What it deliberately does NOT do</h2>
 * It caps planned risk; it does not cut a position that has gone wrong. That is ADR-0086's trailing exit,
 * which runs after this and is the only step allowed to set a target flat. Nor is it a floor: a book the
 * cross-section leaves quiet stays quiet, because there is nothing here that knows a bigger book would be
 * a better one.
 *
 * <p>Stateful (the rolling σ series) and touched only from the fusion tick thread, exactly like
 * {@link StreamVolatility} and {@link StreamCovariance}. Runs on the 30s fusion cadence, never on the
 * tick path.
 */
public final class BookVolatilityBrake {

    /** Quantity scale of the fusion order path — the same one {@link TargetPlanner} sizes at. */
    private static final int QTY_SCALE = 6;

    /**
     * @param span      how many planning cycles the reference median is taken over — an estimation
     *                  window in samples, not a money/risk/exposure number, and the control is
     *                  scale-invariant in σ so the window cannot move a size by itself
     * @param minSample observations required before the brake may speak at all
     */
    public record Params(int span, int minSample) {
    }

    /** The braked book plus the disclosure of what was measured to brake it. */
    public record Result(List<FusionPlanner.Target> targets, double multiplier,
                         Double plannedSigmaUsd, Double referenceSigmaUsd,
                         int coveredNames, int samples) {
    }

    private final int span;
    private final int minSample;
    /** Raw (pre-brake) planned σ, oldest first, bounded by {@code span}. */
    private final Deque<Double> history = new ArrayDeque<>();

    public BookVolatilityBrake(Params params) {
        this.span = Math.max(2, params == null ? 2 : params.span());
        this.minSample = Math.max(2, params == null ? 2 : params.minSample());
    }

    /**
     * Measure this cycle's planned σ, fold it into the reference series, and scale the covered book back
     * to the reference when it runs above it.
     *
     * @param targets       the planned book as it arrives from {@link PortfolioRiskNormaliser}
     * @param multiplierFor instrument → contract multiplier (ADR-0078): a target quantity is only a USD
     *                      notional after {@code × price × multiplier}
     * @param covariance    the measured return covariance; {@link ReturnCovarianceSource#NONE} leaves
     *                      the book untouched
     * @param params        the planning dials the recomputed delta must respect
     */
    public Result apply(List<FusionPlanner.Target> targets,
                        Function<String, BigDecimal> multiplierFor,
                        ReturnCovarianceSource covariance,
                        FusionPlanner.Params params) {
        List<FusionPlanner.Target> book = targets == null ? List.of() : targets;
        if (book.isEmpty() || covariance == null) {
            return new Result(book, 1.0, null, null, 0, history.size());
        }
        List<String> covered = new ArrayList<>();
        List<Double> notionals = new ArrayList<>();
        for (FusionPlanner.Target t : book) {
            if (t.targetQty() == null || t.targetQty().signum() == 0 || t.price() == null) {
                continue; // a flat target carries no risk to measure
            }
            if (covariance.covariance(t.instrument(), t.instrument()).isEmpty()) {
                continue; // uncovered — no measurement, no claim (ADR-0016 / invariant 7)
            }
            BigDecimal mult = multiplierFor == null ? null : multiplierFor.apply(t.instrument());
            if (mult == null || mult.signum() <= 0) {
                continue; // no contract spec ⇒ no USD notional can be asserted
            }
            covered.add(t.instrument());
            // Signed USD notional: quantity × price × contract multiplier — the same arithmetic
            // PositionRisk values the resulting exposure with (ADR-0078). Money is exact up to here; it
            // enters the statistic as a double exactly where CovMath and ADR-0079 already draw that line.
            notionals.add(t.targetQty().multiply(t.price()).multiply(mult).doubleValue());
        }
        double planned = sigma(covered, notionals, covariance);
        if (!(planned > 0)) {
            return new Result(book, 1.0, null, null, covered.size(), history.size());
        }
        record(planned);
        int samples = history.size();
        if (samples < minSample) {
            return new Result(book, 1.0, planned, null, covered.size(), samples);
        }
        double reference = median();
        double brake = Math.min(1.0, reference / planned);
        if (!(brake > 0) || brake >= 1.0) {
            return new Result(book, 1.0, planned, reference, covered.size(), samples);
        }
        BigDecimal scale = BigDecimal.valueOf(brake);
        List<FusionPlanner.Target> out = new ArrayList<>(book.size());
        for (FusionPlanner.Target t : book) {
            if (!covered.contains(t.instrument())) {
                out.add(t); // unmeasured — left exactly as planned
                continue;
            }
            BigDecimal target = t.targetQty().multiply(scale).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
            BigDecimal delta = TargetPlanner.orderDelta(target, t.currentQty(),
                    params.bufferFraction(), params.adjustmentRate());
            out.add(new FusionPlanner.Target(t.instrument(), t.combinedForecast(), t.sources(),
                    t.diversificationMultiplier(), t.price(), target, t.currentQty(), delta,
                    t.contributions()));
        }
        return new Result(out, brake, planned, reference, covered.size(), samples);
    }

    /** The book's daily σ in USD at its measured correlation; 0 when the matrix cannot be aggregated. */
    static double sigma(List<String> instruments, List<Double> notionals, ReturnCovarianceSource cov) {
        int n = instruments.size();
        if (n == 0) {
            return 0;
        }
        double variance = 0;
        for (int i = 0; i < n; i++) {
            double ei = notionals.get(i);
            for (int j = 0; j < n; j++) {
                OptionalDouble sij = cov.covariance(instruments.get(i), instruments.get(j));
                if (sij.isEmpty()) {
                    return 0; // an incomplete matrix cannot be aggregated — make no claim
                }
                variance += ei * notionals.get(j) * sij.getAsDouble();
            }
        }
        return variance > 0 ? Math.sqrt(variance) : 0;
    }

    private void record(double planned) {
        history.addLast(planned);
        while (history.size() > span) {
            history.removeFirst();
        }
    }

    /** Median of the retained series — the mean of the two central order statistics on an even count. */
    private double median() {
        double[] sorted = history.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /** Observations retained — the operator's read on whether the brake is warm. */
    public int samples() {
        return history.size();
    }
}

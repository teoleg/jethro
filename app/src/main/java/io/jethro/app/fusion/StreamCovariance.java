package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Pairwise return covariance measured from the MARK STREAM (ADR-0089) — the correlation input the
 * fusion sizing path needs for the names it actually plans.
 *
 * <h2>Why a second covariance estimator</h2>
 * {@link PortfolioRiskNormaliser} (ADR-0079) and {@link VolatilityBudget} (ADR-0083) are the only two
 * controls that ask how much of the target book is <em>one bet repeated</em> and how unequal its
 * standalone risks are. Both read a {@link ReturnCovarianceSource} built from the {@code daily_close}
 * series, and an instrument only enters that estimate once it has accumulated enough admissible
 * consecutive sessions <em>in the running feed mode</em> (ADR-0073). A session's worth of closes is one
 * point; two are one return. So on a stream that has been running for a couple of sessions the daily
 * estimate covers nothing at all — the parametric VaR built on the same covariance reports its entire
 * exposure as skipped — and both controls degrade to their documented "no measurement, no claim"
 * fallback, which is to leave the book exactly as planned. A desk that plans every name on the same
 * side then carries N independent per-name budgets of what is arithmetically one position, and the
 * control designed to stop precisely that is silent.
 *
 * <p>This is the same coverage gap ADR-0086 hit for σ and answered the same way: the mark stream is the
 * ~1 Hz series every forecast sensor already consumes, warm for every name in the universe, and it is
 * durable ({@link SensorWarmup} replays it across restarts). {@link StreamVolatility} measures a name's
 * own σ from it; this measures the pairs.
 *
 * <h2>What it measures</h2>
 * Sampled at a FIXED cadence — one SYNCHRONISED snapshot of every name's mark per call, so the returns
 * entering a pair are contemporaneous by construction — the EWMA of return cross-products:
 * <pre>
 *   rᵢ    = ln(pᵢ,ₜ / pᵢ,ₜ₋₁)                      one interval's log return
 *   cᵢⱼ  ← (1−α)·cᵢⱼ + α·rᵢ·rⱼ     α = 2/(span+1)
 * </pre>
 * with {@code cᵢᵢ} the variance of an interval return, exactly as {@link StreamVolatility} computes it.
 * As there, the estimator runs as a plain running mean until it has absorbed {@code span} joint returns
 * and only then decays, and it reports nothing at all until then.
 *
 * <h2>Units — and why they cannot change a decision</h2>
 * The unit here is the variance of a return over ONE sampling interval, not over a day. That is a
 * different number from the daily-close estimate it stands in for, and it is deliberately NOT rescaled,
 * because rescaling would require asserting how many sampling intervals make a trading day — a
 * convention this class has no business inventing (a continuous synthetic stream and a cash equity
 * session disagree, and the answer would silently move a risk number). It is safe to leave alone
 * because <b>both consumers are homogeneous of degree zero in Σ</b>:
 * <ul>
 *   <li>{@link PortfolioRiskNormaliser}: {@code PDM = √(Σᵢeᵢ²Σᵢᵢ) / √(ΣᵢΣⱼeᵢeⱼΣᵢⱼ)} — multiply every
 *       Σᵢⱼ by any k &gt; 0 and both roots scale by √k, so the ratio is unchanged.</li>
 *   <li>{@link VolatilityBudget}: {@code kᵢ = σ_ref/σ̃ᵢ} with σ_ref the harmonic mean of the same
 *       cross-section, and the winsorisation that precedes it is rank-based; scaling every σ by √k
 *       scales σ_ref by √k and leaves every kᵢ, the dispersion and the lever cap unchanged.</li>
 * </ul>
 * So substituting this estimator changes <em>what is measured</em> and never the units the answer is
 * read in. Nothing downstream consumes an absolute σ from this class.
 *
 * <h2>Safety</h2>
 * Nothing here is money, a size or a price: a covariance is a dimensionless statistic and leaves as a
 * {@code double} at exactly the boundary {@link ReturnCovarianceSource} already draws (invariant 1 —
 * prices arrive as exact decimal and only the ratio becomes a double). It sizes nothing and gates
 * nothing on its own (ADR-0016 / invariant 7); its two consumers are both ONE-WAY — PDM is capped at 1
 * and the vol budget carries a gross cap of 1 — so an error in this estimate can only ever make the
 * desk carry LESS exposure than it planned, never more. Not thread-safe: confined to the fusion tick.
 */
public final class StreamCovariance {

    /** Enough precision for a price ratio; the result is a dimensionless statistic, never money. */
    private static final MathContext RATIO = MathContext.DECIMAL64;

    /**
     * The EWMA span, in joint samples — a statistical convention, not a money, risk or exposure number.
     * It sets how much history the estimate remembers and, with it, how many synchronised samples a
     * pair must be seen in before it is allowed to speak.
     */
    public record Params(int span) {
        public Params {
            if (span < 2) {
                span = 2; // a (co)variance needs at least two returns to mean anything
            }
        }

        /** The standard EWMA smoothing factor for a span of {@code span} observations. */
        public double alpha() {
            return 2.0 / (span + 1.0);
        }
    }

    /** Canonically ordered instrument pair, so {@code (a,b)} and {@code (b,a)} are one state. */
    private record Pair(String a, String b) {
        static Pair of(String x, String y) {
            return x.compareTo(y) <= 0 ? new Pair(x, y) : new Pair(y, x);
        }
    }

    private static final class State {
        private double value;   // EWMA of rᵢ·rⱼ once warm; running mean of it while warming
        private double sum;     // Σ rᵢ·rⱼ over the warm-up samples, for that running mean
        private long samples;   // how many JOINT returns this pair has absorbed
    }

    private final Params params;
    private final Map<String, BigDecimal> lastPrice = new HashMap<>();
    private final Map<Pair, State> pairs = new HashMap<>();

    public StreamCovariance(Params params) {
        this.params = params == null ? new Params(120) : params;
    }

    /** Joint samples a pair must be seen in before its covariance is allowed to speak. */
    public int warmupSamples() {
        return params.span();
    }

    /**
     * Absorb ONE synchronised sample: every name's mark at a single instant, taken at the caller's
     * fixed cadence. Only names present in both this sample and the previous one produce a return, and
     * only pairs of such names are updated — so a name that joins late, drops out for a cycle, or
     * carries a missing/non-positive price simply contributes nothing rather than injecting a
     * fabricated zero return, which would bias the estimate toward "these names do not move together"
     * and so toward NOT cutting the book.
     *
     * <p>Prices arrive exact and only their ratio becomes a {@code double} (invariant 1).
     */
    public void update(Map<String, BigDecimal> sample) {
        if (sample == null || sample.isEmpty()) {
            return;
        }
        Map<String, Double> returns = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : sample.entrySet()) {
            String id = e.getKey();
            BigDecimal price = e.getValue();
            if (id == null || price == null || price.signum() <= 0) {
                continue;
            }
            BigDecimal previous = lastPrice.put(id, price);
            if (previous == null || previous.signum() <= 0) {
                continue; // first sight: a price, not yet a return
            }
            double r = Math.log(price.divide(previous, RATIO).doubleValue());
            if (Double.isFinite(r)) {
                returns.put(id, r);
            }
        }
        if (returns.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>(returns.keySet());
        double alpha = params.alpha();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i; j < names.size(); j++) {
                double product = returns.get(names.get(i)) * returns.get(names.get(j));
                State s = pairs.computeIfAbsent(Pair.of(names.get(i), names.get(j)), k -> new State());
                s.samples++;
                if (s.samples <= params.span()) {
                    s.sum += product;
                    s.value = s.sum / s.samples; // running mean while warming — never a 1-sample anchor
                } else {
                    s.value = (1.0 - alpha) * s.value + alpha * product;
                }
            }
        }
    }

    /**
     * Σ(a,b) over one sampling interval's log returns; empty while the pair is still warming, or when
     * either name has never been seen. The caller must then make NO claim about that pair, exactly as
     * {@link ReturnCovarianceSource} requires (ADR-0016 / invariant 7).
     *
     * <p>A variance is additionally required to be strictly positive: a name measured as never moving
     * would divide the vol budget by zero, and "no dispersion measured yet" is a warm-up statement, not
     * a risk statement.
     */
    public OptionalDouble covariance(String a, String b) {
        if (a == null || b == null) {
            return OptionalDouble.empty();
        }
        State s = pairs.get(Pair.of(a, b));
        if (s == null || s.samples < params.span() || !Double.isFinite(s.value)) {
            return OptionalDouble.empty();
        }
        if (a.equals(b) && !(s.value > 0)) {
            return OptionalDouble.empty(); // a degenerate variance is not a measurement
        }
        return OptionalDouble.of(s.value);
    }

    /** This estimator as the narrow read interface the fusion sizing controls consume. */
    public ReturnCovarianceSource asSource() {
        return this::covariance;
    }

    /**
     * How many of {@code instruments} currently carry a usable variance — the coverage disclosure the
     * caller compares estimators on, and an operator-facing number only. Never an input to a size.
     */
    public int measuredNames(Iterable<String> instruments) {
        int n = 0;
        for (String id : instruments) {
            if (covariance(id, id).isPresent()) {
                n++;
            }
        }
        return n;
    }

    /** True once this name has been seen at all — used to decide whether the seed still owes it. */
    public boolean seen(String instrumentId) {
        return instrumentId != null && lastPrice.containsKey(instrumentId);
    }
}

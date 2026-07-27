package io.jethro.app.hedge;

import io.jethro.app.fusion.SensorWarmup;
import io.jethro.app.fusion.StreamCovariance;
import io.jethro.trading.riskpnl.CovMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

/**
 * The return covariance of the hedge axis measured on the MARK STREAM (ADR-0095) — the evidence the
 * hedge advisor needs to size on a measured β and to know whether its proxy hedges this book at all.
 *
 * <h2>Why the hedge needs a second estimator</h2>
 * {@link HedgeAdvisor}'s statistical tier (ADR-0038) reads the {@code daily_close} EWMA covariance,
 * and a name only enters that estimate after enough admissible consecutive sessions <em>in the
 * running feed mode</em> (ADR-0073). On a stream a few sessions old it covers nothing the desk
 * actually holds — the parametric VaR built on the same matrix reports its entire exposure as
 * skipped — so {@link io.jethro.trading.riskpnl.HedgeMath#betaHedge} returns empty for every
 * candidate, the ADR-0042 proxy selection never runs, and the ρ² effectiveness floor <b>can never
 * fire</b>. The hedge falls to the ADR-0040 structural tier and sizes itself, forever, from assigned
 * fundamental betas whose fit to the stream is never checked. This is the same coverage gap ADR-0089
 * found and answered for the fusion sizing controls, in the one consumer it did not reach.
 *
 * <h2>What it measures — and why the sampling period cannot change a hedge</h2>
 * The estimator is ADR-0089's {@link StreamCovariance}: one SYNCHRONISED snapshot of every name's
 * mark per hedge cycle, EWMA of log-return cross-products, nothing reported until a pair is warm.
 * Its unit is the (co)variance of a return over ONE sampling interval, not over a day, and — exactly
 * as ADR-0089 argued — it is deliberately not rescaled, because rescaling would require asserting how
 * many intervals make a trading day.
 *
 * <p>That is admissible here because <b>both quantities the advisor takes from Σ are homogeneous of
 * degree zero in Σ</b>. With book exposures {@code Eᵢ} and proxy {@code F}:
 * <pre>
 *   E_F* = −(Σᵢ Eᵢ·Σ[i,F]) / Σ[F,F]                     the minimum-variance hedge notional
 *   ρ²   = (Σᵢ Eᵢ·Σ[i,F])² / ((ΣᵢΣⱼ EᵢEⱼΣ[i,j])·Σ[F,F])   its measured effectiveness
 * </pre>
 * Replace Σ by k·Σ for any k &gt; 0: the numerator and denominator of {@code E_F*} both scale by k,
 * and ρ²'s numerator scales by k² against a denominator that also scales by k² — so both are
 * unchanged. The hedge quantity and the floor test do not depend on the period the covariance was
 * measured over.
 *
 * <p>The two figures that are NOT scale-free are {@code grossSigmaUsd = √Var(P&L)} and
 * {@code residualSigmaUsd}: those are absolute USD σ <em>per sampling interval</em>, and the advisor
 * therefore reports them as absent on a stream-sourced proposal rather than mislabel a per-interval σ
 * as a daily one. They are display-only; nothing sizes off them.
 *
 * <h2>Warm restart</h2>
 * A pair must be seen in {@code span} synchronised samples before it speaks, which is far longer than
 * this process tends to live. So on first sight the estimator is seeded from the durable mark history
 * on one shared bucket grid ({@link SensorWarmup#jointSeedSamples}, ADR-0071/0089) — a covariance of
 * returns taken at different instants measures the misalignment, so the seed is always replayed for
 * the whole cross-section at once, and re-run whenever that cross-section grows.
 *
 * <h2>Safety</h2>
 * A covariance is a dimensionless statistic and leaves as a {@code double} at exactly the boundary
 * {@link CovMath.Covariance} already draws (invariant 1 — prices arrive exact and only their ratio
 * becomes a double). Nothing here is money, a size or a price. A pair whose covariance is not
 * measured is never filled in with a zero — a fabricated zero would read as "these names do not move
 * together" and quietly bias ρ² — so a name is admitted to the matrix only when its variance and
 * every pair with an already-admitted name are measured.
 *
 * <p>Updates are confined to the hedge cycle's single thread; the published matrix is immutable and
 * replaced wholesale, so the read-only REST panel can consume it from any thread.
 */
public final class HedgeStreamCovariance {

    private static final Logger log = LoggerFactory.getLogger(HedgeStreamCovariance.class);

    private final StreamCovariance estimator;
    private final SensorWarmup.History markHistory;
    private final Function<String, Long> markTimeFor;
    private final long intervalMillis;
    private final Set<String> seeded = new HashSet<>();

    /** Synchronised samples absorbed so far — the estimator's own observation unit (intervals, not
     *  days), carried on the published matrix purely so the advisor and the panel can report it. */
    private long samples;

    private volatile Optional<CovMath.Covariance> published = Optional.empty();

    /**
     * @param estimator     the ADR-0089 mark-stream covariance; null disables the whole tier
     * @param markHistory   durable mark series for the warm-restart seed; null = cold start
     * @param markTimeFor   PROVIDER timestamp of a name's current mark — the seed anchor. Wall clock
     *                      would silently empty the seed on a delayed, replayed or simulated feed
     *                      (the ADR-0071 correction: one clock only, the feed's).
     * @param intervalMillis the sampling cadence — the hedge cycle, the grid the seed is bucketed on
     */
    public HedgeStreamCovariance(StreamCovariance estimator, SensorWarmup.History markHistory,
                                 Function<String, Long> markTimeFor, long intervalMillis) {
        this.estimator = estimator;
        this.markHistory = markHistory;
        this.markTimeFor = markTimeFor;
        this.intervalMillis = Math.max(1L, intervalMillis);
    }

    /** The matrix as last published — safe to read from any thread; empty while warming. */
    public Optional<CovMath.Covariance> snapshot() {
        return published;
    }

    /**
     * Absorb one synchronised snapshot of {@code names} and republish the matrix.
     *
     * @param names   the hedge axis members plus every proxy candidate — the proxy must be in the
     *                same sample as the book, or there is no {@code Σ[i,F]} to hedge with
     * @param priceOf the live mark per name; a name with no price simply contributes nothing this
     *                cycle rather than injecting a fabricated return
     */
    public void observe(Collection<String> names, Function<String, Optional<BigDecimal>> priceOf) {
        if (estimator == null || names == null || names.isEmpty()) {
            return;
        }
        Set<String> universe = new LinkedHashSet<>(names);
        seed(universe);
        Map<String, BigDecimal> sample = new LinkedHashMap<>();
        for (String id : universe) {
            BigDecimal price = priceOf.apply(id).orElse(null);
            if (price != null && price.signum() > 0) {
                sample.put(id, price);
            }
        }
        if (sample.isEmpty()) {
            return;
        }
        estimator.update(sample);
        samples++;
        published = matrix(universe);
    }

    /**
     * The measured names as a covariance matrix, admitting a name only when its own variance and its
     * covariance with every already-admitted name are measured — so no cell is ever fabricated.
     * Fewer than two admitted names is not a hedge input (there is no book-vs-proxy pair), and the
     * advisor is left with nothing rather than a partial claim.
     */
    private Optional<CovMath.Covariance> matrix(Collection<String> universe) {
        List<String> admitted = new ArrayList<>();
        for (String id : universe) {
            if (estimator.covariance(id, id).isEmpty()) {
                continue;
            }
            boolean complete = true;
            for (String other : admitted) {
                if (estimator.covariance(id, other).isEmpty()) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                admitted.add(id);
            }
        }
        if (admitted.size() < 2) {
            return Optional.empty();
        }
        int n = admitted.size();
        double[][] sigma = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                OptionalDouble c = estimator.covariance(admitted.get(i), admitted.get(j));
                if (c.isEmpty()) {
                    return Optional.empty(); // admission guarantees this, but never guess a cell
                }
                sigma[i][j] = c.getAsDouble();
                sigma[j][i] = sigma[i][j];
            }
        }
        return Optional.of(new CovMath.Covariance(List.copyOf(admitted), sigma, (int) Math.min(samples,
                Integer.MAX_VALUE)));
    }

    /** Replay the durable mark history as synchronised snapshots, once per cross-section growth. */
    private void seed(Collection<String> universe) {
        if (markHistory == null || seeded.containsAll(universe)) {
            return;
        }
        seeded.addAll(universe);
        Long providerMillis = null;
        for (String id : universe) {
            Long t = markTimeFor == null ? null : markTimeFor.apply(id);
            if (t != null && t > 0 && (providerMillis == null || t > providerMillis)) {
                providerMillis = t; // newest provider stamp on the axis — the feed's clock, not ours
            }
        }
        long anchor = providerMillis != null ? providerMillis : System.currentTimeMillis();
        var replay = SensorWarmup.jointSeedSamples(markHistory, universe, anchor, intervalMillis,
                estimator.warmupSamples());
        for (var s : replay) {
            estimator.update(s);
            samples++;
        }
        published = matrix(universe);
        int measured = estimator.measuredNames(universe);
        if (measured == 0) {
            // WARN, not INFO: with nothing measured the ρ² floor cannot fire and the hedge is back to
            // sizing itself from assigned betas — the exact failure this seed exists to prevent.
            log.warn("hedge covariance still cold after seeding {} synchronised snapshots of {} name(s)"
                    + " — the hedge cannot measure its own effectiveness until the mark history"
                    + " accumulates (ADR-0095)", replay.size(), universe.size());
        } else {
            log.info("hedge covariance warmed {} of {} name(s) from {} synchronised snapshots (ADR-0095)",
                    measured, universe.size(), replay.size());
        }
    }
}

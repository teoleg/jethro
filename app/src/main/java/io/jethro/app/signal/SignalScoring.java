package io.jethro.app.signal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure, exactly-testable scoring for per-signal health telemetry (ADR-0055, phase 1). A source's live
 * directional call is scored by the realised forward return of FOLLOWING it: buy signal → the return;
 * sell signal → its negative. Rolling hit-rate and average directional return per source are the
 * evidence the ADR-0055 fusion layer will weight sources by — a decayed source gets down-weighted by
 * measurement, not argument.
 *
 * <p>These are dimensionless analytics (returns/ratios), never a number into positions/PnL/risk
 * (ADR-0016 / invariant 7): telemetry measures the signals, it does not size or gate anything. Phase 1
 * sources emit a plain direction (±1), so hit-rate is the honest metric; a graded information
 * coefficient (rank-correlation of forecast magnitude vs return) arrives with the graded forecasts in
 * ADR-0055 phase 2.
 */
public final class SignalScoring {

    private SignalScoring() {
    }

    public enum Outcome { WIN, LOSS, FLAT }

    /** Return of following a {@code direction} (∈ {-1,0,+1}) call from {@code entry} to {@code exit}. */
    public static double directionalReturn(int direction, BigDecimal entry, BigDecimal exit) {
        if (direction == 0 || entry == null || exit == null || entry.signum() <= 0) {
            return 0.0;
        }
        double raw = exit.doubleValue() / entry.doubleValue() - 1.0;
        return direction > 0 ? raw : -raw;
    }

    /** WIN/LOSS/FLAT: a move counts only if it clears {@code flatThresholdBps}, else it is noise. */
    public static Outcome outcome(double directionalReturn, double flatThresholdBps) {
        double th = Math.abs(flatThresholdBps) / 1e4;
        return directionalReturn > th ? Outcome.WIN : (directionalReturn < -th ? Outcome.LOSS : Outcome.FLAT);
    }

    /**
     * Rolling health for one source <b>at one measurement horizon</b>; {@code open} (unresolved) is
     * filled by the caller.
     *
     * <p>{@code stdReturnBps} is the SAMPLE standard deviation across every resolved observation — the
     * per-call dispersion, useful for display. {@code cohorts} and {@code stdCohortMeanBps} carry the
     * INDEPENDENT sample (ADR-0077): the number of distinct emission bursts the observations came from,
     * and the dispersion of those bursts' mean returns. The standard error is formed from the latter,
     * because that is the sample that actually varies.
     *
     * <p>{@code horizonSeconds} is part of the IDENTITY of this reading, not a label on it (ADR-0082):
     * expectancy is a return over a period, so "+13.5 bps" means nothing until you know whether that is
     * per hour or per four minutes — and the desk's round-trip cost, which the edge gate subtracts from
     * it, is per round trip regardless. A source measured at several horizons yields several Stats, one
     * per rung, and they are never pooled. Zero means "unstated" — the shape a caller with no ladder to
     * hand produces, and the reason every consumer must treat it as opaque rather than arithmetic.
     */
    public record Stats(String source, long resolved, long wins, long losses, long flats, long open,
                        double hitRate, double avgReturnBps, double stdReturnBps,
                        long cohorts, double stdCohortMeanBps, long horizonSeconds) {

        /**
         * The i.i.d. shape: every observation is its own independent draw. Correct for a source that
         * emits one call at a time, and the honest reading of a caller that has no timing to hand.
         */
        public Stats(String source, long resolved, long wins, long losses, long flats, long open,
                     double hitRate, double avgReturnBps, double stdReturnBps) {
            this(source, resolved, wins, losses, flats, open, hitRate, avgReturnBps, stdReturnBps,
                    resolved, stdReturnBps, 0L);
        }

        /** The cohort-aware shape at an unstated horizon — the ADR-0077 constructor, unchanged. */
        public Stats(String source, long resolved, long wins, long losses, long flats, long open,
                     double hitRate, double avgReturnBps, double stdReturnBps,
                     long cohorts, double stdCohortMeanBps) {
            this(source, resolved, wins, losses, flats, open, hitRate, avgReturnBps, stdReturnBps,
                    cohorts, stdCohortMeanBps, 0L);
        }

        /** The same reading, restated as having been measured over {@code horizonSeconds}. */
        public Stats atHorizon(long horizonSeconds) {
            return new Stats(source, resolved, wins, losses, flats, open, hitRate, avgReturnBps,
                    stdReturnBps, cohorts, stdCohortMeanBps, horizonSeconds);
        }

        /**
         * Standard error of {@link #avgReturnBps} in bps — the Fama–MacBeth estimator, taken across
         * emission cohorts rather than across observations (ADR-0077). Zero when fewer than two
         * independent cohorts have resolved: a single cross-section, however wide, supports no standard
         * error at all, and consumers read a zero as "no evidence yet".
         */
        public double stdErrorBps() {
            return cohorts > 1 && stdCohortMeanBps > 0 ? stdCohortMeanBps / Math.sqrt((double) cohorts) : 0.0;
        }
    }

    /** One resolved observation and the instant its call was recorded. */
    public record Observation(long entryEpochMillis, double directionalReturn) {
    }

    /**
     * Aggregates one source's resolved directional returns, treating every observation as an
     * independent draw. Correct only for a source that emits one call at a time; prefer
     * {@link #aggregate(String, List, long, double, long)} where emission times are known.
     */
    public static Stats aggregate(String source, List<Double> directionalReturns, double flatThresholdBps, long open) {
        List<Observation> obs = new ArrayList<>(directionalReturns.size());
        long t = 0;
        for (double r : directionalReturns) {
            obs.add(new Observation(t++, r)); // 1ms apart ⇒ every observation its own cohort
        }
        return aggregate(source, obs, 0, flatThresholdBps, open);
    }

    /**
     * Aggregates one source's resolved observations into hit-rate, expectancy and BOTH dispersions —
     * per-observation and between-cohort (ADR-0077).
     *
     * <p><b>Why cohorts.</b> A forecast source scores its whole cross-section at once: 23 names called
     * in the same 200ms burst, resolved together one horizon later. Those 23 numbers are 23 views of the
     * SAME hour of market, not 23 independent draws, so dividing their dispersion by √23 understates the
     * standard error by √(1+(n−1)ρ̄) — a factor of ~2.2 at the measured cross-sectional correlation on
     * this book. The expectancy is therefore estimated the way a cross-sectional signal always is
     * (Fama &amp; MacBeth, <i>JPE</i> 1973): reduce each burst to one number, then do the statistics over
     * bursts. Where a source emits one call at a time every cohort has one member and this reduces
     * exactly to the ordinary i.i.d. estimator — no special case, no discontinuity.
     *
     * @param cohortWindowMillis observations recorded within this gap of one another are ONE draw.
     *                           Merging is the conservative direction (fewer independent cohorts ⇒ a
     *                           wider standard error ⇒ a stricter gate), so this is deliberately
     *                           generous relative to a burst's width.
     */
    public static Stats aggregate(String source, List<Observation> observations, long cohortWindowMillis,
                                  double flatThresholdBps, long open) {
        long wins = 0;
        long losses = 0;
        long flats = 0;
        for (Observation o : observations) {
            switch (outcome(o.directionalReturn(), flatThresholdBps)) {
                case WIN -> wins++;
                case LOSS -> losses++;
                case FLAT -> flats++;
            }
        }
        long n = observations.size();
        double decisive = wins + losses;
        double hitRate = decisive > 0 ? wins / decisive : 0.0; // FLATs excluded — they were no bet

        List<Double> cohortMeans = cohortMeans(observations, cohortWindowMillis);
        // Fama–MacBeth: each cohort contributes one observation of the source's expectancy, and every
        // cohort counts once regardless of how many names it happened to span. Identical to the pooled
        // mean when cohorts are equal-sized (as a full-cross-section emitter's always are).
        double meanBps = mean(cohortMeans) * 1e4;
        double stdCohortMeanBps = sampleStdDev(cohortMeans, mean(cohortMeans)) * 1e4;

        List<Double> returns = new ArrayList<>(observations.size());
        for (Observation o : observations) {
            returns.add(o.directionalReturn());
        }
        double stdBps = sampleStdDev(returns, mean(returns)) * 1e4;

        return new Stats(source, n, wins, losses, flats, open, hitRate, meanBps, stdBps,
                cohortMeans.size(), stdCohortMeanBps);
    }

    /**
     * Mean directional return per emission cohort. Observations are ordered by entry time and a new
     * cohort starts wherever the gap to the previous one exceeds {@code cohortWindowMillis}.
     */
    private static List<Double> cohortMeans(List<Observation> observations, long cohortWindowMillis) {
        List<Double> out = new ArrayList<>();
        if (observations.isEmpty()) {
            return out;
        }
        List<Observation> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparingLong(Observation::entryEpochMillis));
        long window = Math.max(0, cohortWindowMillis);
        double sum = 0;
        long count = 0;
        long previous = sorted.get(0).entryEpochMillis();
        for (Observation o : sorted) {
            if (count > 0 && o.entryEpochMillis() - previous > window) {
                out.add(sum / count);
                sum = 0;
                count = 0;
            }
            sum += o.directionalReturn();
            count++;
            previous = o.entryEpochMillis();
        }
        out.add(sum / count);
        return out;
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** Bessel-corrected sample standard deviation; 0 when the sample cannot support one. */
    private static double sampleStdDev(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double sqDev = 0;
        for (double v : values) {
            double d = v - mean;
            sqDev += d * d;
        }
        return Math.sqrt(sqDev / (values.size() - 1));
    }
}

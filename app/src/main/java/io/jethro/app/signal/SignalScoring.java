package io.jethro.app.signal;

import java.math.BigDecimal;
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

    /** Rolling health for one source; {@code open} (unresolved) is filled by the caller. */
    public record Stats(String source, long resolved, long wins, long losses, long flats, long open,
                        double hitRate, double avgReturnBps) {
    }

    /** Aggregates one source's resolved directional returns into hit-rate + average return. */
    public static Stats aggregate(String source, List<Double> directionalReturns, double flatThresholdBps, long open) {
        long wins = 0;
        long losses = 0;
        long flats = 0;
        double sum = 0;
        for (double r : directionalReturns) {
            sum += r;
            switch (outcome(r, flatThresholdBps)) {
                case WIN -> wins++;
                case LOSS -> losses++;
                case FLAT -> flats++;
            }
        }
        long n = directionalReturns.size();
        double decisive = wins + losses;
        double hitRate = decisive > 0 ? wins / decisive : 0.0; // FLATs excluded — they were no bet
        double avgBps = n > 0 ? (sum / n) * 1e4 : 0.0;
        return new Stats(source, n, wins, losses, flats, open, hitRate, avgBps);
    }
}

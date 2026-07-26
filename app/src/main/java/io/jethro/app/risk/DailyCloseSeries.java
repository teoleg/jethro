package io.jethro.app.risk;

import io.jethro.messaging.Provenance;

import java.time.LocalDate;
import java.util.List;

/**
 * Which recorded daily closes the running session may measure risk from, and which consecutive pairs
 * of them form a real return (ADR-0073 / invariant 8).
 *
 * <p><b>Why this exists.</b> Sim, live and replay are different price processes for the same name —
 * the sim's AAPL and the live feed's AAPL sit at different levels entirely. Before ADR-0073 the
 * {@code daily_close} table was keyed {@code (day, instrument)} with no feed mode, so both sessions
 * wrote into one series; the close-to-close "return" across the boundary was the ratio of two
 * unrelated price levels, not a market move. Every consumer of that series is a risk sensor — VaR and
 * per-name volatility, which vol-targets sizing — so the phantom propagated straight into the numbers
 * that say how much is at risk and how big a position may be.
 *
 * <p><b>The rule, in two parts.</b>
 * <ol>
 *   <li><b>Admissible rows.</b> The running feed mode's own closes, plus the {@code SEED} bootstrap
 *       history. {@code SEED} is reference data — real history loaded once by the seeder before any
 *       session ran, the prior every mode starts from — not another mode's session output, so using
 *       it is not aggregating sim with live. Where a day carries both, the running mode's own close
 *       wins: a session's own observation of its own tape beats the prior.</li>
 *   <li><b>Admissible returns.</b> Only between two closes from the <i>same</i> stream. This is what
 *       actually removes the phantom: filtering rows alone still leaves the one boundary pair where
 *       {@code SEED} hands over to the session. Cost is at most one observation per boundary — a gap
 *       the estimator handles, unlike a fabricated 70% day it cannot.</li>
 * </ol>
 *
 * <p>Pure and dimensionless: it selects observations, and computes no price, return, risk or money
 * number of its own (ADR-0016 / invariant 7).
 */
public final class DailyCloseSeries {

    /** The bootstrap history loaded before any session ran — reference data, admissible in every mode. */
    public static final String SEED = "SEED";

    /** One recorded close and the stream that produced it. */
    public record Close(LocalDate day, String feedMode, double close) {
    }

    private DailyCloseSeries() {
    }

    /** The feed modes the running session may read: its own, plus the mode-agnostic seed. */
    public static List<String> admissibleModes() {
        return admissibleModes(Provenance.mode().name());
    }

    /**
     * As above for an explicit mode — the testable form. Always exactly two entries so callers can bind
     * a fixed {@code in (?, ?)}; an absent mode degenerates to the seed alone (repeated, not widened).
     */
    public static List<String> admissibleModes(String feedMode) {
        return feedMode == null || feedMode.isBlank() || SEED.equals(feedMode)
                ? List.of(SEED, SEED) : List.of(feedMode, SEED);
    }

    /**
     * May a close-to-close return be taken between these two observations? Only when both came from
     * the same stream — a handover between the seed and a session, or between two feed modes, is a
     * change of price process, not a market move.
     */
    public static boolean sameStream(String earlierMode, String laterMode) {
        return earlierMode != null && earlierMode.equals(laterMode);
    }

    /**
     * The admissible close-to-close returns of one instrument's series, oldest first: a return for
     * every consecutive pair from the same stream, and nothing across a handover. Returns are
     * fractional ({@code later/earlier − 1}).
     *
     * <p>{@code double} here because these are estimator input for {@code VolMath}/{@code VarMath} —
     * a dimensionless return series, never a price, quantity or P&amp;L at a boundary (invariant 1).
     *
     * @param ascendingByDay one close per day, ascending; a non-positive earlier close is skipped
     */
    public static double[] returns(List<Close> ascendingByDay) {
        if (ascendingByDay == null || ascendingByDay.size() < 2) {
            return new double[0];
        }
        double[] scratch = new double[ascendingByDay.size() - 1];
        int n = 0;
        for (int i = 1; i < ascendingByDay.size(); i++) {
            Close earlier = ascendingByDay.get(i - 1);
            Close later = ascendingByDay.get(i);
            if (earlier.close() > 0 && sameStream(earlier.feedMode(), later.feedMode())) {
                scratch[n++] = later.close() / earlier.close() - 1.0;
            }
        }
        return n == scratch.length ? scratch : java.util.Arrays.copyOf(scratch, n);
    }

    /**
     * True when {@code candidate} should replace {@code held} as the close for a day already seen.
     * The running mode's own observation beats the seed; otherwise the first row read stands, so the
     * result never depends on row order beyond that one rule.
     */
    public static boolean preferOver(String heldMode, String candidateMode) {
        return SEED.equals(heldMode) && !SEED.equals(candidateMode);
    }
}

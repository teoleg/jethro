package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.Hypothesis;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The deterministic risk envelope for bounded autonomy (ADR-0022, amended by ADR-0027):
 * decides whether an already-admissible, quant-sized hypothesis may auto-execute (simulated,
 * ADR-0019), and AT WHAT SIZE. Pure and code-only — the model never influences this.
 *
 * <p>The gate is the AI's own <b>measured track record</b> (ADR-0027 outcome scoring), not the
 * momentum strategy's backtest (which measured a different strategy's edge — a category error
 * that, combined with honest costs, silently revoked all autonomy):
 * <ul>
 *   <li><b>Probation</b> — fewer than {@code minTrackRecord} scored outcomes: trade SMALL
 *       (the probation notional; the order is resized down to it) to build the record. This is
 *       how a desk seeds a new strategy: earn size with measured results.</li>
 *   <li><b>Earned</b> — with a full record and measured outcome P&L &gt; 0: full autonomy cap
 *       (an over-cap order is rejected, not resized — full size must be deliberate).</li>
 *   <li><b>Revoked</b> — a full record with non-positive P&L: no auto-execution; every thesis
 *       stays a human-review card until the humans decide otherwise.</li>
 * </ul>
 * Admissibility, minimum conviction and the whitelist still apply in all phases.
 *
 * <p><b>ADR-0049:</b> the OOS backtest is no longer advisory — it is a HARD pre-gate applied BEFORE
 * this envelope (in {@code HypothesisLifecycle.runAutonomy}): a thesis whose instrument the
 * deterministic backtest does not support never reaches this envelope and never becomes an order.
 * This envelope's track-record/cap logic is now an additional constraint on top of that gate, never
 * a substitute for it — the model never originates an order without a measured deterministic edge.
 */
public final class AutonomyEnvelope {

    /** The AI sleeve's measured record: scored outcomes and their summed mark-to-mark P&L. */
    public record TrackRecord(int scoredOutcomes, BigDecimal outcomePnl) {
        public static final TrackRecord EMPTY = new TrackRecord(0, BigDecimal.ZERO);
    }

    /** The envelope's ruling: allowed (at {@code quantity}, possibly probation-resized) or not. */
    public record Decision(boolean allowed, String reason, BigDecimal quantity, boolean probation) {
        static Decision no(String reason) {
            return new Decision(false, reason, null, false);
        }

        static Decision yes(BigDecimal quantity, boolean probation) {
            return new Decision(true, null, quantity, probation);
        }
    }

    private final HypothesisProperties.Autonomy cfg;

    public AutonomyEnvelope(HypothesisProperties.Autonomy cfg) {
        this.cfg = cfg;
    }

    public Decision decide(HypothesisEvaluator.Evaluated e, BigDecimal multiplier, TrackRecord track) {
        if (e.verdict() != HypothesisEvaluator.Verdict.ADMISSIBLE) {
            return Decision.no("not admissible");
        }
        if (!convictionMeetsMin(e.hypothesis().conviction())) {
            return Decision.no("conviction " + e.hypothesis().conviction()
                    + " below minimum " + minConviction());
        }
        var whitelist = cfg.whitelistOrEmpty();
        if (!whitelist.isEmpty() && !whitelist.contains(e.hypothesis().instrumentId())) {
            return Decision.no(e.hypothesis().instrumentId() + " not on the autonomy whitelist");
        }

        BigDecimal notionalPerUnit = e.price().multiply(multiplier);
        if (track.scoredOutcomes() < cfg.minTrackRecordOrDefault()) {
            // PROBATION: resize down to the probation notional so the record can build — but you
            // cannot trade less than ONE unit of a contract, so a high-multiplier name (ZN/ES)
            // trades the 1-unit minimum rather than nothing. Otherwise those names sit in probation
            // forever, never executing, never scoring an outcome — autonomy deadlocked.
            BigDecimal affordable = cfg.probationOrderNotionalOrDefault()
                    .divide(notionalPerUnit, 0, RoundingMode.DOWN);
            BigDecimal qty = e.quantity().min(affordable.max(BigDecimal.ONE));
            return Decision.yes(qty, true);
        }
        if (track.outcomePnl().signum() <= 0) {
            return Decision.no("autonomy revoked — measured track record non-positive ("
                    + track.outcomePnl().toPlainString() + " over " + track.scoredOutcomes()
                    + " scored theses); human review only");
        }
        BigDecimal notional = e.quantity().multiply(notionalPerUnit);
        if (notional.compareTo(cfg.maxOrderNotionalOrDefault()) > 0) {
            return Decision.no("order notional " + notional.toPlainString()
                    + " exceeds autonomy cap " + cfg.maxOrderNotionalOrDefault().toPlainString());
        }
        return Decision.yes(e.quantity(), false);
    }

    private boolean convictionMeetsMin(Hypothesis.Conviction conviction) {
        return conviction.ordinal() >= minConviction().ordinal();
    }

    /** Parses the configured minimum; an unknown value falls back to the safest (HIGH). */
    private Hypothesis.Conviction minConviction() {
        try {
            return Hypothesis.Conviction.valueOf(cfg.minConvictionOrDefault().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Hypothesis.Conviction.HIGH;
        }
    }
}

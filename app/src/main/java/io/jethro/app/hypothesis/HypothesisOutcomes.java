package io.jethro.app.hypothesis;

import io.jethro.domain.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Outcome scoring math (ADR-0027), pure and exact for testing (finance-math rule).
 * A hypothesis is scored MARK-TO-MARK at horizon expiry:
 *
 * <pre>
 *   pnl = (exit − entry) × qty × multiplier × dir      dir: BUY = +1, SELL = −1
 * </pre>
 *
 * Worked example: SHORT 143 GOOG (dir −1, mult 1), entry 175.00, exit 171.50 →
 * (171.50 − 175.00) × 143 × 1 × (−1) = <b>+500.50</b> → WIN. Execution costs are NOT in
 * this number — they live in the AI book's ledger P&L; this measures whether the CALL was
 * right, which is what conviction calibration needs.
 */
final class HypothesisOutcomes {

    private HypothesisOutcomes() {
    }

    record Score(String outcome, BigDecimal pnl) {
    }

    static Score score(String direction, BigDecimal entry, BigDecimal exit,
                       BigDecimal quantity, BigDecimal multiplier) {
        BigDecimal dir = "BUY".equals(direction) ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal pnl = exit.subtract(entry).multiply(quantity).multiply(multiplier).multiply(dir)
                .setScale(Decimals.PNL_SCALE, RoundingMode.HALF_EVEN);
        String outcome = pnl.signum() > 0 ? "WIN" : (pnl.signum() < 0 ? "LOSS" : "FLAT");
        return new Score(outcome, pnl);
    }
}

package io.jethro.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Average-cost position accounting (ADR-0008). For a fill of signed quantity {@code q}
 * at price {@code p} into position {@code (Q, avgCost)} with contract multiplier
 * {@code m}:
 *
 * <ul>
 *   <li><b>Increasing</b> (same sign or from flat):
 *       {@code avgCost' = (Q·avgCost + q·p) / (Q + q)}; no realized PnL.</li>
 *   <li><b>Reducing</b> (opposite sign, |q| ≤ |Q|):
 *       {@code realized = (p − avgCost) · closedQty · signum(Q) · m};
 *       avgCost unchanged; avgCost resets to zero when flat.</li>
 *   <li><b>Crossing through flat</b> (|q| &gt; |Q|): split into reduce-to-flat then
 *       open-new-position at {@code p} — never computed as one step.</li>
 * </ul>
 *
 * CONVENTION: avgCost and realized PnL carry scale {@link Decimals#PNL_SCALE} with
 * {@link RoundingMode#HALF_EVEN}. Division occurs only in the increasing case; the
 * rounding mode is stated there explicitly per the finance-math rules. Realized PnL is
 * in the instrument's currency; conversion to book currency happens in risk-pnl.
 */
public final class Positions {

    private static final int SCALE = Decimals.PNL_SCALE;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private Positions() {
    }

    /** Result of applying one fill: the new position and the realized PnL delta. */
    public record FillApplication(Position position, BigDecimal realizedPnl) {
    }

    /**
     * Applies a fill to a position. Pure function: dedupe of at-least-once redelivery
     * (invariant 6) is the caller's job, keyed on {@link Fill#fillId()}.
     */
    public static FillApplication applyFill(Position position, Fill fill, BigDecimal contractMultiplier) {
        if (!position.bookId().equals(fill.bookId()) || !position.instrumentId().equals(fill.instrumentId())) {
            throw new IllegalArgumentException("fill " + fill.fillId() + " does not belong to position "
                    + position.bookId() + "/" + position.instrumentId());
        }
        if (contractMultiplier == null || contractMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("contractMultiplier must be positive");
        }

        BigDecimal q = fill.signedQuantity();
        BigDecimal p = fill.price();
        BigDecimal bigQ = position.quantity();

        // Opening from flat
        if (bigQ.signum() == 0) {
            return new FillApplication(withPosition(position, q, scaled(p)), zero());
        }

        // Increasing: same sign
        if (bigQ.signum() == q.signum()) {
            BigDecimal newQty = bigQ.add(q);
            BigDecimal newAvg = bigQ.multiply(position.avgCost())
                    .add(q.multiply(p))
                    .divide(newQty, SCALE, ROUNDING);
            return new FillApplication(withPosition(position, newQty, newAvg), zero());
        }

        // Reducing (possibly through flat)
        BigDecimal closedQty = q.abs().min(bigQ.abs());
        BigDecimal realized = p.subtract(position.avgCost())
                .multiply(closedQty)
                .multiply(BigDecimal.valueOf(bigQ.signum()))
                .multiply(contractMultiplier)
                .setScale(SCALE, ROUNDING);

        BigDecimal newQty = bigQ.add(q);
        if (newQty.signum() == 0) {
            // Fully closed: flat, avgCost resets
            return new FillApplication(withPosition(position, BigDecimal.ZERO, BigDecimal.ZERO), realized);
        }
        if (newQty.signum() == bigQ.signum()) {
            // Partial reduce: avgCost unchanged
            return new FillApplication(withPosition(position, newQty, position.avgCost()), realized);
        }
        // Crossed through flat: remainder opens a new position at the fill price
        return new FillApplication(withPosition(position, newQty, scaled(p)), realized);
    }

    private static Position withPosition(Position base, BigDecimal qty, BigDecimal avgCost) {
        return new Position(base.bookId(), base.instrumentId(), qty, avgCost);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    }
}

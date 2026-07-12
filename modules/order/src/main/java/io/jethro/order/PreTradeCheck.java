package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.InstrumentId;

import java.math.BigDecimal;

/**
 * Pre-trade risk gate (ADR-0018): the order module asks, before routing, whether an
 * order is admissible. Kept an interface so the order module stays decoupled from
 * risk-pnl (ADR-0015); the app binds it to the deterministic exposure guardrail. A
 * no-op implementation approves everything when risk isn't wired.
 */
public interface PreTradeCheck {

    /** @param signedQuantity order quantity signed by side (BUY positive, SELL negative). */
    Decision check(BookId bookId, InstrumentId instrumentId, BigDecimal signedQuantity);

    record Decision(boolean approved, String reason) {
        public static Decision approve() {
            return new Decision(true, null);
        }

        public static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }

    /** Approves everything — used when no risk guardrail is wired. */
    PreTradeCheck APPROVE_ALL = (book, instrument, qty) -> Decision.approve();
}

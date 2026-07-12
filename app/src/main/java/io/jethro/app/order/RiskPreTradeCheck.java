package io.jethro.app.order;

import io.jethro.domain.BookId;
import io.jethro.domain.InstrumentId;
import io.jethro.order.PreTradeCheck;
import io.jethro.trading.riskpnl.PreTradeGuardrail;

import java.math.BigDecimal;

/** Binds the order module's {@link PreTradeCheck} port to the risk-pnl exposure guardrail. */
public final class RiskPreTradeCheck implements PreTradeCheck {

    private final PreTradeGuardrail guardrail;

    public RiskPreTradeCheck(PreTradeGuardrail guardrail) {
        this.guardrail = guardrail;
    }

    @Override
    public Decision check(BookId bookId, InstrumentId instrumentId, BigDecimal signedQuantity) {
        // reserve=true: this is the order path — an approval reserves the exposure delta so
        // in-flight orders count against subsequent checks until their fills project.
        return guardrail.rejectionReason(bookId.value(), instrumentId.value(), signedQuantity, true)
                .map(Decision::reject)
                .orElseGet(Decision::approve);
    }
}

package io.jethro.app.hypothesis;

import java.math.BigDecimal;

/**
 * A durable record of an AI hypothesis the bounded-autonomy envelope executed (ADR-0022) —
 * one per order the model's thesis led to — now carrying its OUTCOME (ADR-0027): the entry
 * mark, the horizon expiry, and at expiry the exit mark and the scored result. Outcome P&L
 * is <b>mark-to-mark</b> (was the directional call right?); execution costs/slippage live in
 * the AI book's ledger P&L, which is the money record. Quantities/prices are exact decimals
 * (invariant 1), never floats.
 *
 * @param outcome null while the thesis is open; WIN/LOSS/FLAT once scored at horizon expiry.
 */
public record HypothesisRecord(String id, long timestampMillis, String instrumentId, String direction,
                               String horizon, String conviction, String thesis, String book,
                               BigDecimal quantity, Boolean backtestSupported,
                               String orderId, String orderStatus,
                               BigDecimal entryPrice, long expiresAtMillis,
                               String outcome, BigDecimal outcomePnl, BigDecimal exitPrice) {

    /** An open (unscored) record — outcome fields empty until horizon expiry. */
    public static HypothesisRecord open(String id, long timestampMillis, String instrumentId,
                                        String direction, String horizon, String conviction,
                                        String thesis, String book, BigDecimal quantity,
                                        Boolean backtestSupported, String orderId, String orderStatus,
                                        BigDecimal entryPrice, long expiresAtMillis) {
        return new HypothesisRecord(id, timestampMillis, instrumentId, direction, horizon, conviction,
                thesis, book, quantity, backtestSupported, orderId, orderStatus,
                entryPrice, expiresAtMillis, null, null, null);
    }

    /** This record scored at expiry. */
    public HypothesisRecord scored(String outcome, BigDecimal outcomePnl, BigDecimal exitPrice) {
        return new HypothesisRecord(id, timestampMillis, instrumentId, direction, horizon, conviction,
                thesis, book, quantity, backtestSupported, orderId, orderStatus,
                entryPrice, expiresAtMillis, outcome, outcomePnl, exitPrice);
    }

    public boolean isOpen() {
        return outcome == null;
    }
}

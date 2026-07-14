package io.jethro.order;

import java.math.BigDecimal;

/**
 * Execution costs for simulated fills (ADR-0025): the half-spread a marketable order crosses
 * and the fee it pays, per instrument. A port so the order module stays free of reference-data
 * internals — the app assembly binds it to per-asset-class config. {@link #FREE} keeps
 * cost-free execution for tests that assert pure lifecycle behaviour.
 *
 * @param spreadBps  full bid/ask spread. For price-quoted instruments: bps of price
 *                   (multiplicative). For rate-quoted ones ({@code rateQuoted}): basis points
 *                   of RATE, applied additively to the quote (a swap quoted 4.00% with a 0.4bp
 *                   spread fills pay-fixed at 4.002).
 * @param feeBps     fee/commission in bps of notional, embedded in the fill price for MARKET
 *                   orders (v1 — a separate cash line is the ADR-0025 follow-up).
 * @param rateQuoted true when the "price" is a rate in percent (swaps) — spread is additive.
 */
public interface ExecutionCostSource {

    Cost costFor(String instrumentId);

    record Cost(BigDecimal spreadBps, BigDecimal feeBps, boolean rateQuoted) {
    }

    /** Zero-cost execution (lifecycle tests; NOT the production default — see ADR-0025). */
    ExecutionCostSource FREE = instrumentId ->
            new Cost(BigDecimal.ZERO, BigDecimal.ZERO, false);
}

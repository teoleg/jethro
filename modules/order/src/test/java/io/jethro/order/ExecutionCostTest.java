package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.domain.TimeInForce;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution cost model (ADR-0025), exact worked examples (finance-math rule):
 * mid is the mark; a marketable order crosses the half-spread; MARKET pays the fee.
 */
class ExecutionCostTest {

    private static final ExecutionCostSource EQUITY_COSTS = id ->
            new ExecutionCostSource.Cost(new BigDecimal("5"), new BigDecimal("1"), false);
    private static final ExecutionCostSource SWAP_COSTS = id ->
            new ExecutionCostSource.Cost(new BigDecimal("0.4"), BigDecimal.ZERO, true);

    private static Order order(Side side, OrderType type, String limit) {
        return new Order("ord-1", "key-1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                side, type, new BigDecimal("131"),
                Optional.ofNullable(limit).map(BigDecimal::new), TimeInForce.GTC, OrderStatus.ROUTED, Instant.EPOCH);
    }

    // Impact-only source (zero spread/fee isolates the impact term): ADV $1B, σ 2%/day, mult 1.
    private static final ExecutionCostSource IMPACT_COSTS = id ->
            new ExecutionCostSource.Cost(BigDecimal.ZERO, BigDecimal.ZERO, false,
                    new BigDecimal("1000000000"), new BigDecimal("0.02"), BigDecimal.ONE);

    private static Order sized(Side side, String qty) {
        return new Order("ord-1", "key-1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                side, OrderType.MARKET, new BigDecimal(qty),
                Optional.empty(), TimeInForce.GTC, OrderStatus.ROUTED, Instant.EPOCH);
    }

    @Test
    void marketImpactFollowsTheSquareRootLaw() {
        var exec = new SimulatedExecutor(IMPACT_COSTS);
        // 500 @ 200 = 100k notional; participation 1e-4 → √ = 0.01 → impact = 0.02×0.01 = 2bp.
        var small = exec.tryExecute(sized(Side.BUY, "500"), new BigDecimal("200")).orElseThrow();
        assertTrue(small.price().compareTo(new BigDecimal("200.0399")) > 0
                        && small.price().compareTo(new BigDecimal("200.0401")) < 0,
                "≈200.04 (2bp impact), got " + small.price());
        // 4× the size → √4 = 2× the impact (4bp → ≈200.08), NOT 4×.
        var big = exec.tryExecute(sized(Side.BUY, "2000"), new BigDecimal("200")).orElseThrow();
        assertTrue(big.price().compareTo(new BigDecimal("200.0799")) > 0
                        && big.price().compareTo(new BigDecimal("200.0801")) < 0,
                "≈200.08 (4bp impact), got " + big.price());
        // SELL is hit adversely downward.
        var sell = exec.tryExecute(sized(Side.SELL, "500"), new BigDecimal("200")).orElseThrow();
        assertTrue(sell.price().compareTo(new BigDecimal("200")) < 0, "impact is always adverse");
    }

    @Test
    void participationCapRejectsOutsizedOrdersBeforeRouting() {
        // ADV $1M for the test; cap 2% = $20k. 500 @ 200 = $100k → rejected with the reason.
        ExecutionCostSource thinAdv = id -> new ExecutionCostSource.Cost(
                BigDecimal.ZERO, BigDecimal.ZERO, false,
                new BigDecimal("1000000"), new BigDecimal("0.02"), BigDecimal.ONE);
        var exec = new SimulatedExecutor(thinAdv, new BigDecimal("0.02"));
        var rejection = exec.participationRejection(sized(Side.BUY, "500"), new BigDecimal("200"));
        assertTrue(rejection.isPresent() && rejection.get().contains("% of ADV"), String.valueOf(rejection));
        // $10k order = 1% of ADV → passes.
        assertTrue(exec.participationRejection(sized(Side.BUY, "50"), new BigDecimal("200")).isEmpty());
        // No ADV on file → unmodelled, no cap (never guessed).
        var noAdv = new SimulatedExecutor(EQUITY_COSTS, new BigDecimal("0.02"));
        assertTrue(noAdv.participationRejection(sized(Side.BUY, "500000"), new BigDecimal("200")).isEmpty());
    }

    @Test
    void maxQuantityUnderCapIsTheSlicerChunkSize() {
        // ADV $1M, cap 2% = $20k notional; mid 200 → max 100 units per slice.
        ExecutionCostSource thinAdv = id -> new ExecutionCostSource.Cost(
                BigDecimal.ZERO, BigDecimal.ZERO, false,
                new BigDecimal("1000000"), new BigDecimal("0.02"), BigDecimal.ONE);
        var exec = new SimulatedExecutor(thinAdv, new BigDecimal("0.02"));
        var max = exec.maxQuantityUnderCap(sized(Side.BUY, "500"), new BigDecimal("200")).orElseThrow();
        assertTrue(new BigDecimal("100").compareTo(max) == 0, "20k / 200 = 100, got " + max);
        // A slice of exactly that size passes the gate.
        assertTrue(exec.participationRejection(sized(Side.BUY, "100"), new BigDecimal("200")).isEmpty());
        // Cap off / no mid → empty (caller falls back to reject-or-pass).
        assertTrue(new SimulatedExecutor(thinAdv).maxQuantityUnderCap(sized(Side.BUY, "500"), new BigDecimal("200")).isEmpty());
        assertTrue(exec.maxQuantityUnderCap(sized(Side.BUY, "500"), null).isEmpty());
    }

    @Test
    void quotedTouchOverridesTheSyntheticSpread() {
        // A REAL quote rides with the mark (ADR-0025): BUY crosses to the ASK as quoted —
        // fill price IS the ask (fees are cash now, never in the price).
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        var buy = exec.tryExecute(order(Side.BUY, OrderType.MARKET, null),
                new BigDecimal("190.00"), new BigDecimal("189.95"), new BigDecimal("190.05")).orElseThrow();
        assertEquals(0, new BigDecimal("190.05").compareTo(buy.price()));
        // fee = 131 × 190.05 × 1bp = 2.489655 cash.
        assertEquals(0, new BigDecimal("2.489655").compareTo(buy.fee()));
        // SELL hits the BID at the quoted 189.95.
        var sell = exec.tryExecute(order(Side.SELL, OrderType.MARKET, null),
                new BigDecimal("190.00"), new BigDecimal("189.95"), new BigDecimal("190.05")).orElseThrow();
        assertEquals(0, new BigDecimal("189.95").compareTo(sell.price()));
    }

    @Test
    void limitMarketabilityUsesTheQuotedAsk() {
        // BUY LIMIT 190.02: the synthetic touch (190.0475) says unmarketable, but the REAL
        // ask is 190.01 ≤ limit → fills at the limit. Quotes beat estimates.
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        var fill = exec.tryExecute(order(Side.BUY, OrderType.LIMIT, "190.02"),
                new BigDecimal("190.00"), new BigDecimal("189.99"), new BigDecimal("190.01")).orElseThrow();
        assertEquals(0, new BigDecimal("190.02").compareTo(fill.price()));
        // And the reverse: quoted ask 190.03 > limit → not marketable even though mid is below.
        assertTrue(exec.tryExecute(order(Side.BUY, OrderType.LIMIT, "190.02"),
                new BigDecimal("190.00"), new BigDecimal("189.99"), new BigDecimal("190.03")).isEmpty());
    }

    @Test
    void buyMarketCrossesHalfSpreadAndPaysFeeAsCash() {
        // mid 190.00, spread 5bp, fee 1bp: fill price = touch = 190 × 1.00025 = 190.0475
        // (the price carries ONLY the spread); fee = 131 × 190.0475 × 1bp = 2.489622 cash.
        var fill = new SimulatedExecutor(EQUITY_COSTS)
                .tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("190.047500"), fill.price());
        assertEquals(0, new BigDecimal("2.489622").compareTo(fill.fee()));
    }

    @Test
    void sellMarketGetsHitOnTheBidWithItsOwnCashFee() {
        // fill price = touch = 190 × 0.99975 = 189.9525; fee = 131 × 189.9525 × 1bp = 2.488378.
        var fill = new SimulatedExecutor(EQUITY_COSTS)
                .tryExecute(order(Side.SELL, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("189.952500"), fill.price());
        assertEquals(0, new BigDecimal("2.488378").compareTo(fill.fee()));
    }

    @Test
    void roundTripStillCostsSpreadPlusTwoFeesNowSplitHonestly() {
        // PRICE round trip at an unmoved mid = the spread: 131 × (190.0475 − 189.9525) =
        // 131 × 0.095 = $12.445; CASH fees add 2.489622 + 2.488378 = $4.978 — total ≈ 7bp
        // of notional, identical economics to before, now separable for TCA.
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        var buy = exec.tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        var sell = exec.tryExecute(order(Side.SELL, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        BigDecimal priceRoundTrip = buy.price().subtract(sell.price()).multiply(new BigDecimal("131"));
        assertEquals(new BigDecimal("12.445000"), priceRoundTrip.setScale(6));
        assertEquals(0, new BigDecimal("4.978000").compareTo(buy.fee().add(sell.fee())));
    }

    @Test
    void limitFillsNowCarryTheirFeeWithoutTouchingTheLimitPrice() {
        // The whole point of fee-as-cash: a LIMIT fill pays commission WITHOUT its price
        // moving past the limit. BUY LIMIT 190.02, real ask 190.01 → fills AT 190.02 with
        // fee = 131 × 190.02 × 1bp = 2.489262 cash.
        var fill = new SimulatedExecutor(EQUITY_COSTS)
                .tryExecute(order(Side.BUY, OrderType.LIMIT, "190.02"),
                        new BigDecimal("190.00"), new BigDecimal("189.99"), new BigDecimal("190.01")).orElseThrow();
        assertEquals(0, new BigDecimal("190.02").compareTo(fill.price()));
        assertEquals(0, new BigDecimal("2.489262").compareTo(fill.fee()));
    }

    @Test
    void swapSpreadIsAdditiveInRateBp() {
        // Rate quote 4.00%, 0.4bp spread → pay-fixed (BUY) fills at 4.00 + 0.002 = 4.002.
        var fill = new SimulatedExecutor(SWAP_COSTS)
                .tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("4.00")).orElseThrow();
        assertEquals(new BigDecimal("4.002000"), fill.price());
    }

    @Test
    void limitMarketabilityIsAgainstTheTouchNotTheMid() {
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        // BUY limit AT the mid: the ask (190.0475) is above it → NOT marketable (with free
        // execution this filled — that was the dishonesty).
        assertTrue(exec.tryExecute(order(Side.BUY, OrderType.LIMIT, "190.00"), new BigDecimal("190.00")).isEmpty());
        // BUY limit above the ask: fills AT THE LIMIT, never beyond it (no fee embedded —
        // a limit fill beyond its limit would violate limit semantics; ADR-0025 v1).
        var fill = exec.tryExecute(order(Side.BUY, OrderType.LIMIT, "190.05"), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("190.050000"), fill.price());
    }

    @Test
    void freeSourceFillsAtMidExactly() {
        var fill = new SimulatedExecutor(ExecutionCostSource.FREE)
                .tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(0, new BigDecimal("190.00").compareTo(fill.price()));
    }
}

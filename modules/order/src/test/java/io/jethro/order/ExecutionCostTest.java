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
    void quotedTouchOverridesTheSyntheticSpread() {
        // A REAL quote rides with the mark (ADR-0025): BUY crosses to the ASK as quoted —
        // 190.05 ask + 1bp fee = 190.05 × 1.0001 = 190.069005 — not mid × (1 + spread/2).
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        var buy = exec.tryExecute(order(Side.BUY, OrderType.MARKET, null),
                new BigDecimal("190.00"), new BigDecimal("189.95"), new BigDecimal("190.05")).orElseThrow();
        assertEquals(new BigDecimal("190.069005"), buy.price());
        // SELL hits the BID: 189.95 × (1 − 0.0001) = 189.931005.
        var sell = exec.tryExecute(order(Side.SELL, OrderType.MARKET, null),
                new BigDecimal("190.00"), new BigDecimal("189.95"), new BigDecimal("190.05")).orElseThrow();
        assertEquals(new BigDecimal("189.931005"), sell.price());
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
    void buyMarketCrossesHalfSpreadAndPaysFee() {
        // mid 190.00, spread 5bp, fee 1bp:
        // touch = 190 × 1.00025 = 190.0475; fill = 190.0475 × 1.0001 = 190.06650475 → 190.066505
        var fill = new SimulatedExecutor(EQUITY_COSTS)
                .tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("190.066505"), fill.price());
    }

    @Test
    void sellMarketGetsHitOnTheBidMinusFee() {
        // touch = 190 × 0.99975 = 189.9525; fill = 189.9525 × 0.9999 = 189.93350475 → 189.933505
        var fill = new SimulatedExecutor(EQUITY_COSTS)
                .tryExecute(order(Side.SELL, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("189.933505"), fill.price());
    }

    @Test
    void roundTripCostIsSpreadPlusTwoFees() {
        // Round trip at an unmoved mid loses spread + 2·fee = (5 + 2)bp of notional:
        // 131 × (190.066505 − 189.933505) = 131 × 0.133 = $17.42 — churn is no longer free.
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        var buy = exec.tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        var sell = exec.tryExecute(order(Side.SELL, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        BigDecimal roundTrip = buy.price().subtract(sell.price()).multiply(new BigDecimal("131"));
        assertEquals(new BigDecimal("17.423000"), roundTrip.setScale(6));
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

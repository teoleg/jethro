package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
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
 * ADR-0084 — what POSTING instead of CROSSING is worth, measured in the one number the edge gate
 * reads: implementation-shortfall slippage against the arrival price.
 *
 * <p>Worked example (finance-math rule), on the live MSFT reading that motivated the change — mark
 * 429.327284, configured spread 2.869181818 bps, so the half-spread is 1.434590909 bps:
 * <pre>
 *   touch(BUY)  = 429.327284 × (1 + 0.0002869181818/2) = 429.388875
 *   MARKET BUY  → fills at the touch          → slippage = (429.388875 − 429.327284)/429.327284 × 10⁴
 *                                                        = 1.4346 bps   (half the spread, as designed)
 *   LIMIT  BUY at the arrival mark 429.327284 → fills at the LIMIT when the ask reaches it
 *                                             → slippage = 0.0000 bps
 * </pre>
 * A round trip is two of these, so the desk's measured price cost per round trip falls from the full
 * spread to zero. The separate cash commission is unchanged and still charged on both.
 *
 * <p>These are the sim's execution semantics, not a claim about a real venue: the point under test is
 * that a posted order's cost is its own limit price and a crossed order's cost is the touch — which is
 * true of any book, and is what makes the measurement the gate consumes mean what it says.
 */
class PassiveExecutionCostTest {

    private static final BigDecimal MARK = new BigDecimal("429.327284");
    /** MSFT's configured round-trip spread in bps; the touch is half of it away from the mid. */
    private static final BigDecimal SPREAD_BPS = new BigDecimal("2.869181818");

    /** Spread only — the commission is a separate cash fee and is tested elsewhere (ADR-0025). */
    private static final ExecutionCostSource COSTS = instrument -> new ExecutionCostSource.Cost(
            SPREAD_BPS, BigDecimal.ZERO, false);

    private final SimulatedExecutor executor = new SimulatedExecutor(COSTS);

    private static Order order(OrderType type, Side side, String limit) {
        return new Order("ord-1", "fusion:MSFT:1", new BookId("ALPHA"), new InstrumentId("MSFT"),
                side, type, new BigDecimal("10"),
                limit == null ? Optional.empty() : Optional.of(new BigDecimal(limit)),
                type == OrderType.LIMIT ? TimeInForce.DAY : TimeInForce.GTC,
                OrderStatus.ROUTED, Instant.EPOCH);
    }

    @Test
    void crossingPaysHalfTheSpreadOnEntry() {
        Fill fill = executor.tryExecute(order(OrderType.MARKET, Side.BUY, null), MARK).orElseThrow();

        assertEquals(new BigDecimal("429.388875"), fill.price(), "MARKET BUY lifts the offer");
        assertEquals(new BigDecimal("1.4346"), Tca.slippageBps(Side.BUY, MARK, fill.price(), false),
                "the cost of crossing is exactly the half-spread");
    }

    @Test
    void postingAtTheArrivalMarkCostsNothingWhenItFills() {
        // The market comes to us: the ask has fallen to the price we posted at, so the order is
        // marketable and fills AT OUR LIMIT — which is the arrival price itself.
        BigDecimal midWhenFilled = new BigDecimal("429.200000"); // ask 429.261572 ≤ our 429.327284
        Fill fill = executor.tryExecute(order(OrderType.LIMIT, Side.BUY, "429.327284"), midWhenFilled)
                .orElseThrow();

        assertEquals(new BigDecimal("429.327284"), fill.price(), "a posted order fills at its own price");
        assertEquals(new BigDecimal("0.0000"), Tca.slippageBps(Side.BUY, MARK, fill.price(), false),
                "posting at the arrival mark is a zero-slippage entry — the desk paid the price it decided at");
    }

    @Test
    void postingAtTheArrivalMarkDoesNotFillWhileTheOfferIsAway() {
        // The honest other side of the trade: a passive order that the market never reaches simply
        // does not trade. That is opportunity cost, and it is why an EXIT must never be posted.
        assertTrue(executor.tryExecute(order(OrderType.LIMIT, Side.BUY, "429.327284"), MARK).isEmpty(),
                "at the arrival mid the ask is still a half-spread away — the order rests");
    }

    @Test
    void sellSideIsTheMirrorImage() {
        Fill crossed = executor.tryExecute(order(OrderType.MARKET, Side.SELL, null), MARK).orElseThrow();
        assertEquals(new BigDecimal("429.265693"), crossed.price(), "MARKET SELL hits the bid");
        assertEquals(new BigDecimal("1.4346"), Tca.slippageBps(Side.SELL, MARK, crossed.price(), false));

        BigDecimal midWhenFilled = new BigDecimal("429.450000"); // bid 429.388396 ≥ our 429.327284
        Fill posted = executor.tryExecute(order(OrderType.LIMIT, Side.SELL, "429.327284"), midWhenFilled)
                .orElseThrow();
        assertEquals(new BigDecimal("0.0000"), Tca.slippageBps(Side.SELL, MARK, posted.price(), false));
    }
}

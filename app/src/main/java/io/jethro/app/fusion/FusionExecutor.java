package io.jethro.app.fusion;

import io.jethro.app.risk.TradingHaltSwitch;
import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.strategy.StrategySelector;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.domain.TimeInForce;
import io.jethro.order.NewOrder;
import io.jethro.order.OrderService;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Routes a fused order delta through the SAME deterministic gate chain the direct paths used (ADR-0055
 * §5), so the fusion layer replaces the sources as the order ORIGIN without weakening any guardrail:
 *
 * <ol>
 *   <li><b>Paper execution</b>: fills are ALWAYS internal ({@code SimulatedExecutor}) — there is no
 *       real-broker path — so routing runs on any feed (incl. a LIVE feed = paper trading on real marks).
 *       The real-money guard is ADR-0015 (extract the {@code order} module before wiring a real broker),
 *       not a feed-mode check.</li>
 *   <li><b>Firm breaker</b> (ADR-0027): no new exposure while halted.</li>
 *   <li><b>Backtest support</b> (ADR-0049): only names the OOS selector found a tradable algo for — the
 *       deterministic backtest stays the hard gate; AI/social never order an unvalidated name.</li>
 *   <li><b>Pre-trade guardrail</b>: per-book risk limits; a rejection vetoes the order.</li>
 * </ol>
 *
 * <p><b>How the delta is executed (ADR-0084).</b> A delta that INCREASES risk is POSTED — a DAY LIMIT
 * at the arrival mark — and a delta that REDUCES risk crosses as MARKET. See {@link #route}. No model
 * number is used — only the deterministic delta quantity and the instrument's own mark — so invariant 7
 * / ADR-0016 hold.
 */
public final class FusionExecutor {

    private static final Logger log = LoggerFactory.getLogger(FusionExecutor.class);

    /** Every order this layer originates carries it; how {@link #cancelStalePassiveOrders} finds its own. */
    static final String KEY_PREFIX = "fusion:";

    /** The scale a posted limit price is stated at — the price scale the fill and mark records use. */
    private static final int PRICE_SCALE = 6;

    /** Outcome of a routing attempt — {@code routed} true only when an order was actually submitted. */
    public record Result(String instrument, String book, String side, BigDecimal qty, boolean routed,
                        String reason, String orderId) {
        static Result vetoed(String instrument, String reason) {
            return new Result(instrument, null, null, BigDecimal.ZERO, false, reason, null);
        }
    }

    private final StrategyProperties props;
    private final InstrumentRefSource refs;
    private final PreTradeGuardrail guardrail;
    private final TradingHaltSwitch halt;
    private final OrderService orderService;
    private final ObjectProvider<StrategySelector> selector;
    private final boolean requireBacktestSupport;

    /** Back-compat / default: the ADR-0049 backtest-support veto is REQUIRED (the strict, real-capital shape). */
    public FusionExecutor(StrategyProperties props, InstrumentRefSource refs,
                          PreTradeGuardrail guardrail, TradingHaltSwitch halt,
                          OrderService orderService, ObjectProvider<StrategySelector> selector) {
        this(props, refs, guardrail, halt, orderService, selector, true);
    }

    /**
     * @param requireBacktestSupport when false (ADR-0122 exploration mode, paper book only), the
     *   ADR-0049/0059 backtest-support veto is FAIL-OPEN: a name the OOS selector has not approved is
     *   NOT vetoed, so the desk may act on the combined forecast alone. Every deterministic floor still
     *   stands — firm breaker, per-book/firm exposure caps and the pre-trade guardrail below, the
     *   conviction floor above — so this removes a VALIDATION discipline, never a risk floor. Default
     *   true restores the strict shape exactly.
     */
    public FusionExecutor(StrategyProperties props, InstrumentRefSource refs,
                          PreTradeGuardrail guardrail, TradingHaltSwitch halt,
                          OrderService orderService, ObjectProvider<StrategySelector> selector,
                          boolean requireBacktestSupport) {
        this.props = props;
        this.refs = refs;
        this.guardrail = guardrail;
        this.halt = halt;
        this.orderService = orderService;
        this.selector = selector;
        this.requireBacktestSupport = requireBacktestSupport;
    }

    /** Routes one instrument's fused delta through the gates; returns what happened (never throws). */
    public Result route(String instrument, BigDecimal deltaQty) {
        return route(instrument, deltaQty, false);
    }

    /**
     * @param riskReducing the delta strictly shrinks |position| (see {@code TargetPlanner.isRiskReducing}).
     *   Such an order skips the ADR-0049 backtest-support veto — that gate answers "may we put risk ON
     *   this name?", and a name the selector has dropped is precisely one the desk should be getting
     *   OUT of, so letting it veto the exit would trap the book (ADR-0065). The deterministic floor is
     *   NOT relaxed: the firm breaker and the pre-trade guardrail below still apply unchanged.
     */
    public Result route(String instrument, BigDecimal deltaQty, boolean riskReducing) {
        try {
            // Execution is ALWAYS internal simulated fills (OrderService → SimulatedExecutor); there is no
            // real-broker path in the codebase, so routing under a LIVE feed is PAPER TRADING against real
            // marks — no real money. That is the whole point of a live test. The real-money guard is not a
            // feed-mode check here but ADR-0015: the `order` module MUST be extracted to its own JVM before
            // any real broker is wired, and that is where a live-execution gate belongs. (Supersedes the
            // old ADR-0019 sim-only routing restriction, which needlessly blocked testing on a real feed.)
            if (halt.isHalted()) {
                return Result.vetoed(instrument, "firm breaker halted (ADR-0027)");
            }
            InstrumentRef ref = refs.find(instrument).orElse(null);
            if (ref == null) {
                return Result.vetoed(instrument, "not in the instrument master");
            }
            // ADR-0078: round in the instrument's own contract terms. A share/FX unit still rounds to a
            // whole unit; a CONTRACT keeps the quantity scale the order and fill records already carry,
            // because one contract is worth price × multiplier and a correctly-sized position in it is
            // routinely a fraction of one. Always toward zero — rounding may only ever trade less.
            BigDecimal qty = TargetPlanner.tradableQuantity(deltaQty, ref.multiplier());
            if (qty.signum() == 0) {
                return Result.vetoed(instrument, "sub-unit delta — nothing to trade");
            }
            if (!riskReducing && !backtestSupported(instrument)) {
                return Result.vetoed(instrument, "not backtest-supported (ADR-0049) — no tradable OOS algo");
            }
            String book = props.bookFor(ref.assetClass());
            Side side = qty.signum() > 0 ? Side.BUY : Side.SELL;
            BigDecimal absQty = qty.abs();
            BigDecimal signed = side.signed(absQty);
            var rejection = guardrail.rejectionReason(book, instrument, signed);
            if (rejection.isPresent()) {
                return Result.vetoed(instrument, "guardrail: " + rejection.get());
            }
            // ADR-0084: an entry POSTS, an exit CROSSES. A risk-increasing delta rests as a DAY LIMIT
            // at the arrival mark and fills only if the market comes to it; a risk-reducing delta is a
            // MARKET order, because a cut that waits for a better price is not a cut.
            BigDecimal limit = riskReducing ? null : passiveLimitPrice(instrument, side);
            OrderType type = limit == null ? OrderType.MARKET : OrderType.LIMIT;
            var command = new NewOrder(KEY_PREFIX + instrument + ":" + UUID.randomUUID(),
                    book, instrument, side, type, absQty, limit,
                    limit == null ? TimeInForce.GTC : TimeInForce.DAY);
            var order = orderService.submit(command);
            log.info("FUSION routed {} {} {} {} → {} on {} ({}) — ADR-0055 sole-origin (sim)",
                    type, side, absQty.toPlainString(), instrument, order.status(), book, order.orderId());
            return new Result(instrument, book, side.name(), absQty, true, null, order.orderId());
        } catch (Exception e) {
            log.warn("fusion route of {} failed: {}", instrument, e.getMessage());
            return Result.vetoed(instrument, "error: " + e.getMessage());
        }
    }

    /**
     * The price a risk-INCREASING delta is posted at (ADR-0084): the instrument's current mark, taken
     * from the SAME cache the order module stamps as the order's TCA arrival price. Null when no mark
     * has arrived yet, in which case the caller falls back to MARKET and the order path rejects it for
     * want of data exactly as it always did.
     *
     * <p><b>Why the mark and not a chosen offset.</b> The mark is the mid; posting there is the
     * neutral passive price — it neither pays the spread (the marketable order's cost) nor claims a
     * rebate for capturing it (posting at the near touch would record NEGATIVE slippage and credit the
     * edge gate with a price improvement the desk merely hoped for). Measured implementation-shortfall
     * slippage on a fill here is therefore exactly zero by construction, which is the honest reading:
     * the desk paid the price it decided at. No dial and no invented number is introduced — the limit
     * IS the mark (invariant 7 / ADR-0016).
     *
     * <p>Rounded to the price scale the fill and mark records already carry, and AWAY from the side we
     * are buying — DOWN for a BUY, UP for a SELL — so a rounding step can only ever make the posted
     * price better for the desk, never worse (finance-math: explicit scale and rounding at every step).
     */
    private BigDecimal passiveLimitPrice(String instrument, Side side) {
        return passiveLimitPrice(orderService.lastPrice(instrument).orElse(null), side);
    }

    /** The arithmetic of the above, over a mark the caller has already read. Null mark ⇒ null. */
    static BigDecimal passiveLimitPrice(BigDecimal mid, Side side) {
        if (mid == null || mid.signum() <= 0) {
            return null;
        }
        return mid.setScale(PRICE_SCALE, side == Side.BUY ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    /**
     * Cancels every working order this layer posted (ADR-0084), returning how many were actually
     * cancelled. Called at the top of each planning cycle: the target book is about to be recomputed
     * from fresh forecasts and fresh marks, so any passive order still resting from the previous cycle
     * is stale intent — leaving it working would let a plan the desk no longer holds fill minutes later,
     * and re-posting alongside it would stack N cycles of the same intent into N times the position.
     *
     * <p>Scoped by the {@value #KEY_PREFIX} idempotency-key prefix, so it can only ever cancel orders
     * this layer originated — never the hedge advisor's, never an operator's. Cancellation is the
     * order module's CAS, so an order that filled in the same instant wins cleanly and is counted as a
     * fill, not a cancel.
     */
    public int cancelStalePassiveOrders() {
        int cancelled = 0;
        try {
            for (Order working : orderService.workingOrders()) {
                String key = working.idempotencyKey();
                if (key == null || !key.startsWith(KEY_PREFIX)) {
                    continue;
                }
                var after = orderService.cancel(working.orderId(),
                        "fusion re-plan — passive order superseded by a fresh target (ADR-0084)");
                if (after.map(o -> o.status() == OrderStatus.CANCELLED).orElse(false)) {
                    cancelled++;
                }
            }
        } catch (Exception e) {
            log.warn("fusion could not sweep its stale passive orders: {}", e.getMessage());
        }
        return cancelled;
    }

    /**
     * ADR-0049/0059: a name is tradable only when the OOS selector has a POSITIVE-EDGE algo for it.
     * FAIL-CLOSED for the sole-origin path (ADR-0059): an empty selection (not measured yet), an absent
     * name, or a NO_TRADE verdict (traded in the backtest and lost on both algos) all VETO — fusion must
     * not churn an unvalidated or explicitly-rejected name. (The direct strategy path stays fail-open;
     * fusion is higher-stakes.) No selector wired (persistence off) → the sim-gate + guardrail still protect.
     */
    private boolean backtestSupported(String instrument) {
        if (!requireBacktestSupport) {
            return true; // ADR-0122 exploration mode: fail-open on the OOS veto (paper book only)
        }
        StrategySelector s = selector.getIfAvailable();
        if (s == null) {
            return true;
        }
        var choice = s.selection().get(instrument);
        return choice != null && !io.jethro.trading.algo.strategy.SelectingStrategy.NO_TRADE.equals(choice.algo());
    }
}

package io.jethro.app.fusion;

import io.jethro.app.risk.TradingHaltSwitch;
import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.strategy.StrategySelector;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
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
 * The delta is submitted as a MARKET order. No model number is used — only the deterministic delta
 * quantity — so invariant 7 / ADR-0016 hold.
 */
public final class FusionExecutor {

    private static final Logger log = LoggerFactory.getLogger(FusionExecutor.class);

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

    public FusionExecutor(StrategyProperties props, InstrumentRefSource refs,
                          PreTradeGuardrail guardrail, TradingHaltSwitch halt,
                          OrderService orderService, ObjectProvider<StrategySelector> selector) {
        this.props = props;
        this.refs = refs;
        this.guardrail = guardrail;
        this.halt = halt;
        this.orderService = orderService;
        this.selector = selector;
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
            BigDecimal qty = deltaQty == null ? BigDecimal.ZERO : deltaQty.setScale(0, RoundingMode.DOWN);
            if (qty.signum() == 0) {
                return Result.vetoed(instrument, "sub-unit delta — nothing to trade");
            }
            InstrumentRef ref = refs.find(instrument).orElse(null);
            if (ref == null) {
                return Result.vetoed(instrument, "not in the instrument master");
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
            var command = new NewOrder("fusion:" + instrument + ":" + UUID.randomUUID(),
                    book, instrument, side, OrderType.MARKET, absQty, null);
            var order = orderService.submit(command);
            log.info("FUSION routed {} {} {} → {} on {} ({}) — ADR-0055 sole-origin (sim)",
                    side, absQty.toPlainString(), instrument, order.status(), book, order.orderId());
            return new Result(instrument, book, side.name(), absQty, true, null, order.orderId());
        } catch (Exception e) {
            log.warn("fusion route of {} failed: {}", instrument, e.getMessage());
            return Result.vetoed(instrument, "error: " + e.getMessage());
        }
    }

    /**
     * ADR-0049/0059: a name is tradable only when the OOS selector has a POSITIVE-EDGE algo for it.
     * FAIL-CLOSED for the sole-origin path (ADR-0059): an empty selection (not measured yet), an absent
     * name, or a NO_TRADE verdict (traded in the backtest and lost on both algos) all VETO — fusion must
     * not churn an unvalidated or explicitly-rejected name. (The direct strategy path stays fail-open;
     * fusion is higher-stakes.) No selector wired (persistence off) → the sim-gate + guardrail still protect.
     */
    private boolean backtestSupported(String instrument) {
        StrategySelector s = selector.getIfAvailable();
        if (s == null) {
            return true;
        }
        var choice = s.selection().get(instrument);
        return choice != null && !io.jethro.trading.algo.strategy.SelectingStrategy.NO_TRADE.equals(choice.algo());
    }
}

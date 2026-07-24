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
 *   <li><b>Sim-only</b> (ADR-0019): never routes unless the running feed mode is SIM — a hard gate, the
 *       fusion layer can no more reach a real broker than the strategy could.</li>
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
        try {
            if (!"SIM".equals(io.jethro.messaging.Provenance.mode().name())) {
                return Result.vetoed(instrument, "not SIM — fusion routing is sim-only (ADR-0019)");
            }
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
            if (refs.monitorOnly(instrument)) {
                // ADR-0060 §3: a discovery-promoted name is monitor-only — it flows into marks/indicators/
                // signals but cannot trade until its ADV is measured and it clears the OOS gate. Growth
                // never bleeds risk. (The OOS backtest gate below would also veto it, but this is explicit.)
                return Result.vetoed(instrument, "monitor-only (ADR-0060) — discovered name not yet graduated to trading");
            }
            if (!backtestSupported(instrument)) {
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

package io.jethro.app.hypothesis;

import io.jethro.app.strategy.StrategyProperties;
import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * The deterministic quant layer for a hypothesis (ADR-0022): the model proposed a
 * direction; this code owns every number. It validates the instrument, sizes to the
 * configured target notional (never using the model's ordinal conviction as a multiplier),
 * routes to the book that fits the asset class, and runs the read-only pre-trade guardrail.
 * The result is a candidate a human executes from the ticket (ADR-0018) — this slice never
 * auto-submits; bounded autonomy (ADR-0022) switches on once the backtest bridge (step 8)
 * can validate a thesis's edge.
 *
 * <p>Sizing mirrors the strategy's notional sizing (target / (price × multiplier), rounded
 * down, per-class cap) but without vol-scaling — a hypothesis carries no signal vol.
 * A full historical backtest of the thesis is deferred to step 8 (replay); noted, not faked.
 */
public final class HypothesisEvaluator {

    /** Why a hypothesis did or didn't become an actionable candidate. */
    public enum Verdict { ADMISSIBLE, UNKNOWN_INSTRUMENT, NO_MARK, UNSIZEABLE, BLOCKED }

    /** A hypothesis after the quant layer: the deterministic sizing/verdict the model never saw. */
    public record Evaluated(Hypothesis hypothesis, Verdict verdict, String book,
                            BigDecimal quantity, BigDecimal price, String note) {
    }

    private final InstrumentRefSource refs;
    private final PreTradeGuardrail guardrail;
    private final StrategyProperties sizing;

    public HypothesisEvaluator(InstrumentRefSource refs, PreTradeGuardrail guardrail, StrategyProperties sizing) {
        this.refs = refs;
        this.guardrail = guardrail;
        this.sizing = sizing;
    }

    /** Evaluates a hypothesis against live marks. {@code marks}: instrumentId → current price. */
    public Evaluated evaluate(Hypothesis h, Map<String, BigDecimal> marks) {
        Optional<InstrumentRef> ref = refs.find(h.instrumentId());
        if (ref.isEmpty()) {
            return verdict(h, Verdict.UNKNOWN_INSTRUMENT, null, null, null, "not in the instrument master");
        }
        BigDecimal price = marks.get(h.instrumentId());
        if (price == null || price.signum() <= 0) {
            return verdict(h, Verdict.NO_MARK, null, null, null, "no live mark to value it");
        }
        String assetClass = ref.get().assetClass();
        String book = sizing.bookFor(assetClass);
        BigDecimal multiplier = ref.get().multiplier();
        BigDecimal notionalPerUnit = price.multiply(multiplier);
        BigDecimal qty = sizing.targetNotional().divide(notionalPerUnit, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            // One unit already exceeds the per-class order cap → unsizeable, never round up.
            if (notionalPerUnit.compareTo(sizing.maxOrderNotionalFor(assetClass)) > 0) {
                return verdict(h, Verdict.UNSIZEABLE, book, null, price,
                        "one unit (" + plain(notionalPerUnit) + ") exceeds the " + assetClass + " order cap");
            }
            qty = BigDecimal.ONE;
        }
        BigDecimal signed = h.direction().signed(qty);
        Optional<String> rejection = guardrail.rejectionReason(book, h.instrumentId(), signed);
        if (rejection.isPresent()) {
            return verdict(h, Verdict.BLOCKED, book, qty, price, rejection.get());
        }
        return verdict(h, Verdict.ADMISSIBLE, book, qty, price, "pre-trade check passed");
    }

    private static Evaluated verdict(Hypothesis h, Verdict v, String book,
                                     BigDecimal qty, BigDecimal price, String note) {
        return new Evaluated(h, v, book, qty, price, note);
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

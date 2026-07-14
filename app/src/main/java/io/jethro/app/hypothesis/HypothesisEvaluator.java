package io.jethro.app.hypothesis;

import io.jethro.app.backtest.BacktestResult;
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

    /** Backtest support for the thesis's instrument (the future bounded-autonomy gate,
     *  ADR-0022): the strategy's measured PnL on this name over the sim tape. {@code supports}
     *  = net-positive on some trades. Null when no backtest was supplied. */
    public record Backtest(BigDecimal pnl, int trades, boolean supports) {
    }

    /** A hypothesis after the quant layer: the deterministic sizing/verdict the model never saw. */
    public record Evaluated(Hypothesis hypothesis, Verdict verdict, String book,
                            BigDecimal quantity, BigDecimal price, String note, Backtest backtest) {

        /** Same evaluation at a different quantity (probation resizing, ADR-0027). */
        public Evaluated withQuantity(BigDecimal newQuantity) {
            return new Evaluated(hypothesis, verdict, book, newQuantity, price, note, backtest);
        }
    }

    private final InstrumentRefSource refs;
    private final PreTradeGuardrail guardrail;
    private final StrategyProperties sizing;
    private final String book; // the AI sleeve — separate from the momentum strategy's books

    public HypothesisEvaluator(InstrumentRefSource refs, PreTradeGuardrail guardrail,
                               StrategyProperties sizing, String book) {
        this.refs = refs;
        this.guardrail = guardrail;
        this.sizing = sizing;
        this.book = book;
    }

    /** Evaluates a hypothesis against live marks, no backtest context. */
    public Evaluated evaluate(Hypothesis h, Map<String, BigDecimal> marks) {
        return evaluate(h, marks, Map.of());
    }

    /**
     * Evaluates a hypothesis against live marks, attaching the backtest support for its
     * instrument. {@code marks}: instrumentId → current price; {@code backtest}: instrumentId
     * → the strategy's measured result on that name (may be empty).
     */
    public Evaluated evaluate(Hypothesis h, Map<String, BigDecimal> marks,
                              Map<String, BacktestResult.InstrumentResult> backtest) {
        Backtest bt = backtestFor(h.instrumentId(), backtest);
        Optional<InstrumentRef> ref = refs.find(h.instrumentId());
        if (ref.isEmpty()) {
            return verdict(h, Verdict.UNKNOWN_INSTRUMENT, null, null, null, "not in the instrument master", bt);
        }
        BigDecimal price = marks.get(h.instrumentId());
        if (price == null || price.signum() <= 0) {
            return verdict(h, Verdict.NO_MARK, null, null, null, "no live mark to value it", bt);
        }
        String assetClass = ref.get().assetClass();
        // The AI sleeve trades its own book (not routed to the strategy's ALPHA/MACRO), so the
        // momentum algo never flattens an AI-opened position (ADR-0022).
        BigDecimal multiplier = ref.get().multiplier();
        BigDecimal notionalPerUnit = price.multiply(multiplier);
        BigDecimal qty = sizing.targetNotional().divide(notionalPerUnit, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            // One unit already exceeds the per-class order cap → unsizeable, never round up.
            if (notionalPerUnit.compareTo(sizing.maxOrderNotionalFor(assetClass)) > 0) {
                return verdict(h, Verdict.UNSIZEABLE, book, null, price,
                        "one unit (" + plain(notionalPerUnit) + ") exceeds the " + assetClass + " order cap", bt);
            }
            qty = BigDecimal.ONE;
        }
        BigDecimal signed = h.direction().signed(qty);
        Optional<String> rejection = guardrail.rejectionReason(book, h.instrumentId(), signed);
        if (rejection.isPresent()) {
            return verdict(h, Verdict.BLOCKED, book, qty, price, rejection.get(), bt);
        }
        return verdict(h, Verdict.ADMISSIBLE, book, qty, price, "pre-trade check passed", bt);
    }

    private static Backtest backtestFor(String instrumentId, Map<String, BacktestResult.InstrumentResult> backtest) {
        BacktestResult.InstrumentResult ir = backtest.get(instrumentId);
        if (ir == null) {
            return null;
        }
        BigDecimal pnl = ir.realizedPnl().add(ir.unrealizedPnl());
        return new Backtest(pnl, ir.trades(), pnl.signum() > 0 && ir.trades() > 0);
    }

    private static Evaluated verdict(Hypothesis h, Verdict v, String book,
                                     BigDecimal qty, BigDecimal price, String note, Backtest bt) {
        return new Evaluated(h, v, book, qty, price, note, bt);
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

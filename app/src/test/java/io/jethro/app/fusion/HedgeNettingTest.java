package io.jethro.app.fusion;

import io.jethro.trading.riskpnl.PositionRisk;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0091 — the fusion planner nets against the books it ROUTES INTO, never against the hedge
 * overlay. Counting the hedger's leg as the desk's own inventory makes the two loops a closed
 * positive feedback: the hedger buys {@code h} of the proxy, the planner reads its gap as
 * {@code target − (own + h)} and opens {@code −h} in a strategy book to close it, and since the
 * hedger's own target is unchanged by that, nothing converges — both legs grow together and the
 * hedge ends up exactly cancelled while both books pay the spread.
 *
 * <p>Exact decimal quantities throughout (invariant 1); the figures are the live ES book that
 * exposed this — {@code MACRO −0.060884}, {@code HEDGE +0.050697}, planner target {@code −0.160100}.
 */
class HedgeNettingTest {

    private static final String HEDGE_BOOK = "HEDGE";
    /** assumedCorrelation, unitNotional, bufferFraction, adjustmentRate (rate 1.0 = converge at once). */
    private static final FusionPlanner.Params PLAN =
            new FusionPlanner.Params(0.5, BigDecimal.valueOf(10_000), 0.0, 1.0);

    private static PositionRisk pos(String book, String instrument, String qty) {
        BigDecimal q = new BigDecimal(qty);
        return new PositionRisk(book, instrument, "FUTURE", "USD", q,
                BigDecimal.ZERO, BigDecimal.ZERO, false, 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void theHedgersLegIsNotTheDesksInventory() {
        List<PositionRisk> book = List.of(
                pos("MACRO", "ES", "-0.060884"),
                pos(HEDGE_BOOK, "ES", "0.050697"));

        Map<String, BigDecimal> routed = FusionConfig.routedBookPositions(book, HEDGE_BOOK);

        assertEquals(new BigDecimal("-0.060884"), routed.get("ES"),
                "the planner sees only what it can trade — the strategy leg");
        // The old read summed every book: −0.060884 + 0.050697 = −0.010187, a near-flat position on a
        // contract the strategy book is genuinely short by more than four times that.
        Map<String, BigDecimal> everyBook = FusionConfig.routedBookPositions(book, null);
        assertEquals(new BigDecimal("-0.010187"), everyBook.get("ES"));
    }

    @Test
    void bookIdMatchIsCaseInsensitiveAndOtherBooksStillSum() {
        List<PositionRisk> book = List.of(
                pos("ALPHA", "AAPL", "-70"),
                pos("MACRO", "AAPL", "12"),
                pos("hedge", "ES", "0.050697"),
                pos("MACRO", "ES", "-0.060884"));

        Map<String, BigDecimal> routed = FusionConfig.routedBookPositions(book, HEDGE_BOOK);

        assertEquals(new BigDecimal("-58"), routed.get("AAPL"),
                "two strategy books in one name still net against each other");
        assertEquals(new BigDecimal("-0.060884"), routed.get("ES"),
                "the hedge book is excluded whatever the case of its id");
    }

    @Test
    void aNameTheHedgerDoesNotHoldIsUnaffectedQuantityForQuantity() {
        List<PositionRisk> book = List.of(
                pos("ALPHA", "JNJ", "-20"),
                pos(HEDGE_BOOK, "ES", "0.050697"));

        assertEquals(FusionConfig.routedBookPositions(book, null).get("JNJ"),
                FusionConfig.routedBookPositions(book, HEDGE_BOOK).get("JNJ"),
                "excluding the hedge book changes nothing it does not hold");
    }

    /**
     * The whole point, at the planner: with the same target and the same real strategy position, the
     * old read converges the strategy leg to {@code target − h} and the new one to {@code target}.
     * The difference is exactly the hedger's own notional, carried as gross for nothing.
     */
    @Test
    void theRatchetHasNoFixedPointUnderTheOldRead() {
        BigDecimal target = new BigDecimal("-0.160100");
        BigDecimal hedgeLeg = new BigDecimal("0.050697");
        BigDecimal strategyLeg = new BigDecimal("-0.060884");

        // NEW: the gap is measured against the strategy leg alone, so it closes onto the target.
        BigDecimal newDelta = TargetPlanner.orderDelta(target, strategyLeg, 0.0, 1.0);
        assertEquals(0, target.compareTo(strategyLeg.add(newDelta)),
                "the strategy leg settles at the target the desk actually asked for");

        // OLD: the gap was measured against strategy + hedge, so the leg overshoots by the hedge.
        BigDecimal oldDelta = TargetPlanner.orderDelta(target, strategyLeg.add(hedgeLeg), 0.0, 1.0);
        BigDecimal oldLeg = strategyLeg.add(oldDelta);
        assertEquals(0, target.subtract(hedgeLeg).compareTo(oldLeg),
                "the old read settles the strategy leg at target − h, cancelling the hedge exactly");

        // Firm net under the old read is the target itself: the hedge contributes nothing.
        assertEquals(0, target.compareTo(oldLeg.add(hedgeLeg)),
                "old: the hedger's leg is fully neutralised by the strategy leg opened against it");
        // Under the new read the hedge offsets, so the firm's net is smaller than the desk's view.
        BigDecimal newFirmNet = target.add(hedgeLeg);
        assertTrue(newFirmNet.abs().compareTo(target.abs()) < 0,
                "new: the hedge actually reduces the firm's net exposure");

        // Gross in the contract: |leg| + |h|. The old read carries the hedge twice over.
        BigDecimal oldGrossQty = oldLeg.abs().add(hedgeLeg.abs());
        BigDecimal newGrossQty = target.abs().add(hedgeLeg.abs());
        assertEquals(new BigDecimal("0.261494"), oldGrossQty);
        assertEquals(new BigDecimal("0.210797"), newGrossQty);
        assertEquals(0, hedgeLeg.compareTo(oldGrossQty.subtract(newGrossQty)),
                "the gross removed is exactly the hedger's own size");
    }

    @Test
    void aStepUnderTheOldReadReopensWhateverTheHedgerJustBought() {
        // One cycle in the loop, rate 1.0 for clarity: the desk is exactly on target, the hedger buys
        // h, and the old read immediately plans a sale of h into a strategy book. The new read plans
        // nothing, because nothing the desk holds has changed.
        BigDecimal target = new BigDecimal("-0.160100");
        BigDecimal strategyLeg = new BigDecimal("-0.160100"); // already on target
        BigDecimal hedgerJustBought = new BigDecimal("0.050697");

        assertEquals(0, TargetPlanner.orderDelta(target, strategyLeg, 0.0, 1.0).signum(),
                "new read: on target, nothing to do");
        assertEquals(0, hedgerJustBought.negate()
                        .compareTo(TargetPlanner.orderDelta(target, strategyLeg.add(hedgerJustBought), 0.0, 1.0)),
                "old read: sell exactly what the hedger bought, every time it acts");
    }
}

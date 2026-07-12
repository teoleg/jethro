package io.jethro.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value tests per the finance-math skill. Worked example (hand-computed):
 * buy 100 @ 10, buy 50 @ 13 → 150 @ avgCost 11.00;
 * sell 120 @ 12 → realized (12 − 11) × 120 = 120.00, position 30 @ 11.00.
 */
class PositionsTest {

    private static final BookId BOOK = new BookId("B1");
    private static final InstrumentId INST = new InstrumentId("I1");
    private static final BigDecimal M1 = BigDecimal.ONE;

    private static Fill fill(String id, Side side, String qty, String price) {
        return new Fill(id, "O1", BOOK, INST, side, new BigDecimal(qty), new BigDecimal(price), Instant.EPOCH);
    }

    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    @Test
    void workedExampleFromFinanceMathSkill() {
        var p0 = Position.flat(BOOK, INST);

        var a1 = Positions.applyFill(p0, fill("f1", Side.BUY, "100", "10"), M1);
        assertValue("100", a1.position().quantity());
        assertValue("10", a1.position().avgCost());
        assertValue("0", a1.realizedPnl());

        var a2 = Positions.applyFill(a1.position(), fill("f2", Side.BUY, "50", "13"), M1);
        assertValue("150", a2.position().quantity());
        assertValue("11.00", a2.position().avgCost()); // (100·10 + 50·13) / 150
        assertValue("0", a2.realizedPnl());

        var a3 = Positions.applyFill(a2.position(), fill("f3", Side.SELL, "120", "12"), M1);
        assertValue("30", a3.position().quantity());
        assertValue("11.00", a3.position().avgCost()); // reduce leaves avgCost unchanged
        assertValue("120.00", a3.realizedPnl());       // (12 − 11) × 120
    }

    @Test
    void flatToShortAndProfitableCover() {
        var p0 = Position.flat(BOOK, INST);

        var s1 = Positions.applyFill(p0, fill("f1", Side.SELL, "50", "20"), M1);
        assertValue("-50", s1.position().quantity());
        assertValue("20", s1.position().avgCost());

        // Cover at 18: short profit (20 − 18) × 50 = 100
        var s2 = Positions.applyFill(s1.position(), fill("f2", Side.BUY, "50", "18"), M1);
        assertTrue(s2.position().isFlat());
        assertValue("0", s2.position().avgCost()); // reset on flat
        assertValue("100.00", s2.realizedPnl());
    }

    @Test
    void crossThroughFlatSplitsIntoCloseAndOpen() {
        var p0 = Position.flat(BOOK, INST);
        var long30 = Positions.applyFill(p0, fill("f1", Side.BUY, "30", "11"), M1).position();

        // Sell 50 @ 12: close 30 (realized (12 − 11) × 30 = 30), open short 20 @ 12
        var crossed = Positions.applyFill(long30, fill("f2", Side.SELL, "50", "12"), M1);
        assertValue("-20", crossed.position().quantity());
        assertValue("12", crossed.position().avgCost());
        assertValue("30.00", crossed.realizedPnl());
    }

    @Test
    void contractMultiplierScalesRealizedPnl() {
        // Futures-style: multiplier 50. Long 2 @ 4500, sell 2 @ 4510:
        // realized = (4510 − 4500) × 2 × 50 = 1000
        var p0 = Position.flat(BOOK, INST);
        var m = new BigDecimal("50");
        var opened = Positions.applyFill(p0, fill("f1", Side.BUY, "2", "4500"), m).position();
        var closed = Positions.applyFill(opened, fill("f2", Side.SELL, "2", "4510"), m);
        assertValue("1000.00", closed.realizedPnl());
        assertTrue(closed.position().isFlat());
    }

    @Test
    void avgCostDivisionUsesDeclaredScaleAndRounding() {
        // buy 1 @ 10, buy 2 @ 11 → avg = 32/3 = 10.66666666.67 → 10.66666667 (scale 8, HALF_EVEN)
        var p0 = Position.flat(BOOK, INST);
        var a1 = Positions.applyFill(p0, fill("f1", Side.BUY, "1", "10"), M1).position();
        var a2 = Positions.applyFill(a1, fill("f2", Side.BUY, "2", "11"), M1).position();
        assertEquals(new BigDecimal("10.66666667"), a2.avgCost());
    }

    @Test
    void lossMakingReduceIsNegative() {
        var p0 = Position.flat(BOOK, INST);
        var long100 = Positions.applyFill(p0, fill("f1", Side.BUY, "100", "10"), M1).position();
        var reduced = Positions.applyFill(long100, fill("f2", Side.SELL, "40", "9.50"), M1);
        assertValue("-20.00", reduced.realizedPnl()); // (9.50 − 10) × 40
        assertValue("60", reduced.position().quantity());
    }

    @Test
    void zeroQuantityFillIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> fill("f1", Side.BUY, "0", "10"));
    }

    @Test
    void fillForWrongPositionIsRejected() {
        var other = Position.flat(new BookId("B2"), INST);
        assertThrows(IllegalArgumentException.class,
                () -> Positions.applyFill(other, fill("f1", Side.BUY, "1", "10"), M1));
    }
}

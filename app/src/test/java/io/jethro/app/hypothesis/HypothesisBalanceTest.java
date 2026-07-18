package io.jethro.app.hypothesis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Directional-balance labelling + prompt line (ADR-0036). */
class HypothesisBalanceTest {

    @Test
    void tooFewCallsAreBalancedAndSilent() {
        assertEquals("balanced", HypothesisBalance.skew(2, 0));
        assertEquals("", HypothesisBalance.promptLine(2, 0), "no line until there are enough calls");
    }

    @Test
    void lopsidedLongIsFlagged() {
        assertEquals("skewing long", HypothesisBalance.skew(7, 1));
        assertEquals("recent calls: 7 long, 1 short (skewing long)", HypothesisBalance.promptLine(7, 1));
    }

    @Test
    void lopsidedShortIsFlagged() {
        assertEquals("skewing short", HypothesisBalance.skew(1, 5));
    }

    @Test
    void aMixIsBalanced() {
        assertEquals("balanced", HypothesisBalance.skew(3, 2));
        assertEquals("recent calls: 3 long, 2 short (balanced)", HypothesisBalance.promptLine(3, 2));
    }
}

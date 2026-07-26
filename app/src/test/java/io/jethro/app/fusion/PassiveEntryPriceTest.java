package io.jethro.app.fusion;

import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADR-0084 — the price a risk-INCREASING fusion delta is posted at. The limit IS the instrument's
 * own mark (no dial, no offset, invariant 7 / ADR-0016); the only arithmetic is the scale and the
 * direction it rounds in, and that direction is the whole safety property: rounding may make the
 * posted price better for the desk, never worse.
 */
class PassiveEntryPriceTest {

    @Test
    void theLimitIsTheMarkItself() {
        BigDecimal mark = new BigDecimal("429.327284");
        assertEquals(mark, FusionExecutor.passiveLimitPrice(mark, Side.BUY));
        assertEquals(mark, FusionExecutor.passiveLimitPrice(mark, Side.SELL));
    }

    @Test
    void roundingNeverPostsAWorsePriceThanTheMark() {
        // A mark carrying more precision than the price scale: a BUY may not be rounded UP into
        // paying more than the mid, nor a SELL rounded DOWN into receiving less.
        BigDecimal mark = new BigDecimal("429.3272845999");
        assertEquals(new BigDecimal("429.327284"), FusionExecutor.passiveLimitPrice(mark, Side.BUY),
                "a BUY rounds DOWN — never bid above the mark");
        assertEquals(new BigDecimal("429.327285"), FusionExecutor.passiveLimitPrice(mark, Side.SELL),
                "a SELL rounds UP — never offer below the mark");
    }

    @Test
    void aCoarserMarkIsStatedAtThePriceScaleUnchanged() {
        assertEquals(new BigDecimal("100.070000"), FusionExecutor.passiveLimitPrice(
                new BigDecimal("100.07"), Side.BUY), "no value change, just the declared scale");
    }

    @Test
    void noMarkMeansNoPassivePrice() {
        // The caller then falls back to MARKET and the order path rejects it for want of data
        // exactly as it always did — a name with no mark must not be posted at an invented price.
        assertNull(FusionExecutor.passiveLimitPrice(null, Side.BUY));
        assertNull(FusionExecutor.passiveLimitPrice(BigDecimal.ZERO, Side.BUY));
        assertNull(FusionExecutor.passiveLimitPrice(new BigDecimal("-1.5"), Side.SELL));
    }
}

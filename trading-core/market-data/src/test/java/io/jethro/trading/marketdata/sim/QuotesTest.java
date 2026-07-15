package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Scaled-long quote synthesis (ADR-0025): the sim's bid/ask around the mid must equal the
 * execution model's synthetic touch exactly — one spread, two representations.
 */
class QuotesTest {

    @Test
    void equitySpreadMatchesTheExecutorsTouchExactly() {
        // mid 190.000000, EQUITY 5bp (500 centi-bps): half = 190e6 × 500 / 2e6 = 47,500.
        var spec = new Quotes.QuoteSpec(500, false);
        assertEquals(189_952_500L, Quotes.bidScaled(190_000_000L, spec));
        assertEquals(190_047_500L, Quotes.askScaled(190_000_000L, spec), "ask = 190.0475 = 190 × 1.00025");
    }

    @Test
    void swapSpreadIsAdditiveInRateBp() {
        // par 4.310000 (4,310,000 scaled), SWAP 0.4bp (40 centi-bps): half = 40 × 50 = 2,000
        // scaled = 0.002 in percent = 0.2bp each side — the executor's additive convention.
        var spec = new Quotes.QuoteSpec(40, true);
        assertEquals(4_308_000L, Quotes.bidScaled(4_310_000L, spec));
        assertEquals(4_312_000L, Quotes.askScaled(4_310_000L, spec));
    }

    @Test
    void zeroSpreadQuotesAtTheMid() {
        var spec = new Quotes.QuoteSpec(0, false);
        assertEquals(190_000_000L, Quotes.bidScaled(190_000_000L, spec));
        assertEquals(190_000_000L, Quotes.askScaled(190_000_000L, spec));
    }
}

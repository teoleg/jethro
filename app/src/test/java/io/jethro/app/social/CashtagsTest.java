package io.jethro.app.social;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticker extraction (ADR-0050): cashtags for social, exchange-qualified tickers for news prose. */
class CashtagsTest {

    @Test
    void extractsCashtags() {
        assertEquals(Set.of("NVDA", "AAPL"), Cashtags.extractCashtags("watching $NVDA and $aapl today"));
    }

    @Test
    void newsExchangeQualifiedTickersAreExtracted() {
        String news = "Nvidia (NASDAQ: NVDA) jumped after JPMorgan (NYSE: JPM) raised its target; "
                + "SAP (ETR: SAP) also rose.";
        Set<String> got = Cashtags.extractNewsTickers(news);
        assertTrue(got.contains("NVDA"));
        assertTrue(got.contains("JPM"));
        assertTrue(got.contains("SAP"));
    }

    @Test
    void nonTickerAbbreviationsInParensAreNotMistakenForTickers() {
        // The exchange-qualified requirement is what prevents a false-positive flood from press releases.
        String macro = "The FOMC held rates; GDP and CPI data (BLS) were mixed, the CEO said.";
        assertTrue(Cashtags.extractNewsTickers(macro).isEmpty(), "bare parenthetical abbreviations are not tickers");
    }

    @Test
    void newsExtractorStillCatchesACashtagIfPresent() {
        assertTrue(Cashtags.extractNewsTickers("rumor mill on $TSLA and (NASDAQ: MSFT)").containsAll(Set.of("TSLA", "MSFT")));
    }

    @Test
    void handlesBareExchangePrefixWithoutParens() {
        assertTrue(Cashtags.extractNewsTickers("shares of NYSE: KO were flat").contains("KO"));
    }

    @Test
    void emptyOnNoMentions() {
        assertFalse(Cashtags.extractNewsTickers("the central bank signalled patience on policy").iterator().hasNext());
    }
}

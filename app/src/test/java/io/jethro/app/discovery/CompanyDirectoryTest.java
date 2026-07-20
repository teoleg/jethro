package io.jethro.app.discovery;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Company-name → ticker resolution for news discovery (ADR-0045/0050 §7). */
class CompanyDirectoryTest {

    private static final CompanyDirectory DIR = CompanyDirectory.fromClasspath("discovery/company-tickers.csv");

    @Test
    void bundledDirectoryLoads() {
        assertTrue(DIR.size() > 30, "the curated directory should load its entries");
    }

    @Test
    void resolvesCompanyNamesInHeadlines() {
        assertTrue(DIR.resolve("Tesla recalls thousands of vehicles").contains("TSLA"));
        assertTrue(DIR.resolve("Goldman Sachs raises its price target").contains("GS"));
        assertTrue(DIR.resolve("Coca-Cola beats on earnings").contains("KO"));
    }

    @Test
    void aliasesResolveToTheSameTicker() {
        assertTrue(DIR.resolve("Facebook parent reports").contains("META"));
        assertTrue(DIR.resolve("Google unveils a new model").contains("GOOGL"));
    }

    @Test
    void wholePhraseOnly_noSubstringFalsePositive() {
        // "Intel" must not fire inside "intelligence".
        assertFalse(DIR.resolve("new artificial intelligence rules proposed").contains("INTC"));
    }

    @Test
    void unrelatedMacroTextResolvesNothing() {
        assertTrue(DIR.resolve("The central bank held rates as inflation cooled").isEmpty());
    }
}

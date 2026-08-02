package io.muniworld.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.muniworld.bond.MuniBondService;
import io.muniworld.domain.Bond;
import io.muniworld.domain.BondRow;
import io.muniworld.domain.PriceQuote;
import io.muniworld.store.MuniLmdbStore;
import io.muniworld.store.MuniSearchIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The current-price store (ADR-0015) round-trips a quote and — the point of it — turns a <b>terms-only</b>
 * bond's blank economics into real computed indicators once a market price lands, with the price's as-of and
 * source carried through. No quote ⇒ blank; a quote ⇒ economics. Nothing invented.
 */
class MuniPriceStoreTest {

    private Path dir;
    private MuniLmdbStore store;
    private MuniPriceStore prices;
    private MuniBondService bonds;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("muni-px");
        store = new MuniLmdbStore(dir.toString(), 16);
        prices = new MuniPriceStore(store, mapper);
        bonds = new MuniBondService(new MuniSearchIndex(store), mapper, prices);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void quoteRoundTrips() {
        prices.put(new PriceQuote("649122AC9", new BigDecimal("108.25"), LocalDate.of(2026, 8, 2), "msrb-rtrs"));
        PriceQuote q = prices.get("649122AC9").orElseThrow();
        assertEquals(0, new BigDecimal("108.25").compareTo(q.price()));
        assertEquals(LocalDate.of(2026, 8, 2), q.asOf());
        assertEquals("msrb-rtrs", q.source());
        assertTrue(prices.get("000000000").isEmpty());
    }

    @Test
    void priceLandingFillsTermsOnlyEconomics() {
        // a terms-only bond (OS-extracted): coupon + maturity, NO price
        Bond termsOnly = new Bond("649122AC9", "NYC Water", new BigDecimal("5.000"),
                LocalDate.of(2035, 6, 1), null, null, "tax-exempt", null, null, "Aa1", "3603900000");
        bonds.index(termsOnly);

        BondRow blank = bonds.get("649122AC9").orElseThrow();
        assertTrue(Double.isNaN(blank.price()) && Double.isNaN(blank.ytm()), "no quote → blank economics");

        // a real market quote lands
        prices.put(new PriceQuote("649122AC9", new BigDecimal("108.25"), LocalDate.of(2026, 8, 2), "msrb-rtrs"));

        BondRow priced = bonds.get("649122AC9").orElseThrow();
        assertEquals(108.25, priced.price(), 1e-9, "the quote drives the price");
        assertTrue(priced.ytm() > 0 && priced.ytm() < 5.0, "premium bond yields below coupon, got " + priced.ytm());
        assertEquals("2026-08-02", priced.priceAsOf(), "the price carries its as-of");
        assertEquals("msrb-rtrs", priced.priceSource(), "and its provenance");
    }
}

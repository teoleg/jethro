package io.muniworld.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.muniworld.bond.MuniBondService;
import io.muniworld.domain.BondRow;
import io.muniworld.store.MuniLmdbStore;
import io.muniworld.store.MuniSearchIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the ADR-0004 securities pipeline, entirely offline: a stub fetcher lands JSON, the
 * {@link IngestService} normalises it through a {@link FieldMap} and indexes it into the real LMDB B-tree
 * (ADR-0013), and the bonds come back out of the search index as {@link BondRow}s with live indicators —
 * so land → parse → normalise → index → search is exercised without touching the network.
 */
class IngestServiceTest {

    private Path dir;
    private MuniLmdbStore store;
    private MuniBondService bonds;
    private IngestService ingest;
    // JavaTimeModule so Bond's LocalDate fields serialise to LMDB — Spring auto-registers it in production.
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final FieldMap MAP = new FieldMap(
            "cusip", "issuer", "coupon", "maturity", "dated", "price",
            "tax", "call_date", "call_price", "rating", "fips");

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("muni-ingest");
        store = new MuniLmdbStore(dir.toString(), 16);
        bonds = new MuniBondService(new MuniSearchIndex(store), mapper);
        ingest = new IngestService(mapper, new SecurityNormaliser(), bonds);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /** A connector that "lands" a fixed JSON body — no network, same code path as a real Socrata fetch. */
    private SourceConnector jsonConnector(String id, String json) {
        HttpFetcher stub = (sourceId, url) ->
                RawArtifact.of(sourceId, url, "application/json", json.getBytes(StandardCharsets.UTF_8));
        return new SocrataConnector(id, "stub.local", "abcd-1234", 1000, stub);
    }

    @Test
    void landsNormalisesIndexesAndSearches() {
        // two priced NYC Water bonds (CUSIP-6 649122) + one terms-only row (no price → kept, blank economics).
        String json = """
                [
                  {"cusip":"649122AC9","issuer":"NYC Water","coupon":"5.000","maturity":"2035-06-01",
                   "price":"108.25","tax":"Tax-Exempt","fips":"3603900000","rating":"Aa1"},
                  {"cusip":"649122AD7","issuer":"NYC Water","coupon":"5.250","maturity":"2041-06-01",
                   "price":"$104.50","tax":"Tax-Exempt","fips":"3603900000","rating":"Aa1",
                   "call_date":"2033-06-01","call_price":"100"},
                  {"cusip":"649122ZZ0","issuer":"NYC Water","coupon":"4.000","maturity":"2030-06-01"}
                ]
                """;

        IngestService.Summary sum = ingest.ingest(jsonConnector("socrata:test", json), MAP);

        assertTrue(sum.ok(), "ingest ran: " + sum.error());
        assertEquals(3, sum.rows());
        assertEquals(3, sum.indexed(), "all three rows indexed — two priced, one terms-only");
        assertEquals(0, sum.skipped(), "the price-less row is kept (terms-only), not quarantined");
        assertTrue(sum.bytes() > 0 && sum.sha256() != null, "the landing carries provenance");

        // the indexed bonds come back out of the LMDB search index, by issuer prefix, with indicators.
        List<BondRow> rows = bonds.byIssuer("649122");
        assertEquals(3, rows.size());
        BondRow ac9 = rows.stream().filter(r -> r.cusip().equals("649122AC9")).findFirst().orElseThrow();
        assertEquals(5.0, ac9.coupon(), 1e-9);
        assertTrue(ac9.ytm() > 0 && ac9.ytm() < 5.0, "premium bond yields below coupon, got " + ac9.ytm());
        assertTrue(ac9.currentYield() > 4.0 && ac9.currentYield() < 5.0, "5/108.25 ~ 4.6%");

        // the terms-only bond shows its terms but BLANK market economics (NaN → "—"), never a stale number.
        BondRow zz0 = rows.stream().filter(r -> r.cusip().equals("649122ZZ0")).findFirst().orElseThrow();
        assertEquals(4.0, zz0.coupon(), 1e-9, "terms are present");
        assertTrue(Double.isNaN(zz0.price()) && Double.isNaN(zz0.ytm()), "no price → no computed economics");
    }

    @Test
    void indexRowsTakesAlreadyInHandRows() {
        List<Map<String, Object>> rows = List.of(
                Map.of("cusip", "64972RAA0", "issuer", "NYW peer", "coupon", "3.5",
                        "maturity", "2032-06-01", "price", "99.00", "fips", "3603900000"));

        IngestService.Summary sum = ingest.indexRows(rows, MAP);

        assertEquals(1, sum.indexed());
        assertEquals(0, sum.skipped());
        assertEquals(1, bonds.byGeography("36").size(), "indexed into the geo tree under NY state FIPS");
    }
}

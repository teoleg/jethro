package io.muniworld.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the ADR-0013 ordered-key search works: issuer prefix (CUSIP-6), maturity range, coupon range, and
 * geography prefix all return the right CUSIPs in sorted order — which is only correct if the big-endian /
 * fixed-width key encoding in {@link MuniKeys} sorts as intended.
 */
class MuniSearchIndexTest {

    private Path dir;
    private MuniLmdbStore store;
    private MuniSearchIndex idx;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("muni-idx");
        store = new MuniLmdbStore(dir.toString(), 16);
        idx = new MuniSearchIndex(store);
        // Two NYW-style issuer CUSIP-6s (649122, 64972R) + one NY-water peer; FIPS NJ-Bergen vs NY.
        put("649122AB1", 2030, 4.000, "3403900000");
        put("649122AC9", 2035, 5.000, "3403900000");
        put("649122AD7", 2041, 5.250, "3403900000");
        put("64972RAA0", 2032, 3.500, "3603900000"); // different issuer, different geo
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void put(String cusip, int matYear, double couponPct, String fips) {
        idx.indexSecurity(cusip, LocalDate.of(matYear, 6, 1),
                MuniKeys.couponScaled(BigDecimal.valueOf(couponPct)), fips,
                cusip.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void issuerPrefixReturnsOnlyThatIssuersCusips() {
        List<String> a = idx.byIssuer("649122");
        assertEquals(List.of("649122AB1", "649122AC9", "649122AD7"), a, "sorted CUSIP-9s under the CUSIP-6");
        assertEquals(List.of("64972RAA0"), idx.byIssuer("64972R"));
    }

    @Test
    void maturityRangeIsInclusiveAndOrdered() {
        // maturities: AB1=2030, 64972R=2032, AC9=2035, AD7=2041
        List<String> mid = idx.byMaturityRange(LocalDate.of(2031, 1, 1), LocalDate.of(2036, 1, 1));
        assertEquals(List.of("64972RAA0", "649122AC9"), mid, "2032 then 2035, in date order");
        assertEquals(4, idx.byMaturityRange(LocalDate.of(2000, 1, 1), LocalDate.of(2099, 1, 1)).size());
    }

    @Test
    void couponRangeIsInclusiveAndOrdered() {
        long lo = MuniKeys.couponScaled(BigDecimal.valueOf(4.000));
        long hi = MuniKeys.couponScaled(BigDecimal.valueOf(5.000));
        // coupons: 64972R=3.50 (out), AB1=4.00, AC9=5.00, AD7=5.25 (out)
        List<String> band = idx.byCouponRange(lo, hi);
        assertEquals(List.of("649122AB1", "649122AC9"), band, "4.00% then 5.00%, in coupon order");
    }

    @Test
    void geographyPrefixMatchesStateAndCounty() {
        assertEquals(3, idx.byGeography("34").size(), "all NJ (state FIPS 34)");
        assertEquals(3, idx.byGeography("34039").size(), "the 34039 place");
        assertEquals(1, idx.byGeography("36").size(), "the NY one");
        assertTrue(idx.byGeography("99").isEmpty(), "no such geography");
    }

    @Test
    void pointLookupRoundTrips() {
        assertEquals("649122AC9", new String(idx.get("649122AC9").orElseThrow(), StandardCharsets.US_ASCII));
        assertTrue(idx.get("000000000").isEmpty());
    }
}

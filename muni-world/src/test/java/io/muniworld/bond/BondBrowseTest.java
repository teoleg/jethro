package io.muniworld.bond;

import io.muniworld.store.MuniKeys;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The browse path's two safety properties: the sort key can never reach SQL as arbitrary text, and the
 * scaled-long coupon key is exact — the constraint that made an unrounded filing rate fatal.
 */
class BondBrowseTest {

    @Test
    void theSortKeyIsAClosedSetNeverPassedThrough() {
        assertEquals("cusip", MuniBondService.sortColumn("cusip"));
        assertEquals("issuer", MuniBondService.sortColumn("issuer"));
        assertEquals("coupon", MuniBondService.sortColumn("coupon"));
        assertEquals("maturity_date", MuniBondService.sortColumn("maturity"));

        // The sort key is an IDENTIFIER, so it cannot be a bind parameter — anything unrecognised must
        // fall back to the default rather than being interpolated into the statement.
        assertEquals("updated_at", MuniBondService.sortColumn("coupon; DROP TABLE muni.security --"));
        assertEquals("updated_at", MuniBondService.sortColumn(null));
        assertEquals("updated_at", MuniBondService.sortColumn(""));
    }

    @Test
    void theCouponIndexKeyDemandsExactlySixDecimals() {
        // Why ingest must round: the key is coupon × 1e6 as an exact long, so a finer rate throws rather
        // than silently truncating a money value. Both halves of that contract are asserted here.
        assertEquals(5_125_000L, MuniKeys.couponScaled(new BigDecimal("5.125000")));
        assertEquals(3_750_000L, MuniKeys.couponScaled(new BigDecimal("3.750000")));
        assertThrows(ArithmeticException.class,
                () -> MuniKeys.couponScaled(new BigDecimal("3.7500000000000004")),
                "a rate finer than 6dp is refused, never rounded behind our back");
    }
}

package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strata zero curve from streamed tenor quotes. Rates/DFs are analytics estimates
 * (double, tolerance asserts) — never ledger money.
 */
class CurveServiceTest {

    private CurveService fullCurve() {
        var s = new CurveService();
        s.onRate("USD.SOFR.1Y", new BigDecimal("4.00"));
        s.onRate("USD.SOFR.2Y", new BigDecimal("4.10"));
        s.onRate("USD.SOFR.5Y", new BigDecimal("4.20"));
        s.onRate("USD.SOFR.10Y", new BigDecimal("4.30"));
        s.onRate("USD.SOFR.30Y", new BigDecimal("4.40"));
        return s;
    }

    @Test
    void noCurveUntilEveryTenorHasQuoted() {
        var s = new CurveService();
        s.onRate("USD.SOFR.1Y", new BigDecimal("4.00"));
        assertTrue(s.curve().isEmpty());
        assertTrue(s.snapshot().isEmpty());
    }

    @Test
    void nodesReproduceTheQuotedRatesAndInterpolateBetween() {
        var s = fullCurve();
        assertEquals(0.042, s.zeroRate(5).orElseThrow(), 1e-12);        // node exact
        // linear between 5y (4.20%) and 10y (4.30%): 7.5y → 4.25%
        assertEquals(0.0425, s.zeroRate(7.5).orElseThrow(), 1e-12);
    }

    @Test
    void discountFactorIsContinuousCompounding() {
        var s = fullCurve();
        // DF(5y) = e^(−0.042·5) = e^(−0.21) ≈ 0.810584
        assertEquals(Math.exp(-0.21), s.discountFactor(5).orElseThrow(), 1e-12);
    }

    @Test
    void snapshotListsAllTenorsSorted() {
        var points = fullCurve().snapshot();
        assertEquals(5, points.size());
        assertEquals(1.0, points.get(0).tenorYears());
        assertEquals(30.0, points.get(4).tenorYears());
        assertEquals(0.044, points.get(4).zeroRate(), 1e-12);
    }

    @Test
    void curveQuoteIdsAreRecognised() {
        assertTrue(CurveService.isCurveQuote("USD.SOFR.10Y"));
        assertTrue(!CurveService.isCurveQuote("AAPL"));
    }
}

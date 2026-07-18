package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strata zero curve from streamed tenor quotes. Rates/DFs are analytics estimates
 * (double, tolerance asserts) — never ledger money. The no-arg constructor is the
 * quote-as-zero FIXTURE mode these shape tests use; the production PAR bootstrap
 * (ADR-0041) is covered by the calibration round-trip tests below.
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

    // ---- PAR bootstrap (ADR-0041): the production mode ----

    private CurveService bootstrapped() {
        var s = new CurveService(true, () -> java.time.LocalDate.of(2026, 7, 17));
        s.onRate("USD.SOFR.1Y", new BigDecimal("4.00"));
        s.onRate("USD.SOFR.2Y", new BigDecimal("4.10"));
        s.onRate("USD.SOFR.5Y", new BigDecimal("4.20"));
        s.onRate("USD.SOFR.10Y", new BigDecimal("4.30"));
        s.onRate("USD.SOFR.30Y", new BigDecimal("4.40"));
        return s;
    }

    @Test
    void bootstrapRoundTripsTheParQuotes() {
        // THE defining property of a correct bootstrap: the Strata-priced par rate of each
        // tenor's OIS on the calibrated curve reproduces its quote.
        var s = bootstrapped();
        var curve = s.curve().orElseThrow();
        var valDate = java.time.LocalDate.of(2026, 7, 17);
        var provider = com.opengamma.strata.pricer.rate.ImmutableRatesProvider.builder(valDate)
                .discountCurve(com.opengamma.strata.basics.currency.Currency.USD, curve)
                .overnightIndexCurve(com.opengamma.strata.basics.index.OvernightIndices.USD_SOFR, curve)
                .build();
        double[][] tenorQuote = {{1, 0.0400}, {2, 0.0410}, {5, 0.0420}, {10, 0.0430}, {30, 0.0440}};
        for (double[] tq : tenorQuote) {
            var swap = com.opengamma.strata.product.swap.type.FixedOvernightSwapConventions
                    .USD_FIXED_1Y_SOFR_OIS
                    .createTrade(valDate, com.opengamma.strata.basics.date.Tenor.ofYears((int) tq[0]),
                            com.opengamma.strata.product.common.BuySell.BUY, 1_000_000, tq[1],
                            com.opengamma.strata.basics.ReferenceData.standard())
                    .getProduct().resolve(com.opengamma.strata.basics.ReferenceData.standard());
            double par = com.opengamma.strata.pricer.swap.DiscountingSwapProductPricer.DEFAULT
                    .parRate(swap, provider);
            assertEquals(tq[1], par, 1e-8, "par round-trip at " + (int) tq[0] + "Y");
        }
    }

    @Test
    void calibratedZerosSitAboveParOnAnUpwardSlopingCurve() {
        // Textbook: with rates rising in tenor, the zero at the long end exceeds the par quote
        // (par is a duration-weighted average of the zeros below it). Quote-as-zero missed this.
        var s = bootstrapped();
        double z30 = s.zeroRate(30).orElseThrow();
        assertTrue(z30 > 0.0440, "30y zero " + z30 + " should exceed the 4.40% par quote");
        // And the short end barely differs (nothing below it to average in).
        assertEquals(0.0400, s.zeroRate(1).orElseThrow(), 5e-4);
    }
}

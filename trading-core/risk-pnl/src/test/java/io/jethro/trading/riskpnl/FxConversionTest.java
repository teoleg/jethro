package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FX conversion via Strata's FxMatrix. Converted amounts are money derived from a double
 * rate (market data), so cross-rate results assert to a tolerance; the same-currency
 * identity is exact.
 */
class FxConversionTest {

    // 1 EUR = 1.085 USD, 1 GBP = 1.270 USD
    private final FxConversion fx = FxConversion.fromUsdPairMarks(Map.of(
            "EURUSD", new BigDecimal("1.085"),
            "GBPUSD", new BigDecimal("1.270")));

    @Test
    void sameCurrencyIsIdentityAndExact() {
        BigDecimal amount = new BigDecimal("1234.56");
        assertEquals(amount, fx.convert(amount, "USD", "USD"));
    }

    @Test
    void convertsToUsdAtThePairRate() {
        // 100 EUR → 108.50 USD
        assertEquals(108.50, fx.convert(new BigDecimal("100"), "EUR", "USD").doubleValue(), 1e-6);
    }

    @Test
    void triangulatesCrossRatesThroughUsd() {
        // GBP → EUR = (GBP/USD) / (EUR/USD) = 1.270 / 1.085 = 1.170506...
        // 100 GBP → ~117.05 EUR
        assertEquals(100.0 * 1.270 / 1.085,
                fx.convert(new BigDecimal("100"), "GBP", "EUR").doubleValue(), 1e-4);
    }

    @Test
    void knowsWhatItCanConvert() {
        assertTrue(fx.canConvert("EUR", "USD"));
        assertTrue(fx.canConvert("GBP", "EUR"));
        assertTrue(fx.canConvert("JPY", "JPY"));       // identity always
        assertFalse(fx.canConvert("JPY", "USD"));      // no JPY rate supplied
    }
}

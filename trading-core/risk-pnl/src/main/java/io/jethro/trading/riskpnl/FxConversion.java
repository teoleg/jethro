package io.jethro.trading.riskpnl;

import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.currency.FxMatrix;
import com.opengamma.strata.basics.currency.FxMatrixBuilder;
import io.jethro.domain.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Cross-currency conversion for risk rollups (ADR-0020, quant-engine.md), backed by
 * OpenGamma Strata's {@link FxMatrix} — the analytics substrate, not hand-rolled FX.
 *
 * <p>The money boundary is explicit: amounts in and out are exact {@link BigDecimal}
 * (invariant 1); Strata computes the {@code double} rate (and any triangulated cross
 * rate) in the middle; the result is restated to {@link Decimals#PNL_SCALE} at a single
 * reviewed point. Every conversion names its FX marks — the {@link FxMatrix} passed in.
 */
public final class FxConversion {

    private final FxMatrix fxMatrix;

    public FxConversion(FxMatrix fxMatrix) {
        this.fxMatrix = fxMatrix;
    }

    /**
     * Builds a converter from USD-quoted pair marks, e.g. {@code {"EURUSD": 1.085,
     * "GBPUSD": 1.270}} → an FxMatrix with USD as the pivot. Non 6-character or
     * non-*USD pairs are ignored (only spot-vs-USD marks feed the matrix for now).
     */
    public static FxConversion fromUsdPairMarks(Map<String, BigDecimal> usdPairMarks) {
        FxMatrixBuilder builder = FxMatrix.builder();
        for (Map.Entry<String, BigDecimal> e : usdPairMarks.entrySet()) {
            String pair = e.getKey();
            if (pair.length() == 6 && pair.endsWith("USD") && e.getValue().signum() > 0) {
                Currency base = Currency.of(pair.substring(0, 3));
                builder.addRate(base, Currency.USD, e.getValue().doubleValue());
            }
        }
        return new FxConversion(builder.build());
    }

    /**
     * Converts an exact amount from one currency to another. Identity when the currencies
     * match (no rate needed). Throws if a needed rate is absent from the matrix.
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return amount;
        }
        double rate = fxMatrix.fxRate(Currency.of(fromCurrency), Currency.of(toCurrency));
        return amount.multiply(BigDecimal.valueOf(rate)).setScale(Decimals.PNL_SCALE, RoundingMode.HALF_UP);
    }

    /** True if the matrix can convert between the two currencies (or they're equal). */
    public boolean canConvert(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return true;
        }
        try {
            fxMatrix.fxRate(Currency.of(fromCurrency), Currency.of(toCurrency));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

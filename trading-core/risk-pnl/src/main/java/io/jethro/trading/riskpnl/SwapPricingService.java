package io.jethro.trading.riskpnl;

import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.date.Tenor;
import com.opengamma.strata.basics.index.OvernightIndices;
import com.opengamma.strata.market.curve.Curve;
import com.opengamma.strata.pricer.rate.ImmutableRatesProvider;
import com.opengamma.strata.pricer.swap.DiscountingSwapProductPricer;
import com.opengamma.strata.pricer.swap.DiscountingSwapTradePricer;
import com.opengamma.strata.product.common.BuySell;
import com.opengamma.strata.product.swap.ResolvedSwapTrade;
import com.opengamma.strata.product.swap.type.FixedOvernightSwapConventions;
import io.jethro.domain.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Swap valuation on the live SOFR curve (quant-engine phase 4, slice 3) — real Strata
 * pricing, not approximation: trades built from {@code USD_FIXED_1Y_SOFR_OIS} market
 * conventions (USNY calendars, ACT/360 float, spot offset), discounted and forecast on
 * the {@link CurveService} curve via {@link DiscountingSwapTradePricer}. DV01 is the
 * calibrated parameter sensitivity summed and scaled to 1bp.
 *
 * <p>Boundary (invariant 1): PV and DV01 become exact {@link BigDecimal} at the money
 * boundary; par rates stay double (market data). Valuations are for the platform's
 * <b>reference swaps</b> — the defined USD_IRS products at a fixed demo coupon and
 * notional (CONVENTION below) — until swaps are tradeable positions.
 */
public final class SwapPricingService {

    /** A reference swap being valued: the defined product + demo economics. */
    private record ReferenceSwap(String instrumentId, int tenorYears, double fixedRate, double notional) {
    }

    // CONVENTION: demo economics — pay-fixed, $1M notional, coupons near the demo curve
    // so PV starts small and moves visibly with the curve. Real trades replace these.
    private static final List<ReferenceSwap> REFERENCE_SWAPS = List.of(
            new ReferenceSwap("USD_IRS_5Y", 5, 0.0400, 1_000_000),
            new ReferenceSwap("USD_IRS_10Y", 10, 0.0410, 1_000_000));

    private static final ReferenceData REF_DATA = ReferenceData.standard();

    public record SwapValuation(String instrumentId, String tenor, double notional,
                                double fixedRate, double parRate,
                                BigDecimal presentValue, BigDecimal dv01) {
    }

    private final CurveService curves;

    public SwapPricingService(CurveService curves) {
        this.curves = curves;
    }

    /** Values every reference swap on the current curve; empty until the curve is live. */
    public List<SwapValuation> valueAll(LocalDate valuationDate) {
        return curves.curve().map(curve -> value(curve, valuationDate)).orElse(List.of());
    }

    /**
     * Full-revaluation swap P&amp;L per $1M lot under a parallel curve shock (quant-engine step 2,
     * second slice): {@code ΔPV = PV(shocked curve) − PV(base curve)} for each reference swap,
     * re-priced through Strata — so the result carries <b>convexity</b>, unlike a first-order
     * DV01 × Δy shock (which is linear and symmetric). instrumentId → ΔPV (exact decimal,
     * invariant 1). Empty until the curve is live.
     */
    public java.util.Map<String, BigDecimal> swapPnlPerLotUnderShock(BigDecimal shiftBps, LocalDate valuationDate) {
        var base = curves.curve();
        var shocked = curves.curveWithShiftBps(shiftBps.doubleValue());
        if (base.isEmpty() || shocked.isEmpty()) {
            return java.util.Map.of();
        }
        var baseProvider = ratesProvider(base.get(), valuationDate);
        var shockedProvider = ratesProvider(shocked.get(), valuationDate);
        java.util.Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (ReferenceSwap swap : REFERENCE_SWAPS) {
            ResolvedSwapTrade trade = referenceTrade(swap, valuationDate);
            double pvBase = presentValue(trade, baseProvider);
            double pvShocked = presentValue(trade, shockedProvider);
            out.put(swap.instrumentId(), money(pvShocked - pvBase));
        }
        return out;
    }

    private List<SwapValuation> value(Curve curve, LocalDate valuationDate) {
        ImmutableRatesProvider provider = ratesProvider(curve, valuationDate);
        List<SwapValuation> out = new ArrayList<>();
        for (ReferenceSwap swap : REFERENCE_SWAPS) {
            ResolvedSwapTrade trade = referenceTrade(swap, valuationDate);
            double pv = presentValue(trade, provider);
            double parRate = DiscountingSwapProductPricer.DEFAULT.parRate(trade.getProduct(), provider);
            double dv01 = provider
                    .parameterSensitivity(DiscountingSwapTradePricer.DEFAULT.presentValueSensitivity(trade, provider))
                    .total().getAmount(Currency.USD).getAmount() * 1e-4;

            out.add(new SwapValuation(swap.instrumentId(), swap.tenorYears() + "Y",
                    swap.notional(), swap.fixedRate(), parRate, money(pv), money(dv01)));
        }
        return out;
    }

    private static ImmutableRatesProvider ratesProvider(Curve curve, LocalDate valuationDate) {
        return ImmutableRatesProvider.builder(valuationDate)
                .discountCurve(Currency.USD, curve)
                .overnightIndexCurve(OvernightIndices.USD_SOFR, curve)
                .build();
    }

    private static ResolvedSwapTrade referenceTrade(ReferenceSwap swap, LocalDate valuationDate) {
        return FixedOvernightSwapConventions.USD_FIXED_1Y_SOFR_OIS
                .createTrade(valuationDate, Tenor.ofYears(swap.tenorYears()), BuySell.BUY,
                        swap.notional(), swap.fixedRate(), REF_DATA)
                .resolve(REF_DATA);
    }

    private static double presentValue(ResolvedSwapTrade trade, ImmutableRatesProvider provider) {
        return DiscountingSwapTradePricer.DEFAULT.presentValue(trade, provider).getAmount(Currency.USD).getAmount();
    }

    /** The money boundary: analytics double → exact decimal (invariant 1). */
    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(Decimals.PNL_SCALE, RoundingMode.HALF_UP);
    }
}

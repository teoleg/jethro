package io.jethro.trading.riskpnl;

import io.jethro.domain.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-book rates risk as <b>bucketed DV01</b> (quant-engine step 5: risk beyond notional).
 * DV01 here is signed P&amp;L per <b>+1bp parallel</b> move in yields, so the sign shows
 * direction: a pay-fixed swap is positive (gains as rates rise), a long bond future is
 * negative (loses). Each rates position's DV01 lands in its key-tenor bucket (2Y/5Y/10Y/30Y),
 * summed per book and firm-wide — the desk's rates exposure profile at a glance.
 *
 * <p>Exact decimals (invariant 1): DV01 is money. Bond DV01 = netExposure × (−D) × 1e-4
 * (D = modified duration from reference data); swap DV01 = quantity(lots) × the Strata
 * per-$1M DV01 ({@link SwapPricingService}). Instruments without a duration/curve are skipped,
 * never guessed (finance-math rule).
 */
public final class RatesRiskService {

    private static final int SCALE = Decimals.PNL_SCALE;
    private static final RoundingMode ROUND = RoundingMode.HALF_EVEN;

    // Key-tenor bucket per rates instrument (demo reference data — a real desk keys off the
    // instrument's own tenor/CTD). Bonds: CME Treasury futures; swaps: the defined USD_IRS.
    private static final Map<String, String> TENOR = Map.of(
            "ZT", "2Y", "ZF", "5Y", "ZN", "10Y", "ZB", "30Y",
            "USD_IRS_5Y", "5Y", "USD_IRS_10Y", "10Y");
    private static final List<String> BUCKET_ORDER = List.of("2Y", "5Y", "10Y", "30Y", "other");

    /** DV01 in one tenor bucket (signed P&amp;L per +1bp). */
    public record TenorDv01(String tenor, BigDecimal dv01) {
    }

    /** A book's (or the firm's) rates risk: DV01 by tenor plus the net total. */
    public record BookRatesRisk(String book, List<TenorDv01> buckets, BigDecimal totalDv01) {
    }

    private final InstrumentRefSource refs;
    private final SwapPricingService swaps;

    public RatesRiskService(InstrumentRefSource refs, SwapPricingService swaps) {
        this.refs = refs;
        this.swaps = swaps;
    }

    /** Bucketed DV01 per book (rates positions only), newest firms first; empty if none. */
    public List<BookRatesRisk> bucketedDv01(List<PositionRisk> positions, LocalDate valuationDate) {
        // Per-$1M swap DV01 from the Strata pricer, keyed by instrumentId.
        Map<String, BigDecimal> swapDv01 = new LinkedHashMap<>();
        for (SwapPricingService.SwapValuation v : swaps.valueAll(valuationDate)) {
            swapDv01.put(v.instrumentId(), v.dv01());
        }

        // book -> tenor -> summed DV01, plus a FIRM aggregate.
        Map<String, Map<String, BigDecimal>> byBook = new LinkedHashMap<>();
        Map<String, BigDecimal> firm = new LinkedHashMap<>();
        for (PositionRisk p : positions) {
            if (p.quantity().signum() == 0) {
                continue;
            }
            BigDecimal dv01 = dv01For(p, swapDv01);
            if (dv01 == null || dv01.signum() == 0) {
                continue; // not a rates position (or no duration/curve) — skip
            }
            String tenor = TENOR.getOrDefault(p.instrumentId(), "other");
            byBook.computeIfAbsent(p.bookId(), b -> new LinkedHashMap<>())
                    .merge(tenor, dv01, BigDecimal::add);
            firm.merge(tenor, dv01, BigDecimal::add);
        }

        List<BookRatesRisk> out = new ArrayList<>();
        byBook.forEach((book, buckets) -> out.add(toBookRisk(book, buckets)));
        out.sort((a, b) -> b.totalDv01().abs().compareTo(a.totalDv01().abs()));
        if (!firm.isEmpty()) {
            out.add(toBookRisk("FIRM", firm));
        }
        return out;
    }

    /** Signed P&amp;L per +1bp for one position, or null if it carries no rates risk. */
    private BigDecimal dv01For(PositionRisk p, Map<String, BigDecimal> swapDv01) {
        switch (p.assetClass()) {
            case "BOND" -> {
                if (!p.hasMark()) {
                    return null; // can't value without a mark
                }
                BigDecimal duration = refs.find(p.instrumentId())
                        .map(InstrumentRef::modDuration).orElse(null);
                if (duration == null) {
                    return null; // no duration on file — never guess
                }
                // netExposure = qty × mark × mult (signed). +1bp → price ×(−D·1e-4) → this P&L.
                return p8(p.netExposure().multiply(duration.negate()).movePointLeft(4));
            }
            case "SWAP" -> {
                BigDecimal perLot = swapDv01.get(p.instrumentId());
                if (perLot == null) {
                    return null; // no curve/pricing yet
                }
                return p8(p.quantity().multiply(perLot)); // qty lots × per-$1M DV01 (signed)
            }
            default -> {
                return null;
            }
        }
    }

    private static BookRatesRisk toBookRisk(String book, Map<String, BigDecimal> buckets) {
        List<TenorDv01> ordered = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String tenor : BUCKET_ORDER) {
            BigDecimal dv01 = buckets.get(tenor);
            if (dv01 != null && dv01.signum() != 0) {
                ordered.add(new TenorDv01(tenor, p8(dv01)));
                total = total.add(dv01);
            }
        }
        return new BookRatesRisk(book, ordered, p8(total));
    }

    private static BigDecimal p8(BigDecimal v) {
        return v.setScale(SCALE, ROUND);
    }
}

package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value tests for bucketed DV01 (quant-engine step 5). DV01 is signed P&amp;L per +1bp:
 * long bond futures are negative (lose as rates rise), pay-fixed swaps positive. Bond DV01 is
 * exact arithmetic; swap DV01 is checked for consistency against the Strata per-$1M number and
 * for sign/magnitude (the pricer owns the absolute value).
 */
class RatesRiskServiceTest {

    private static final LocalDate VAL_DATE = LocalDate.of(2026, 1, 15);

    /** A curveless pricer: no swap DV01 available (bond-only cases). */
    private static SwapPricingService noSwaps() {
        return new SwapPricingService(new CurveService());
    }

    /** A pricer with a flat 4% SOFR curve, so reference-swap DV01 is live. */
    private static SwapPricingService swapsAt(String pct) {
        var s = new CurveService();
        for (String id : List.of("USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y")) {
            s.onRate(id, new BigDecimal(pct));
        }
        return new SwapPricingService(s);
    }

    private static InstrumentRefSource refs(Map<String, InstrumentRef> map) {
        return id -> Optional.ofNullable(map.get(id));
    }

    private static PositionRisk position(String book, String instrument, String assetClass,
                                         BigDecimal qty, BigDecimal netExposure, boolean hasMark) {
        return new PositionRisk(book, instrument, assetClass, "USD",
                qty, BigDecimal.ZERO, BigDecimal.ZERO, hasMark, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, netExposure, netExposure.abs());
    }

    private static BigDecimal bucket(RatesRiskService.BookRatesRisk book, String tenor) {
        return book.buckets().stream().filter(b -> b.tenor().equals(tenor))
                .map(RatesRiskService.TenorDv01::dv01).findFirst().orElse(null);
    }

    @Test
    void longBondFutureDv01IsNegativeAndExact() {
        // 1 ZN long @ 110.50 × $1000 = $110,500 net; mod duration 6.3.
        // DV01 = netExposure × (−D) × 1e-4 = 110,500 × −6.3 × 1e-4 = −69.615 per bp.
        var refs = refs(Map.of("ZN",
                new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("6.3"))));
        var svc = new RatesRiskService(refs, noSwaps());

        var pos = position("MACRO", "ZN", "BOND", BigDecimal.ONE, new BigDecimal("110500"), true);
        List<RatesRiskService.BookRatesRisk> out = svc.bucketedDv01(List.of(pos), VAL_DATE);

        // MACRO row + FIRM row.
        assertEquals(2, out.size());
        var macro = out.get(0);
        assertEquals("MACRO", macro.book());
        assertEquals(0, bucket(macro, "10Y").compareTo(new BigDecimal("-69.615")), "ZN 10Y DV01");
        assertEquals(0, macro.totalDv01().compareTo(new BigDecimal("-69.615")), "book total");
        assertEquals("FIRM", out.get(1).book());
        assertEquals(0, out.get(1).totalDv01().compareTo(new BigDecimal("-69.615")), "firm total");
    }

    @Test
    void shortBondFutureFlipsSign() {
        // Short a bond future → gains as rates rise → positive DV01.
        var refs = refs(Map.of("ZN",
                new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("6.3"))));
        var svc = new RatesRiskService(refs, noSwaps());

        var pos = position("MACRO", "ZN", "BOND", new BigDecimal("-1"), new BigDecimal("-110500"), true);
        var out = svc.bucketedDv01(List.of(pos), VAL_DATE);
        assertEquals(0, out.get(0).totalDv01().compareTo(new BigDecimal("69.615")), "short bond DV01 positive");
    }

    @Test
    void bondWithoutDurationOrMarkIsSkipped() {
        // No mod duration on file, and a separate one with no mark — both carry no DV01.
        var refs = refs(Map.of(
                "ZN", new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000")), // no duration
                "ZB", new InstrumentRef("ZB", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("14.0"))));
        var svc = new RatesRiskService(refs, noSwaps());

        var noDuration = position("MACRO", "ZN", "BOND", BigDecimal.ONE, new BigDecimal("110500"), true);
        var noMark = position("MACRO", "ZB", "BOND", BigDecimal.ONE, BigDecimal.ZERO, false);
        assertTrue(svc.bucketedDv01(List.of(noDuration, noMark), VAL_DATE).isEmpty(),
                "no duration and no mark → nothing to report");
    }

    @Test
    void liveTreasuryCurveMakesBondDv01Dynamic() {
        // Same ZN position as above, but the Treasury curve has quoted 4.50%: below the 6%
        // conversion-factor pivot ZN's CTD is the SHORT end of its 6.5–10y window →
        // D(0.045, 6.5) = 5.5818. DV01 = 110,500 × −5.5818 × 1e-4 = −61.67889 exactly.
        var refs = refs(Map.of("ZN",
                new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("6.3"))));
        var curve = new TreasuryCurveView();
        for (String id : List.of("USD.TSY.1Y", "USD.TSY.2Y", "USD.TSY.5Y", "USD.TSY.10Y", "USD.TSY.30Y")) {
            curve.onRate(id, new BigDecimal("4.50"));
        }
        var svc = new RatesRiskService(refs, noSwaps(), new BondFutureDurations(curve, refs));

        var pos = position("MACRO", "ZN", "BOND", BigDecimal.ONE, new BigDecimal("110500"), true);
        var out = svc.bucketedDv01(List.of(pos), VAL_DATE);
        assertEquals(0, out.get(0).totalDv01().compareTo(new BigDecimal("-61.67889")),
                "dynamic CTD-window DV01 at the live yield, not the static −69.615");
    }

    @Test
    void payFixedSwapDv01IsPositiveAndMatchesStrataPerLot() {
        var swaps = swapsAt("4.00");
        BigDecimal perLot = swaps.valueAll(VAL_DATE).stream()
                .filter(v -> v.instrumentId().equals("USD_IRS_5Y")).findFirst().orElseThrow().dv01();
        assertTrue(perLot.signum() > 0, "pay-fixed 5Y per-$1M DV01 positive: " + perLot);

        var refs = refs(Map.of("USD_IRS_5Y",
                new InstrumentRef("USD_IRS_5Y", "SWAP", "USD", BigDecimal.ONE)));
        var svc = new RatesRiskService(refs, swaps);

        // 2 lots → DV01 = 2 × per-$1M DV01, landing in the 5Y bucket.
        var pos = position("RATES", "USD_IRS_5Y", "SWAP", new BigDecimal("2"), BigDecimal.ZERO, true);
        var out = svc.bucketedDv01(List.of(pos), VAL_DATE);
        BigDecimal expected = perLot.multiply(new BigDecimal("2")).setScale(8, java.math.RoundingMode.HALF_EVEN);
        assertEquals(0, bucket(out.get(0), "5Y").compareTo(expected), "swap 5Y DV01 = 2 × per-lot");
        assertTrue(out.get(0).totalDv01().signum() > 0, "pay-fixed book DV01 positive");
    }

    @Test
    void bucketsPerBookAndAggregatesFirmWide() {
        var refs = refs(Map.of(
                "ZN", new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("6.3")),
                "ZB", new InstrumentRef("ZB", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("14.0"))));
        var svc = new RatesRiskService(refs, noSwaps());

        // Book A: long ZN (10Y, −69.615). Book B: long ZB @ 120 × $1000 = 120,000, D 14 → −168.
        var a = position("BOOK_A", "ZN", "BOND", BigDecimal.ONE, new BigDecimal("110500"), true);
        var b = position("BOOK_B", "ZB", "BOND", BigDecimal.ONE, new BigDecimal("120000"), true);
        var out = svc.bucketedDv01(List.of(a, b), VAL_DATE);

        // Two books + FIRM.
        assertEquals(3, out.size());
        // Sorted by |total| desc: ZB (−168) before ZN (−69.615).
        assertEquals("BOOK_B", out.get(0).book());
        assertEquals(0, out.get(0).totalDv01().compareTo(new BigDecimal("-168")), "ZB 30Y DV01");
        assertEquals("BOOK_A", out.get(1).book());
        assertEquals("FIRM", out.get(2).book());
        // Firm = −168 + −69.615 = −237.615, split across 30Y and 10Y buckets.
        assertEquals(0, out.get(2).totalDv01().compareTo(new BigDecimal("-237.615")), "firm total");
        assertEquals(0, bucket(out.get(2), "30Y").compareTo(new BigDecimal("-168")), "firm 30Y bucket");
        assertEquals(0, bucket(out.get(2), "10Y").compareTo(new BigDecimal("-69.615")), "firm 10Y bucket");
    }

    @Test
    void flatAndNonRatesPositionsCarryNoDv01() {
        var refs = refs(Map.of(
                "ZN", new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("6.3")),
                "AAPL", new InstrumentRef("AAPL", "EQUITY", "USD", BigDecimal.ONE)));
        var svc = new RatesRiskService(refs, noSwaps());

        var flat = position("MACRO", "ZN", "BOND", BigDecimal.ZERO, new BigDecimal("110500"), true);
        var equity = position("MACRO", "AAPL", "EQUITY", new BigDecimal("100"), new BigDecimal("19000"), true);
        assertTrue(svc.bucketedDv01(List.of(flat, equity), VAL_DATE).isEmpty(),
                "flat rates + equity → no rates risk");
    }
}

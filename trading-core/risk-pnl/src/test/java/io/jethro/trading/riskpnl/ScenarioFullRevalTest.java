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
 * Full-revaluation scenario legs (quant-engine step 2 remainder). Bond numbers are
 * hand-computed from the closed form (finance-math rule); swap properties are pricing
 * identities (payer convexity: gains less on +Δ than DV01×Δ, loses more on −Δ; aged
 * trades respond like their remaining tenor).
 */
class ScenarioFullRevalTest {

    private static final LocalDate VAL_DATE = LocalDate.of(2026, 1, 15);

    private final InstrumentRefSource refs = id -> Optional.ofNullable(Map.of(
            "ZN", new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("6.3")),
            "USD_IRS_5Y", new InstrumentRef("USD_IRS_5Y", "SWAP", "USD", new BigDecimal("45000"))
    ).get(id));

    private final FxConversion fx = FxConversion.fromUsdPairMarks(Map.of());

    private static TreasuryCurveView tsyCurveAt(double pct) {
        var curve = new TreasuryCurveView();
        for (String id : List.of("USD.TSY.1Y", "USD.TSY.2Y", "USD.TSY.5Y", "USD.TSY.10Y", "USD.TSY.30Y")) {
            curve.onRate(id, BigDecimal.valueOf(pct));
        }
        return curve;
    }

    private static CurveService flatSofr(String pct) {
        var s = new CurveService();
        for (String id : List.of("USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y")) {
            s.onRate(id, new BigDecimal(pct));
        }
        return s;
    }

    private static PositionRisk pos(String book, String instrument, String assetClass,
                                    String qty, String mark, String mult) {
        BigDecimal q = new BigDecimal(qty);
        BigDecimal net = q.multiply(new BigDecimal(mark)).multiply(new BigDecimal(mult));
        return new PositionRisk(book, instrument, assetClass, "USD", q, new BigDecimal(mark),
                new BigDecimal(mark), true, 0, BigDecimal.ZERO, BigDecimal.ZERO, net, net.abs());
    }

    private static ScenarioEngine.ScenarioResult byId(List<ScenarioEngine.ScenarioResult> results, String id) {
        return results.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual.toPlainString());
    }

    @Test
    void parBondPriceChangeMatchesTheClosedFormWorkedNumbers() {
        // ZN's CTD par bond (6.5y @ 4.5%): P(y') = (y/y')(1 − (1+y'/2)^(−13)) + (1+y'/2)^(−13).
        assertMoney("-0.0540350543", BondFutureDurations.parBondPriceChange(0.045, 0.055, 6.5));
        assertMoney("0.0576882049", BondFutureDurations.parBondPriceChange(0.045, 0.035, 6.5));
        assertMoney("0.0140692906", BondFutureDurations.parBondPriceChange(0.045, 0.0425, 6.5));
    }

    @Test
    void bondFutureFullRevalCarriesTheConvexityAsymmetry() {
        // Long 1 ZN @ 110.50 × $1000 → net 110,500, live TSY curve flat 4.5% (CTD 6.5y):
        //   +100bp: 110,500 × −0.0540350543 = −5,970.87350015
        //   −100bp: 110,500 × +0.0576882049 = +6,374.54664145 (gains MORE than it loses — convexity)
        //   risk-off (−25bp): 110,500 × +0.0140692906 = +1,554.65661130
        var engine = new ScenarioEngine(refs, null, new BondFutureDurations(tsyCurveAt(4.5), refs));
        var results = engine.run(List.of(pos("MACRO", "ZN", "BOND", "1", "110.50", "1000")), fx);
        assertMoney("-5970.87350015", byId(results, "rates-up-100").firmPnlUsd());
        assertMoney("6374.54664145", byId(results, "rates-down-100").firmPnlUsd());
        assertMoney("1554.65661130", byId(results, "risk-off").firmPnlUsd());
        assertTrue(byId(results, "rates-down-100").firmPnlUsd()
                        .compareTo(byId(results, "rates-up-100").firmPnlUsd().negate()) > 0,
                "long bonds gain more on −100bp than they lose on +100bp");
    }

    @Test
    void bondFullRevalRefusesANonPositiveShockedYieldAndFallsBack() {
        // TSY curve at 0.5%: −100bp would take the yield ≤ 0 — no sane reprice; the engine
        // falls back to first-order on the LIVE duration rather than guessing.
        var durations = new BondFutureDurations(tsyCurveAt(0.5), refs);
        assertTrue(durations.priceChangeUnderShock("ZN", new BigDecimal("-100")).isEmpty());
        var engine = new ScenarioEngine(refs, null, durations);
        var results = engine.run(List.of(pos("MACRO", "ZN", "BOND", "1", "110.50", "1000")), fx);
        BigDecimal liveD = durations.modifiedDuration("ZN").orElseThrow();
        assertMoney(new BigDecimal("110500").multiply(liveD).movePointLeft(2).toPlainString(),
                byId(results, "rates-down-100").firmPnlUsd());
    }

    @Test
    void seasonedSwapHookTakesPrecedenceAndIsUsedAsTheGroupTotal() {
        ScenarioEngine.SeasonedSwapReval seasoned = (book, instrument, shiftBps) ->
                Optional.of(new BigDecimal(shiftBps.signum() > 0 ? "12345.00" : "-13000.00"));
        var engine = new ScenarioEngine(refs, null,
                BondFutureDurations.staticOnly(refs), seasoned);
        // Quantity 3 — the hook's number is the TOTAL for the group, never re-multiplied.
        var results = engine.run(List.of(pos("MACRO", "USD_IRS_5Y", "SWAP", "3", "4.45", "45000")), fx);
        assertMoney("12345.00", byId(results, "rates-up-100").firmPnlUsd());
        assertMoney("-13000.00", byId(results, "rates-down-100").firmPnlUsd());
        assertMoney("0", byId(results, "equities-down-5").firmPnlUsd());
    }

    @Test
    void seasonedShockRevalShowsPayerConvexityAndAging() {
        var pricer = new SwapPricingService(flatSofr("4.00"));
        BigDecimal dv01 = pricer.valueSeasoned(VAL_DATE, 5, true, 0.04, 1_000_000, VAL_DATE)
                .orElseThrow().dv01();
        BigDecimal up = pricer.seasonedPnlUnderShock(VAL_DATE, 5, true, 0.04, 1_000_000,
                VAL_DATE, new BigDecimal("100")).orElseThrow();
        BigDecimal down = pricer.seasonedPnlUnderShock(VAL_DATE, 5, true, 0.04, 1_000_000,
                VAL_DATE, new BigDecimal("-100")).orElseThrow();
        BigDecimal linear = dv01.multiply(new BigDecimal("100"));
        // Payer convexity: gains LESS than DV01×100bp on the way up, loses MORE on the way down.
        assertTrue(up.signum() > 0 && up.compareTo(linear) < 0, up + " vs linear " + linear);
        assertTrue(down.signum() < 0 && down.abs().compareTo(linear) > 0, down + " vs linear " + linear);

        // Aging: a 10Y traded 6y ago (4y remaining) responds far less than a fresh 10Y.
        BigDecimal fresh10 = pricer.seasonedPnlUnderShock(VAL_DATE, 10, true, 0.041, 1_000_000,
                VAL_DATE, new BigDecimal("100")).orElseThrow();
        BigDecimal aged10 = pricer.seasonedPnlUnderShock(VAL_DATE.minusYears(6), 10, true, 0.041,
                1_000_000, VAL_DATE, new BigDecimal("100")).orElseThrow();
        assertTrue(aged10.signum() > 0 && aged10.compareTo(fresh10.divide(new BigDecimal("2"))) < 0,
                "aged " + aged10 + " must be well under fresh " + fresh10);

        // Matured: shocks to exactly zero; dead curve: empty, never zeroed silently.
        assertMoney("0", pricer.seasonedPnlUnderShock(VAL_DATE.minusYears(6), 5, true, 0.04,
                1_000_000, VAL_DATE, new BigDecimal("100")).orElseThrow());
        assertTrue(new SwapPricingService(new CurveService()).seasonedPnlUnderShock(VAL_DATE, 5,
                true, 0.04, 1_000_000, VAL_DATE, new BigDecimal("100")).isEmpty());
    }
}

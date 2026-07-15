package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value tests for the scenario engine's FIRST-ORDER fallback paths (no live curves
 * here) — each case is the worked
 * numeric example from the engine's javadoc (finance-math rule: hand-computed first).
 */
class ScenarioEngineTest {

    private final InstrumentRefSource refs = id -> Optional.ofNullable(Map.of(
            "AAPL", new InstrumentRef("AAPL", "EQUITY", "USD", new BigDecimal("1")),
            "SAP", new InstrumentRef("SAP", "EQUITY", "EUR", new BigDecimal("1")),
            "EURUSD", new InstrumentRef("EURUSD", "FX", "USD", new BigDecimal("1")),
            "ZN", new InstrumentRef("ZN", "BOND", "USD", new BigDecimal("1000"), new BigDecimal("6.3")),
            "ZX", new InstrumentRef("ZX", "BOND", "USD", new BigDecimal("1000")), // no duration
            "USD_IRS_5Y", new InstrumentRef("USD_IRS_5Y", "SWAP", "USD", new BigDecimal("45000"))
    ).get(id));

    private final ScenarioEngine engine = new ScenarioEngine(refs);
    private final FxConversion fx =
            FxConversion.fromUsdPairMarks(Map.of("EURUSD", new BigDecimal("1.085")));

    private static PositionRisk pos(String book, String instrument, String assetClass, String ccy,
                                    String qty, String mark) {
        BigDecimal q = new BigDecimal(qty);
        BigDecimal m = new BigDecimal(mark);
        BigDecimal mult = switch (instrument) {
            case "ZN", "ZX" -> new BigDecimal("1000");
            case "USD_IRS_5Y" -> new BigDecimal("45000");
            default -> BigDecimal.ONE;
        };
        BigDecimal net = q.multiply(m).multiply(mult);
        return new PositionRisk(book, instrument, assetClass, ccy, q, m, m, true, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, net, net.abs());
    }

    private static ScenarioEngine.ScenarioResult byId(List<ScenarioEngine.ScenarioResult> results, String id) {
        return results.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual.toPlainString());
    }

    @Test
    void bondFutureLosesDurationTimesShift() {
        // Long 1 ZN @ 110.50 × $1000 → net 110,500; rates +100bp:
        // ΔP&L = 110,500 × (−6.3 × 0.01) = −6,961.50
        var results = engine.run(List.of(pos("MACRO", "ZN", "BOND", "USD", "1", "110.50")), fx);
        assertMoney("-6961.50", byId(results, "rates-up-100").firmPnlUsd());
        assertMoney("6961.50", byId(results, "rates-down-100").firmPnlUsd());
        assertMoney("0", byId(results, "equities-down-5").firmPnlUsd());
    }

    @Test
    void payFixedSwapGainsWhenRatesRise() {
        // 1 lot pay-fixed USD_IRS_5Y (mult 45,000): +100bp → 1 × 1.00 × 45,000 = +45,000;
        // risk-off (−25bp) → 1 × (−0.25) × 45,000 = −11,250.
        var results = engine.run(List.of(pos("MACRO", "USD_IRS_5Y", "SWAP", "USD", "1", "4.45")), fx);
        assertMoney("45000", byId(results, "rates-up-100").firmPnlUsd());
        assertMoney("-11250", byId(results, "risk-off").firmPnlUsd());
    }

    @Test
    void equityShockIsLinearInNetExposure() {
        // Long 100 AAPL @ 190 → net 19,000; equities −5% → −950.
        var results = engine.run(List.of(pos("ALPHA", "AAPL", "EQUITY", "USD", "100", "190")), fx);
        assertMoney("-950", byId(results, "equities-down-5").firmPnlUsd());
        assertMoney("0", byId(results, "rates-up-100").firmPnlUsd());
    }

    @Test
    void foreignEquityConvertsAtTheNamedFxMark() {
        // SAP: 100 @ €185 → net €18,500; equities −5% → −€925 × 1.085 = −$1,003.625.
        var results = engine.run(List.of(pos("ALPHA", "SAP", "EQUITY", "EUR", "100", "185")), fx);
        assertMoney("-1003.625", byId(results, "equities-down-5").firmPnlUsd());
    }

    @Test
    void usdShockHitsFxPositionsAndForeignTranslation() {
        // EURUSD 10,000 @ 1.085 → net $10,850; USD +2% → pair −2% → −217.00.
        // SAP €18,500 = $20,072.50; USD +2% → translation −401.45. Firm: −618.45.
        var results = engine.run(List.of(
                pos("MACRO", "EURUSD", "FX", "USD", "10000", "1.085"),
                pos("ALPHA", "SAP", "EQUITY", "EUR", "100", "185")), fx);
        var usdUp = byId(results, "usd-up-2");
        assertMoney("-618.45", usdUp.firmPnlUsd());
        assertEquals("ALPHA", usdUp.byBook().get(0).bookId()); // worst book first (−401.45)
        assertMoney("-401.45", usdUp.byBook().get(0).pnlUsd());
        assertMoney("-217.00", usdUp.byBook().get(1).pnlUsd());
    }

    @Test
    void bondWithoutDurationIsSkippedNotSilentlyInsensitive() {
        var results = engine.run(List.of(pos("MACRO", "ZX", "BOND", "USD", "1", "101.00")), fx);
        var ratesUp = byId(results, "rates-up-100");
        assertEquals(1, ratesUp.positionsSkipped());
        assertEquals(0, ratesUp.positionsCovered());
        assertMoney("0", ratesUp.firmPnlUsd());
        // Without a rates shock the position is coverable (zero impact, but valued).
        assertEquals(0, byId(results, "equities-down-5").positionsSkipped());
    }

    @Test
    void unconvertibleCurrencyIsSkippedAndCounted() {
        var results = engine.run(List.of(
                pos("ALPHA", "AAPL", "EQUITY", "USD", "100", "190"),
                pos("ALPHA", "SAP", "EQUITY", "JPY", "100", "185")), // no JPY mark in fx
                fx);
        var eqDown = byId(results, "equities-down-5");
        assertEquals(1, eqDown.positionsSkipped());
        assertMoney("-950", eqDown.firmPnlUsd()); // only the convertible position counts
    }

    @Test
    void bookRollupSumsToFirm() {
        var results = engine.run(List.of(
                pos("ALPHA", "AAPL", "EQUITY", "USD", "100", "190"),
                pos("MACRO", "ZN", "BOND", "USD", "1", "110.50"),
                pos("MACRO", "USD_IRS_5Y", "SWAP", "USD", "1", "4.45")), fx);
        var ratesUp = byId(results, "rates-up-100");
        BigDecimal sum = ratesUp.byBook().stream()
                .map(ScenarioEngine.BookImpact::pnlUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(ratesUp.firmPnlUsd()));
        // MACRO nets +45,000 − 6,961.50 = +38,038.50; long bonds hedge the pay-fixed swap.
        assertMoney("38038.50", ratesUp.firmPnlUsd());
        assertTrue(ratesUp.positionsCovered() == 3);
    }
}

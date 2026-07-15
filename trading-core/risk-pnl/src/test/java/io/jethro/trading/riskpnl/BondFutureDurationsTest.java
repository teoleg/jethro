package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic bond-future duration: D(y,T) = (1/y)(1 − (1+y/2)^(−2T)) at the live Treasury
 * yield, refdata static as the fallback. Worked values checked to the published scale.
 */
class BondFutureDurationsTest {

    /** CME deliverable windows standing in for refdata (V28); the risk-pnl domain code no
     *  longer hardcodes them. */
    private static final BondFutureDurations.DeliverableWindowSource WINDOWS = id -> switch (id) {
        case "ZT" -> java.util.Optional.of(new BondFutureDurations.DeliverableWindow(1.75, 2.0));
        case "ZF" -> java.util.Optional.of(new BondFutureDurations.DeliverableWindow(4.17, 5.25));
        case "ZN" -> java.util.Optional.of(new BondFutureDurations.DeliverableWindow(6.5, 10.0));
        case "ZB" -> java.util.Optional.of(new BondFutureDurations.DeliverableWindow(15.0, 25.0));
        default -> java.util.Optional.empty();
    };

    private static final InstrumentRefSource REFS = id -> switch (id) {
        case "ZN" -> Optional.of(new InstrumentRef("ZN", "BOND", "USD",
                new BigDecimal("1000"), new BigDecimal("6.3")));
        case "ZQ" -> Optional.of(new InstrumentRef("ZQ", "BOND", "USD",
                new BigDecimal("4167"), null)); // a bond with no duration on file
        default -> Optional.empty();
    };

    private static TreasuryCurveView curveAt(double pct) {
        var curve = new TreasuryCurveView();
        for (String id : new String[]{"USD.TSY.1Y", "USD.TSY.2Y", "USD.TSY.5Y", "USD.TSY.10Y", "USD.TSY.30Y"}) {
            curve.onRate(id, BigDecimal.valueOf(pct));
        }
        return curve;
    }

    @Test
    void closedFormParBondDurationMatchesTheWorkedExample() {
        // y=4.5%, T=10: (1/0.045)(1 − 1.0225^-20) = 22.2222 × 0.35918 = 7.9819.
        assertEquals(0, new BigDecimal("7.9819")
                .compareTo(BondFutureDurations.parBondModifiedDuration(0.045, 10)));
        // y=1.5%, same 10Y: duration extends to ≈ 9.25 — the static constant misses this.
        BigDecimal low = BondFutureDurations.parBondModifiedDuration(0.015, 10);
        assertTrue(low.doubleValue() > 9.2 && low.doubleValue() < 9.4, "10Y @ 1.5% ≈ 9.25, got " + low);
    }

    @Test
    void liveCurveDrivesTheDurationViaTheCtdWindow() {
        // Flat 4.50% curve < the 6% conversion-factor pivot → ZN's CTD is the SHORT end of
        // its 6.5–10y deliverable window: D(0.045, 6.5) = 5.5818 — not the naive 10y 7.9819,
        // and not the refdata static 6.3.
        var durations = new BondFutureDurations(curveAt(4.50), REFS, WINDOWS);
        assertEquals(0, new BigDecimal("5.5818").compareTo(durations.modifiedDuration("ZN").orElseThrow()),
                "CTD at the short window end below the 6% pivot");

        var lowRates = new BondFutureDurations(curveAt(1.50), REFS, WINDOWS);
        assertTrue(lowRates.modifiedDuration("ZN").orElseThrow()
                        .compareTo(durations.modifiedDuration("ZN").orElseThrow()) > 0,
                "duration extends as yields fall");
    }

    @Test
    void aboveTheSixPercentPivotTheLongEndBecomesCtd() {
        // At 7% yields the conversion-factor advantage flips: ZN's CTD is the 10y end.
        // D(0.07, 10) = (1/0.07)(1 − 1.035⁻²⁰) = 7.1062.
        var durations = new BondFutureDurations(curveAt(7.00), REFS, WINDOWS);
        assertEquals(0, BondFutureDurations.parBondModifiedDuration(0.07, 10)
                        .compareTo(durations.modifiedDuration("ZN").orElseThrow()),
                "long window end above the pivot");
    }

    @Test
    void fallsBackToRefdataUntilTheCurveQuotes() {
        var durations = new BondFutureDurations(new TreasuryCurveView(), REFS, WINDOWS); // nothing quoted
        assertEquals(0, new BigDecimal("6.3").compareTo(durations.modifiedDuration("ZN").orElseThrow()));
        assertTrue(durations.modifiedDuration("ZQ").isEmpty(),
                "no curve tenor and no refdata duration → empty, never guessed");
    }
}

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
    void liveCurveDrivesTheDurationAndYieldMovesIt() {
        var durations = new BondFutureDurations(curveAt(4.50), REFS);
        assertEquals(0, new BigDecimal("7.9819").compareTo(durations.modifiedDuration("ZN").orElseThrow()),
                "ZN keys off the live 10Y yield, not the refdata 6.3");

        var lowRates = new BondFutureDurations(curveAt(1.50), REFS);
        assertTrue(lowRates.modifiedDuration("ZN").orElseThrow()
                        .compareTo(durations.modifiedDuration("ZN").orElseThrow()) > 0,
                "duration extends as yields fall");
    }

    @Test
    void fallsBackToRefdataUntilTheCurveQuotes() {
        var durations = new BondFutureDurations(new TreasuryCurveView(), REFS); // nothing quoted
        assertEquals(0, new BigDecimal("6.3").compareTo(durations.modifiedDuration("ZN").orElseThrow()));
        assertTrue(durations.modifiedDuration("ZQ").isEmpty(),
                "no curve tenor and no refdata duration → empty, never guessed");
    }
}

package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0121 — the cross-sectional residual reversion sensor. Every assertion below is against a
 * hand-worked cross-section, so the arithmetic is pinned rather than merely exercised.
 *
 * <p><b>The worked example</b> used by {@link #fadesTheResidualAgainstThePeerGroup()}. Window
 * L = 900 000 ms ending at t = 1 000 000, so the window opens at 100 000 and its midpoint is 550 000.
 * Four EQUITY names each print an anchor at t = 200 000 (first half) and a current print at
 * t = 800 000 (second half), so every one is admitted with span = 600 000 ms = 600 s, √span = √600.
 *
 * <pre>
 *   name   anchor → current   ln(ratio)        r = ln(ratio)/√600
 *   A      100    → 102       +0.019802627…    +0.00080843887403584…
 *   B      100    → 101       +0.009950331…    +0.00040622055603557…
 *   C      100    → 100.5     +0.004987542…    +0.00020361552954992…
 *   D      100    →  99       −0.010050336…    −0.00041030324307796…
 *
 *   median m  = (r_C + r_B)/2                 = +0.00030491804279274…   (even n → mean of the two middle)
 *   |r−m|     =  0.00050351…, 0.00010130…, 0.00010130…, 0.00071522…
 *   MAD       = (0.00010130… + 0.00050351…)/2 = +0.00030241167224295…
 *   scale     = 1.4826 × MAD                  = +0.00044835554526741…
 *
 *   z_A = (r_A − m)/scale = +1.1230391517579659   score_A = −1.1230391517579659   (out-ran peers → sell)
 *   z_B = +0.2259423671952245                     score_B = −0.2259423671952245
 *   z_C = −0.2259423671952245                     score_C = +0.2259423671952245
 *   z_D = −1.595210081419047                      score_D = +1.595210081419047    (lagged peers → buy)
 * </pre>
 *
 * Note what the sensor did to A and D: both are pure relative statements. The whole group is up on
 * average, and the sensor is still short the biggest gainer and long the only faller — it has removed
 * the common move, which is the entire reason it is a different source from ADR-0070's own-price
 * range sensor rather than a rescaling of it.
 */
class CrossSectionalReversionForecasterTest {

    private static final long NOW = 1_000_000L;
    private static final long LOOKBACK = 900_000L;
    private static final long ANCHOR_AT = 200_000L;  // first half of the window
    private static final long CURRENT_AT = 800_000L; // second half

    private static CrossSectionalReversionForecaster forecaster(int minPeers, double maxAbsZ) {
        return new CrossSectionalReversionForecaster(
                new CrossSectionalReversionForecaster.Params(LOOKBACK, minPeers, maxAbsZ));
    }

    private static void printPair(CrossSectionalReversionForecaster f, String id, String group,
                                  String anchor, String current) {
        f.observe(id, group, new BigDecimal(anchor), ANCHOR_AT);
        f.observe(id, group, new BigDecimal(current), CURRENT_AT);
    }

    private static void fourEquities(CrossSectionalReversionForecaster f) {
        printPair(f, "A", "EQUITY", "100", "102");
        printPair(f, "B", "EQUITY", "100", "101");
        printPair(f, "C", "EQUITY", "100", "100.5");
        printPair(f, "D", "EQUITY", "100", "99");
    }

    @Test
    void fadesTheResidualAgainstThePeerGroup() {
        var f = forecaster(4, 4.0);
        fourEquities(f);

        Map<String, CrossSectionalReversionForecaster.Reading> readings = f.sweep(NOW);

        assertThat(readings.keySet()).containsExactlyInAnyOrder("A", "B", "C", "D");
        assertThat(readings.get("A").score()).isCloseTo(-1.1230391517579659, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(readings.get("B").score()).isCloseTo(-0.2259423671952245, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(readings.get("C").score()).isCloseTo(0.2259423671952245, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(readings.get("D").score()).isCloseTo(1.595210081419047, org.assertj.core.data.Offset.offset(1e-12));
        // and the parts it was built from, so the operator surface is pinned too
        assertThat(readings.get("A").normalisedReturn()).isCloseTo(0.0008084388740358405, org.assertj.core.data.Offset.offset(1e-15));
        assertThat(readings.get("A").peerMedian()).isCloseTo(0.0003049180427927458, org.assertj.core.data.Offset.offset(1e-15));
        assertThat(readings.get("A").peerScale()).isCloseTo(0.000448355545267412, org.assertj.core.data.Offset.offset(1e-15));
        assertThat(readings.get("A").peers()).isEqualTo(4);
        assertThat(readings.get("A").spanMillis()).isEqualTo(600_000L);
        assertThat(readings.get("A").peerGroup()).isEqualTo("EQUITY");
    }

    @Test
    void isRelativeNotDirectional_awholeGroupMovingTogetherHasNoResidual() {
        // Every name up exactly 2%: the common move is entirely the median, so no residual survives.
        // A directional reversion source would be short all four here; this one has no view at all,
        // because the dispersion it divides by is zero.
        var f = forecaster(4, 4.0);
        printPair(f, "A", "EQUITY", "100", "102");
        printPair(f, "B", "EQUITY", "200", "204");
        printPair(f, "C", "EQUITY", "50", "51");
        printPair(f, "D", "EQUITY", "10", "10.2");

        assertThat(f.sweep(NOW)).isEmpty();
    }

    @Test
    void aNameThatStoppedPrintingBeforeTheMidpointIsNotAdmitted() {
        var f = forecaster(3, 4.0);
        printPair(f, "A", "EQUITY", "100", "102");
        printPair(f, "B", "EQUITY", "100", "101");
        printPair(f, "C", "EQUITY", "100", "100.5");
        // D's newest print lands in the FIRST half — a stale name whose "current" price is a quarter
        // hour old. Its return does not span the window the group is compared over, so it is excluded
        // rather than contributing a partial move as if it were current.
        f.observe("D", "EQUITY", new BigDecimal("100"), 150_000L);
        f.observe("D", "EQUITY", new BigDecimal("99"), 300_000L);

        var readings = f.sweep(NOW);

        assertThat(readings.keySet()).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(readings.get("A").peers()).isEqualTo(3);
    }

    @Test
    void aNameThatOnlyStartedPrintingAfterTheMidpointIsNotAdmitted() {
        var f = forecaster(3, 4.0);
        printPair(f, "A", "EQUITY", "100", "102");
        printPair(f, "B", "EQUITY", "100", "101");
        printPair(f, "C", "EQUITY", "100", "100.5");
        f.observe("D", "EQUITY", new BigDecimal("100"), 700_000L); // both prints in the second half
        f.observe("D", "EQUITY", new BigDecimal("90"), 800_000L);

        assertThat(f.sweep(NOW).keySet()).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void aGroupSmallerThanMinPeersGetsNoView() {
        var f = forecaster(4, 4.0);
        printPair(f, "A", "EQUITY", "100", "102");
        printPair(f, "B", "EQUITY", "100", "101");
        printPair(f, "C", "EQUITY", "100", "100.5");

        assertThat(f.sweep(NOW)).isEmpty();
    }

    @Test
    void namesAreComparedOnlyWithinTheirOwnAssetClass() {
        var f = forecaster(3, 4.0);
        printPair(f, "A", "EQUITY", "100", "102");
        printPair(f, "B", "EQUITY", "100", "101");
        printPair(f, "C", "EQUITY", "100", "100.5");
        printPair(f, "EURUSD", "FX", "1.1", "1.1001");
        printPair(f, "GBPUSD", "FX", "1.3", "1.3002");

        var readings = f.sweep(NOW);

        // FX has two members against min-peers 3 — no view there, and crucially the FX moves have not
        // been mixed into the equity scale (which would read every FX residual as ~0 and every equity
        // residual as enormous).
        assertThat(readings.keySet()).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(readings.get("A").peerGroup()).isEqualTo("EQUITY");
    }

    @Test
    void anExtremeResidualIsClampedRatherThanCapturingTheScale() {
        var f = forecaster(4, 4.0);
        printPair(f, "A", "EQUITY", "100", "100.1");
        printPair(f, "B", "EQUITY", "100", "100.2");
        printPair(f, "C", "EQUITY", "100", "100.3");
        printPair(f, "D", "EQUITY", "100", "50"); // a bad print, or a name that genuinely halved

        var readings = f.sweep(NOW);

        assertThat(readings.get("D").score()).isEqualTo(4.0); // clamped, sign still the fade (buy the faller)
        assertThat(readings.get("A").score()).isBetween(-4.0, 4.0);
    }

    @Test
    void theSpanNormalisationMakesSlowAndFastPrintersComparable() {
        // A and B carry the SAME vol-time return over different observed spans: A moves 2% over the
        // full 600 s, B covers the same distance per √second in 150 s (a raw move of only ~1%).
        // Un-normalised, B would look like the laggard of the pair and be handed a spurious buy.
        // Normalised, the two are identical and the sensor takes the same view on both.
        var f = forecaster(3, 4.0);
        f.observe("A", "EQUITY", new BigDecimal("100"), 200_000L);
        f.observe("A", "EQUITY", new BigDecimal("102"), 800_000L);
        double sameVolTimeMove = 100 * Math.exp(Math.log(1.02) / 2.0); // √(150/600) = ½ the log move
        f.observe("B", "EQUITY", new BigDecimal("100"), 500_000L);  // straddles the midpoint, as required
        f.observe("B", "EQUITY", BigDecimal.valueOf(sameVolTimeMove), 650_000L);
        printPair(f, "C", "EQUITY", "100", "101");  // +1% over 600 s — the dispersion to measure against
        printPair(f, "D", "EQUITY", "100", "99");

        var readings = f.sweep(NOW);

        assertThat(readings.get("B").spanMillis()).isEqualTo(150_000L);
        assertThat(readings.get("A").spanMillis()).isEqualTo(600_000L);
        assertThat(readings.get("B").normalisedReturn())
                .isCloseTo(readings.get("A").normalisedReturn(), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(readings.get("B").score())
                .isCloseTo(readings.get("A").score(), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void printsOlderThanTheWindowAreDroppedAndAnOutOfOrderPrintIsRejected() {
        var f = forecaster(3, 4.0);
        f.observe("A", "EQUITY", new BigDecimal("500"), 50_000L); // before the window opens at 100 000
        printPair(f, "A", "EQUITY", "100", "102");
        f.observe("A", "EQUITY", new BigDecimal("999"), 700_000L); // out of order — must not overwrite
        printPair(f, "B", "EQUITY", "100", "101");
        printPair(f, "C", "EQUITY", "100", "100.5");

        var readings = f.sweep(NOW);

        // The 500 print is outside the window and the 999 print went backwards in provider time; A's
        // pair is still 100 → 102 over 600 000 ms, so its normalised return is the worked-example one.
        assertThat(readings.get("A").normalisedReturn())
                .isCloseTo(0.0008084388740358405, org.assertj.core.data.Offset.offset(1e-15));
        assertThat(readings.get("A").spanMillis()).isEqualTo(600_000L);
    }

    @Test
    void aNonPositiveOrNullPriceIsIgnored() {
        var f = forecaster(3, 4.0);
        f.observe("A", "EQUITY", null, ANCHOR_AT);
        f.observe("A", "EQUITY", BigDecimal.ZERO, ANCHOR_AT);
        f.observe("A", "EQUITY", new BigDecimal("-1"), ANCHOR_AT);
        f.observe(null, "EQUITY", new BigDecimal("100"), ANCHOR_AT);
        f.observe("B", null, new BigDecimal("100"), ANCHOR_AT);

        assertThat(f.trackedNames()).isZero();
        assertThat(f.sweep(NOW)).isEmpty();
    }

    @Test
    void paramsRefuseADegenerateGroupSizeAndAnUnusableBound() {
        var p = new CrossSectionalReversionForecaster.Params(0L, 2, 0.0);
        assertThat(p.lookbackMillis()).isEqualTo(1_000L);
        assertThat(p.minPeers()).isEqualTo(3); // a MAD of two points is a range, not a dispersion
        assertThat(p.maxAbsZ()).isEqualTo(4.0);
    }
}

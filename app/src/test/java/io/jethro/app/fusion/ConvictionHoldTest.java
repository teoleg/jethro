package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0145 — the conviction floor applied to the exit as well as the entry.
 *
 * <p>Exact values throughout: quantities are decimal and every expectation below is the arithmetic
 * stated in {@link ConvictionHold}'s javadoc, not a tolerance.
 */
class ConvictionHoldTest {

    private static final double FLOOR = 5.0; // the shipped jethro.fusion.min-forecast-to-route

    private static BigDecimal q(String v) {
        return new BigDecimal(v).setScale(6);
    }

    /**
     * The live pattern this exists to stop: a name opened at full conviction and unwound minutes later
     * at a forecast that would not have been allowed to open a single share of it. Held +34 against a
     * planner target of +0.25 (the same +34 position at a forecast of 0.0688 rather than 9.25) and no
     * control biting, so the whole reduction is the planner's own and none of it routes.
     */
    @Test
    void aReductionTheDecayedForecastAloneAuthoredIsHeldInFull() {
        BigDecimal delta = q("-33.750000");
        BigDecimal capped = ConvictionHold.apply(delta, q("34"), q("0.25"), q("0.25"), 0.0688, FLOOR);
        assertThat(capped).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** The same name once the view has genuinely reversed with conviction — nothing is held back. */
    @Test
    void theSameReductionRoutesInFullOnceTheForecastCarriesConviction() {
        BigDecimal delta = q("-33.750000");
        assertThat(ConvictionHold.apply(delta, q("34"), q("-8.00"), q("-8.00"), -8.25, FLOOR))
                .isEqualByComparingTo(delta);
    }

    /** Exactly at the floor is conviction: the entry test is {@code |f| < floor}, so this must mirror it. */
    @Test
    void theFloorItselfCounts() {
        BigDecimal delta = q("-10");
        assertThat(ConvictionHold.apply(delta, q("34"), q("1"), q("1"), -5.0, FLOOR))
                .isEqualByComparingTo(delta);
    }

    /**
     * A control halved the book while the forecast said nothing. The planner wanted MORE than is held,
     * so the forecast authored none of this reduction and the control's whole half routes:
     * h = 100, p = 120, c = 60 ⇒ min(h,p) − min(h,c) = 100 − 60 = 40.
     */
    @Test
    void aReductionARiskControlAuthoredRoutesInFullEvenWithNoConviction() {
        BigDecimal delta = q("-40");
        assertThat(ConvictionHold.apply(delta, q("100"), q("120"), q("60"), 0.1, FLOOR))
                .isEqualByComparingTo(q("-40"));
    }

    /**
     * Both authors at once: the forecast decayed the target to 80 and a control then cut it to 60. Only
     * the control's 20 routes — h = 100, p = 80, c = 60 ⇒ 80 − 60 = 20 of the 40 the buffer asked for.
     */
    @Test
    void onlyTheControlsShareOfAMixedReductionRoutes() {
        BigDecimal delta = q("-40");
        assertThat(ConvictionHold.apply(delta, q("100"), q("80"), q("60"), 0.1, FLOOR))
                .isEqualByComparingTo(q("-20"));
    }

    /** A control that ordered the EXIT is never held — the chandelier cut, the orphan unwind, the breaker. */
    @Test
    void aFlatTargetIsNeverHeld() {
        BigDecimal delta = q("-34");
        assertThat(ConvictionHold.apply(delta, q("34"), q("0.25"), BigDecimal.ZERO, 0.0688, FLOOR))
                .isEqualByComparingTo(delta);
    }

    /** The short side is the mirror image, sign for sign. */
    @Test
    void aShortIsHeldTheSameWay() {
        BigDecimal delta = q("119"); // buying back a short of 156, the live BAC pattern
        assertThat(ConvictionHold.apply(delta, q("-156"), q("-6"), q("-6"), -0.288, FLOOR))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ConvictionHold.apply(delta, q("-156"), q("-6"), q("-3"), -0.288, FLOOR))
                .isEqualByComparingTo(q("3")); // the control's 3, and only that
    }

    /** A forecast that flipped side but carries no conviction is absence, not a reversal to trade on. */
    @Test
    void aSubConvictionSignFlipIsNotAReversal() {
        BigDecimal delta = q("-34");
        assertThat(ConvictionHold.apply(delta, q("34"), q("-2"), q("-2"), -0.5, FLOOR))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** An INCREASE is a different question and is never touched, however weak the forecast. */
    @Test
    void anIncreaseIsUntouched() {
        BigDecimal delta = q("12");
        assertThat(ConvictionHold.apply(delta, q("34"), q("50"), q("50"), 0.01, FLOOR))
                .isEqualByComparingTo(delta);
    }

    /** Unwired, or with the conviction floor switched off, every path is byte-identical. */
    @Test
    void unwiredAndFloorlessAreBothPassThrough() {
        BigDecimal delta = q("-33.750000");
        assertThat(ConvictionHold.apply(delta, q("34"), null, q("0.25"), 0.0688, FLOOR))
                .isEqualByComparingTo(delta);
        assertThat(ConvictionHold.apply(delta, q("34"), q("0.25"), q("0.25"), 0.0688, 0.0))
                .isEqualByComparingTo(delta);
    }

    /** One-way: the order returned is never larger, and never of a different sign, than the one asked for. */
    @Test
    void itCanOnlyEverTradeLessAndNeverTheOtherWay() {
        BigDecimal delta = q("-40");
        BigDecimal capped = ConvictionHold.apply(delta, q("100"), q("80"), q("60"), 0.1, FLOOR);
        assertThat(capped.abs()).isLessThanOrEqualTo(delta.abs());
        assertThat(capped.signum()).isIn(0, delta.signum());
        // and the desk still ends up on the side it was already on — never flipped by the cap
        assertThat(q("100").add(capped).signum()).isEqualTo(1);
    }

    /** The cap never exceeds what the buffer asked for, even when the controls authored more than that. */
    @Test
    void theControlsShareNeverEnlargesTheOrder() {
        BigDecimal delta = q("-5"); // the buffer only asked for 5 of the 40 the controls would allow
        assertThat(ConvictionHold.apply(delta, q("100"), q("120"), q("60"), 0.1, FLOOR))
                .isEqualByComparingTo(q("-5"));
    }

    // ---- ADR-0149: a flat target is an exit only when something other than the forecast made it flat ----

    /**
     * The live leak this closes, taken from the tape: {@code PFE BUY 240 — fusion exit — target decayed
     * to flat [forecast=-0.0, sources=1]}. Short 240 against a forecast that finished decaying, so the
     * planner's own target is a literal zero, no control ran, and a source is still speaking. Before
     * ADR-0149 the {@code controlled == 0} exemption routed the whole 240 at market at zero conviction —
     * the largest possible order at the least possible conviction, through the exemption of the very
     * class that exists to stop it. Now the planner's share is {@code max(0, min(240,0) − min(240,0)) = 0}.
     */
    @Test
    void aTargetThatDecayedToFlatIsHeld() {
        BigDecimal delta = q("240"); // buying back the whole short
        assertThat(ConvictionHold.apply(delta, q("-240"), BigDecimal.ZERO, BigDecimal.ZERO, -0.0, FLOOR,
                false, 1)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** The ADR-0086 chandelier cut still flattens a decayed name in full — the floor is untouched. */
    @Test
    void aControlThatFlattenedTheNameStillRoutesInFull() {
        BigDecimal delta = q("240");
        assertThat(ConvictionHold.apply(delta, q("-240"), BigDecimal.ZERO, BigDecimal.ZERO, -0.0, FLOOR,
                true, 1)).isEqualByComparingTo(delta);
    }

    /** The ADR-0065 orphan — a held name no live source has a view on — is an unwind, not an opinion. */
    @Test
    void anOrphanWithNoLiveSourceStillUnwindsInFull() {
        BigDecimal delta = q("240");
        assertThat(ConvictionHold.apply(delta, q("-240"), BigDecimal.ZERO, BigDecimal.ZERO, 0.0, FLOOR,
                false, 0)).isEqualByComparingTo(delta);
    }

    /** A planner target that was ALIVE before the controls ran and flat after is theirs, and routes. */
    @Test
    void aControlThatTookALiveTargetToFlatRoutesInFull() {
        BigDecimal delta = q("-34");
        assertThat(ConvictionHold.apply(delta, q("34"), q("0.25"), BigDecimal.ZERO, 0.0688, FLOOR,
                false, 3)).isEqualByComparingTo(delta);
    }

    /** The six-argument overload is the pre-ADR-0149 reading exactly: every flat target routes in full. */
    @Test
    void theUnwiredOverloadIsByteIdentical() {
        BigDecimal delta = q("240");
        assertThat(ConvictionHold.apply(delta, q("-240"), BigDecimal.ZERO, BigDecimal.ZERO, -0.0, FLOOR))
                .isEqualByComparingTo(delta);
    }

    /** Held flat: there is no position to unwind, so the attribution never fires either way. */
    @Test
    void aFlatBookIsUntouchedByTheAttribution() {
        assertThat(ConvictionHold.apply(q("12"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, -0.0,
                FLOOR, false, 1)).isEqualByComparingTo(q("12"));
    }
}

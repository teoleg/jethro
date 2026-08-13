package io.jethro.app.fusion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * ADR-0137: the planned book's gross notional is capped at the gross the deterministic guardrail
 * permits the routing book to hold. Exact-decimal assertions throughout — the multiplier is money math.
 */
class GrossNotionalCapTest {

    private static final Function<String, BigDecimal> CASH_EQUITY = id -> BigDecimal.ONE;
    /** 30s cycle against the desk's 3600s base horizon (ADR-0080 identity), and the ADR-0094 band. */
    private static final FusionPlanner.Params PARAMS = new FusionPlanner.Params(
            0.5, new BigDecimal("250000"), 0.10, TargetPlanner.adjustmentRateFor(30, 3600));

    private static FusionPlanner.Target target(String instrument, String targetQty, String currentQty,
                                               String price) {
        return new FusionPlanner.Target(instrument, 6.0, 3, 1.0, 1.0, new BigDecimal(price),
                new BigDecimal(targetQty), new BigDecimal(currentQty), BigDecimal.ZERO, List.of());
    }

    /**
     * The worked example from ADR-0137, by hand:
     * <pre>
     *   A: target +100 @ 50.00 x 1  ->  |notional| = 5,000.00
     *   B: target -200 @ 25.00 x 1  ->  |notional| = 5,000.00
     *   plannedGross = 10,000.00 ; cap = 4,000.00 ; GNM = 4,000/10,000 = 0.4 exactly
     *   A' = +40.000000  ->  40 x 50 = 2,000.00
     *   B' = -80.000000  ->  80 x 25 = 2,000.00
     *   gross' = 4,000.00 = the cap, to the cent
     * </pre>
     */
    @Test
    void scalesTheBookToExactlyTheCapAndPreservesItsShape() {
        var cap = new GrossNotionalCap(new BigDecimal("4000.00"));

        var result = cap.apply(
                List.of(target("A", "100", "0", "50.00"), target("B", "-200", "0", "25.00")),
                CASH_EQUITY, PARAMS);

        assertThat(result.plannedGrossUsd()).isEqualByComparingTo("10000.00");
        assertThat(result.multiplier()).isEqualTo(0.4);
        assertThat(result.coveredNames()).isEqualTo(2);
        assertThat(result.targets().get(0).targetQty()).isEqualByComparingTo("40.000000");
        assertThat(result.targets().get(1).targetQty()).isEqualByComparingTo("-80.000000");
        assertThat(grossOf(result.targets())).isEqualByComparingTo("4000.00");
    }

    /** Under the cap the control is silent: same list instance, byte-identical book. */
    @Test
    void aBookInsideTheCapIsReturnedUntouched() {
        var cap = new GrossNotionalCap(new BigDecimal("20000.00"));
        List<FusionPlanner.Target> book =
                List.of(target("A", "100", "0", "50.00"), target("B", "-200", "0", "25.00"));

        var result = cap.apply(book, CASH_EQUITY, PARAMS);

        assertThat(result.targets()).isSameAs(book);
        assertThat(result.multiplier()).isEqualTo(1.0);
        assertThat(result.plannedGrossUsd()).isEqualByComparingTo("10000.00");
    }

    /** One-way: a book at exactly the cap is not levered up, and neither is one below it. */
    @Test
    void neverGrowsTheBook() {
        var cap = new GrossNotionalCap(new BigDecimal("10000.00"));

        var atCap = cap.apply(List.of(target("A", "100", "0", "50.00"), target("B", "-200", "0", "25.00")),
                CASH_EQUITY, PARAMS);

        assertThat(atCap.multiplier()).isEqualTo(1.0);
        assertThat(grossOf(atCap.targets())).isEqualByComparingTo("10000.00");
    }

    /** Sign- and shape-preserving: no name flips side, and the notional ratio between names holds. */
    @Test
    void preservesEverySignAndTheCrossSectionalRatio() {
        var cap = new GrossNotionalCap(new BigDecimal("3000.00"));

        var result = cap.apply(
                List.of(target("A", "120", "0", "50.00"), target("B", "-80", "0", "25.00")),
                CASH_EQUITY, PARAMS);

        // plannedGross = 6,000.00 + 2,000.00 = 8,000.00 ; GNM = 3,000/8,000 = 0.375 exactly
        assertThat(result.multiplier()).isEqualTo(0.375);
        assertThat(result.targets().get(0).targetQty()).isEqualByComparingTo("45.000000");   // +120 x 0.375
        assertThat(result.targets().get(1).targetQty()).isEqualByComparingTo("-30.000000");  //  -80 x 0.375
        assertThat(result.targets().get(0).targetQty().signum()).isEqualTo(1);
        assertThat(result.targets().get(1).targetQty().signum()).isEqualTo(-1);
        // 2,250.00 : 750.00 = 3 : 1, the same ratio as 6,000.00 : 2,000.00 before the cap
        assertThat(grossOf(result.targets())).isEqualByComparingTo("3000.00");
    }

    /** A name whose USD notional cannot be asserted enters neither the sum nor the scaling. */
    @Test
    void aNameWithNoContractMultiplierIsLeftExactlyAsPlanned() {
        var cap = new GrossNotionalCap(new BigDecimal("4000.00"));
        Function<String, BigDecimal> noSpecForB = id -> "B".equals(id) ? null : BigDecimal.ONE;

        var result = cap.apply(
                List.of(target("A", "100", "0", "50.00"), target("B", "-200", "0", "25.00")),
                noSpecForB, PARAMS);

        // Only A is covered: plannedGross = 5,000.00, GNM = 4,000/5,000 = 0.8
        assertThat(result.coveredNames()).isEqualTo(1);
        assertThat(result.plannedGrossUsd()).isEqualByComparingTo("5000.00");
        assertThat(result.multiplier()).isEqualTo(0.8);
        assertThat(result.targets().get(0).targetQty()).isEqualByComparingTo("80.000000");
        assertThat(result.targets().get(1).targetQty()).isEqualByComparingTo("-200"); // untouched
    }

    /**
     * The scaled book lands AT or UNDER the cap even when the multiplier does not terminate: the
     * division rounds DOWN, so rounding can never carry the book a step above the gross it may hold.
     */
    @Test
    void roundsDownSoTheCapIsNeverExceeded() {
        var cap = new GrossNotionalCap(new BigDecimal("1000.00"));

        // plannedGross = 3 x 1.00 x 1 = 3.00... scale it up: 3,000 shares @ 1.00 = 3,000.00
        var result = cap.apply(List.of(target("A", "3000", "0", "1.00")), CASH_EQUITY, PARAMS);

        assertThat(result.plannedGrossUsd()).isEqualByComparingTo("3000.00");
        assertThat(grossOf(result.targets())).isLessThanOrEqualTo(new BigDecimal("1000.00"));
    }

    /** The delta is recomputed against the SCALED target — the operator's book shows what will route. */
    @Test
    void recomputesTheOrderDeltaAgainstTheScaledTarget() {
        var cap = new GrossNotionalCap(new BigDecimal("4000.00"));

        var result = cap.apply(
                List.of(target("A", "100", "0", "50.00"), target("B", "-200", "0", "25.00")),
                CASH_EQUITY, PARAMS);

        var expectedA = TargetPlanner.orderDelta(new BigDecimal("40.000000"), BigDecimal.ZERO,
                PARAMS.bufferFraction(), PARAMS.adjustmentRate());
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(expectedA);
    }

    /** A cap that cannot bind is a disabled control — the wiring says so by not constructing one. */
    @Test
    void rejectsANonPositiveCap() {
        assertThatThrownBy(() -> new GrossNotionalCap(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrossNotionalCap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyBookIsNotAnError() {
        var cap = new GrossNotionalCap(new BigDecimal("4000.00"));

        var result = cap.apply(List.of(), CASH_EQUITY, PARAMS);

        assertThat(result.targets()).isEmpty();
        assertThat(result.multiplier()).isEqualTo(1.0);
    }

    private static BigDecimal grossOf(List<FusionPlanner.Target> targets) {
        BigDecimal gross = BigDecimal.ZERO;
        for (FusionPlanner.Target t : targets) {
            gross = gross.add(t.targetQty().multiply(t.price()).abs());
        }
        return gross;
    }
}

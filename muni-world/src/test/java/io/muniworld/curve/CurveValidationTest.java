package io.muniworld.curve;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0020 curve validations — Kalotay's discipline made mechanical: internal consistency (the
 * parameters must reprice the file's own published zeros), no-arbitrage (strictly decreasing discount
 * factors), level plausibility, and the lattice's own refusal to carry a step it cannot reprice.
 */
class CurveValidationTest {

    private static GswCsvParser.Row row(double b0, double b1, double b2, double b3, double t1, double t2,
                                        Map<Integer, Double> svens) {
        return new GswCsvParser.Row(LocalDate.of(2026, 8, 11), b0, b1, b2, b3, t1, t2, svens);
    }

    @Test
    void aNormalCurveDayPassesAllThreeChecks() {
        // SVENY01 = the true reconstruction from the worked example: y(1) = 4 − 0.632121 = 3.367879%.
        assertNull(GswCurveIngest.validate(row(4.0, -1.0, 0, 0, 1, 1, Map.of(1, 3.36787944))));
    }

    /** Internal consistency: parameters that do NOT reproduce the row's own published zero = mis-parse. */
    @Test
    void aRowWhoseParametersDoNotRepriceItsOwnPublishedZeroIsQuarantined() {
        String objection = GswCurveIngest.validate(row(4.0, -1.0, 0, 0, 1, 1, Map.of(1, 3.60)));
        assertNotNull(objection);
        assertTrue(objection.contains("SVENY01"), objection);
    }

    /** Plausibility: a unit slip (percent read as basis points, say) fails the band loudly. */
    @Test
    void anImplausibleLevelIsQuarantined() {
        String objection = GswCurveIngest.validate(row(400.0, -1.0, 0, 0, 1, 1, Map.of()));
        assertNotNull(objection);
        assertTrue(objection.contains("plausibility band"), objection);
        // and the band is two-sided:
        assertNotNull(GswCurveIngest.validate(row(-5.0, 0, 0, 0, 1, 1, Map.of())));
    }

    /** No-arbitrage: a curve whose long discount factors stop falling implies a negative forward. */
    @Test
    void aNegativeImpliedForwardIsQuarantined() {
        // Strongly inverted NSS: level 1%, slope +9% decaying slowly — long-end forwards go negative.
        String objection = GswCurveIngest.validate(row(1.0, 9.0, -25.0, 0, 12.0, 1, Map.of()));
        assertNotNull(objection);
        assertTrue(objection.contains("decreasing") || objection.contains("plausibility"), objection);
    }

    @Test
    void curveSanityNamesTheFailingTenor() {
        assertNull(CurveSanity.objection(new NelsonSiegelSvensson(4.0, -1.0, 0, 0, 1, 1)));
        String objection = CurveSanity.objection(new NelsonSiegelSvensson(60.0, 0, 0, 0, 1, 1));
        assertNotNull(objection);
        assertTrue(objection.contains("plausibility band"), objection);
    }

    /**
     * The lattice's own repricing guard: a discount-factor step too steep for the solve bracket (an
     * implied one-period rate beyond 500%) must REFUSE, never silently carry a mispriced step. The input
     * here is decreasing (so it passes the shape check) but corrupt in size.
     */
    @Test
    void latticeRefusesAStepItCannotReprice() {
        double[] df = {0.9, 0.9 * 0.04};   // one-step ratio 0.04 → implied rate ~6.4 > the 5.0 bracket
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BdtLattice.calibrate(df, 0.15, 0.5));
        assertTrue(e.getMessage().contains("cannot reprice"), e.getMessage());
    }

    /** And the plain shape check still rejects rising discount factors outright. */
    @Test
    void latticeRejectsRisingDiscountFactors() {
        assertThrows(IllegalArgumentException.class,
                () -> BdtLattice.calibrate(new double[] {0.95, 0.96}, 0.15, 0.5));
    }
}

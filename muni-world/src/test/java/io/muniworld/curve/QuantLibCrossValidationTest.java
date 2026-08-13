package io.muniworld.curve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-validation against QuantLib (ADR-0019): the fixtures in {@code quantlib-reference.json} are prices
 * computed by QuantLib's C++ engines (via scripts/quantlib-reference.py, version pinned in the file) on
 * convention-matched cases — flat continuous curve, exact half-year periods, clean-strike Bermudan calls
 * on coupon dates, Black-Karasinski with a≈0 (the same continuous model as constant-σ BDT).
 *
 * <p>Two different tolerances, because two different claims are being tested:
 * <ul>
 *   <li><b>Bullets</b> — both sides are exact discounting of identical cash flows on the identical curve;
 *       agreement must be at floating-point level. A miss here is a BUG, not a discretization gap.</li>
 *   <li><b>Callables</b> — our production semiannual binomial vs QuantLib's 500-step trinomial: the gap IS
 *       our production discretization error, and this test measures and bounds it. The bound is asserted
 *       both ways (each case AND the worst case), so a silent accuracy regression fails the build.</li>
 * </ul>
 */
class QuantLibCrossValidationTest {

    private static final double BULLET_TOL = 5e-7;      // float-precision agreement, both engines exact
    private static final double CALLABLE_TOL = 0.06;    // points per 100 — see measured gaps in the test log

    @Test
    void agreesWithQuantLibReferences() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/quantlib-reference.json")) {
            root = new ObjectMapper().readTree(in);
        }
        double worstBullet = 0;
        double worstCallable = 0;
        String worstCase = "";
        for (JsonNode c : root.get("cases")) {
            double y = c.get("flatYPct").asDouble() / 100.0;
            double coupon = c.get("couponPct").asDouble();
            double years = c.get("years").asDouble();
            double ql = c.get("qlClean").asDouble();
            int n = Math.max(2, (int) Math.round(2 * years));
            double dt = years / n;
            double[] df = new double[n];
            for (int i = 0; i < n; i++) {
                df[i] = Math.exp(-y * (i + 1) * dt);
            }
            String label = c.get("kind").asText() + " y=" + c.get("flatYPct") + " c=" + coupon
                    + " T=" + years + (c.has("sigma") ? " sigma=" + c.get("sigma").asDouble() : "");
            if (c.get("kind").asText().equals("bullet")) {
                // sigma is irrelevant for a bullet ON A CALIBRATED lattice — assert that too, at real vol.
                double ours = LatticeBondPricer.price(
                        BdtLattice.calibrate(df, 0.15, dt), coupon / 2, null, 0, 0.0);
                double diff = Math.abs(ours - ql);
                worstBullet = Math.max(worstBullet, diff);
                assertEquals(ql, ours, BULLET_TOL, label + " (ours " + ours + ")");
            } else {
                double sigma = c.get("sigma").asDouble();
                double callYears = c.get("callYears").asDouble();
                int firstCallStep = Math.max(1, (int) Math.ceil(callYears / dt - 1e-9));
                double ours = LatticeBondPricer.price(
                        BdtLattice.calibrate(df, sigma, dt), coupon / 2,
                        c.get("strike").asDouble(), firstCallStep, 0.0);
                double diff = Math.abs(ours - ql);
                if (diff > worstCallable) {
                    worstCallable = diff;
                    worstCase = label;
                }
                System.out.printf("%-40s ql=%.6f ours=%.6f diff=%.6f%n", label, ql, ours, diff);
                assertTrue(diff < CALLABLE_TOL,
                        label + ": ours " + ours + " vs QuantLib " + ql + " — gap " + diff
                        + " exceeds " + CALLABLE_TOL);
            }
        }
        System.out.printf("worst bullet diff %.2e; worst callable diff %.6f (%s)%n",
                worstBullet, worstCallable, worstCase);
    }
}

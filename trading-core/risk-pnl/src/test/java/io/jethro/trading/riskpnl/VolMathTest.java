package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EWMA daily vol (RiskMetrics λ=0.94): worked recursion, warm-up honesty, recency weighting. */
class VolMathTest {

    @Test
    void ewmaRecursionMatchesTheWorkedExample() {
        // Returns 1%, 2%, -1%: σ²₀ = 0.0001; σ²₁ = 0.94×0.0001 + 0.06×0.0004 = 0.000118;
        // σ²₂ = 0.94×0.000118 + 0.06×0.0001 = 0.00011692 → σ = 0.0108129...
        double vol = VolMath.ewmaDailyVol(new double[]{0.01, 0.02, -0.01}, 3).orElseThrow();
        assertEquals(Math.sqrt(0.00011692), vol, 1e-12);
    }

    @Test
    void belowMinObservationsIsUnmeasuredNeverGuessed() {
        assertTrue(VolMath.ewmaDailyVol(new double[]{0.01, 0.02}, 10).isEmpty());
        assertTrue(VolMath.ewmaDailyVol(new double[]{}, 1).isEmpty());
        assertTrue(VolMath.ewmaDailyVol(new double[]{0.0, 0.0, 0.0}, 3).isEmpty(),
                "degenerate zero vol is unmeasured, not a divide-by-zero invitation");
    }

    @Test
    void recentTurbulenceDominatesOldCalm() {
        double[] calmThenWild = new double[40];
        double[] wildThenCalm = new double[40];
        for (int i = 0; i < 40; i++) {
            calmThenWild[i] = i < 20 ? 0.002 : 0.03;
            wildThenCalm[i] = i < 20 ? 0.03 : 0.002;
        }
        double recentWild = VolMath.ewmaDailyVol(calmThenWild, 10).orElseThrow();
        double recentCalm = VolMath.ewmaDailyVol(wildThenCalm, 10).orElseThrow();
        // 0.94²⁰ ≈ 0.29 — the old regime still carries ~29% of the weight after 20 samples,
        // so the deterministic ratio here is 1.557 (not a dramatic 4×): assert the direction
        // with margin, exact recursion is covered above.
        assertTrue(recentWild > 1.5 * recentCalm,
                "λ=0.94 must weight the recent regime: wild " + recentWild + " vs calm " + recentCalm);
    }
}

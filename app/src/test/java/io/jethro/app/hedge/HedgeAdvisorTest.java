package io.jethro.app.hedge;

import io.jethro.trading.riskpnl.CovMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hedge advisor's target-flat, two-tier, HELD-HEDGE-FEEDBACK and PROXY-SELECTION behaviour
 * (ADR-0039/0040/0042 and the 2026-07-18 math review P1-1): the proposal is always
 * {@code target − held}, a book on target proposes nothing, a flat book unwinds its residual
 * hedge, and the proxy is chosen by measured ρ² with switch hysteresis. Hand-computed
 * covariances (ρ=0.9 ES/STOCK as in HedgeMathTest; ρ=0.95 NQ/STOCK) keep every size checkable.
 */
class HedgeAdvisorTest {

    /** ES + STOCK only (NQ uncovered): σ_ES=1%, σ_STOCK=2%, ρ=0.9 → β=1.8, ρ²=0.81. */
    private static CovMath.Covariance cov2() {
        double[][] sigma = {{1e-4, 1.8e-4}, {1.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 60);
    }

    /** ES, NQ and STOCK: NQ/STOCK ρ=0.95 → ρ²=0.9025 beats ES's 0.81; β_NQ=0.95. */
    private static CovMath.Covariance cov3() {
        double[][] sigma = {
                {1e-4, 1.8e-4, 1.8e-4},
                {1.8e-4, 4e-4, 3.8e-4},
                {1.8e-4, 3.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "NQ", "STOCK"), sigma, 60);
    }

    private static HedgeAdvisor advisor(HedgeAdvisor.Mode mode, String rebalanceFloorUsd) {
        return advisor(mode, rebalanceFloorUsd, 0.10);
    }

    private static HedgeAdvisor advisor(HedgeAdvisor.Mode mode, String rebalanceFloorUsd,
                                        double switchMargin) {
        // min-trade $10k (ADR-0039) with the 25%-of-scale relative leg (ADR-0069); min-covariance
        // 40d (ADR-0041; fixture covs have 60 obs); candidates ES,NQ with refdata-style
        // multipliers (ES 50, NQ 20); structural proxy ES.
        return new HedgeAdvisor(mode, new BigDecimal(rebalanceFloorUsd), new BigDecimal("10000"),
                new BigDecimal("0.25"), 0.25, 40, List.of("ES", "NQ"), switchMargin, "ES", MULTIPLIERS);
    }

    private static final Function<String, Optional<BigDecimal>> MULTIPLIERS = id -> switch (id) {
        case "ES" -> Optional.of(new BigDecimal("50"));
        case "NQ" -> Optional.of(new BigDecimal("20"));
        default -> Optional.empty();
    };
    private static final Function<String, Optional<BigDecimal>> PRICES = id -> switch (id) {
        case "ES" -> Optional.of(new BigDecimal("5600"));   // × 50 → $280k/contract
        case "NQ" -> Optional.of(new BigDecimal("20000"));  // × 20 → $400k/contract
        default -> Optional.empty();
    };
    private static final java.util.function.Predicate<String> IS_EQUITY = "STOCK"::equals;
    private static final Function<String, Optional<BigDecimal>> NO_BETA = id -> Optional.empty();
    private static final Function<String, Optional<BigDecimal>> STOCK_BETA_1_2 =
            id -> "STOCK".equals(id) ? Optional.of(new BigDecimal("1.2")) : Optional.empty();
    private static final Map<String, BigDecimal> NONE_HELD = Map.of();
    private static final Predicate<String> ALL_TRADABLE = id -> true;
    private static final Map<String, BigDecimal> LONG_1M = Map.of("STOCK", new BigDecimal("1000000"));

    private static HedgeAdvisor.Axis eval(HedgeAdvisor a, Optional<CovMath.Covariance> cov,
                                          Map<String, BigDecimal> exposures,
                                          Function<String, Optional<BigDecimal>> betaOf,
                                          Map<String, BigDecimal> held, Predicate<String> tradable) {
        return a.evaluate(cov, exposures, IS_EQUITY, PRICES, betaOf, held, tradable).axes().get(0);
    }

    @Test
    void firstHedgeProposesTheFullTarget() {
        var axis = eval(advisor(HedgeAdvisor.Mode.ADVISE, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("STATISTICAL", axis.tier());
        assertEquals("ES", axis.proxyId(), "NQ is not in this covariance — ES is the only sized candidate");
        assertEquals("SELL", axis.hedgeSide());
        assertEquals("HEDGE", axis.status());
        assertEquals(0.81, axis.effectiveness(), 1e-9);
        // β̂=1.8 → target −6.428571 ES; held 0 → the delta IS the full target.
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void onTargetBookProposesNothing_theP1RunawayRegression() {
        // The exact runaway scenario: the hedge from the last cycle is ON (held = target).
        // Without held-hedge feedback the advisor would propose the FULL hedge again forever.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertFalse(axis.hedging(), "held == target — nothing to trade");
        assertNull(axis.hedgeQuantity());
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void partialHoldingTopsUpOnlyTheDelta() {
        var axis = eval(advisor(HedgeAdvisor.Mode.ADVISE, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-4")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("SELL", axis.hedgeSide());
        // target −6.428571, held −4 → delta −2.428571.
        assertEquals(0, new BigDecimal("2.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void flatBookUnwindsTheResidualHedge() {
        // Equities all closed; a −6.4 ES hedge is still on. No underlying → no hedge (ADR-0039).
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of(), NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide(), "closing a short hedge buys it back");
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void deltaUnderTheMinTradeNotionalHolds() {
        // held −6.4 vs target −6.428571 → delta 0.028571 ES ≈ $8,000 < $10k min trade.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.4")), ALL_TRADABLE);
        assertFalse(axis.hedging(), "an $8k trim is churn, not a hedge");
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void smallBookResidualHedgeIsTrimmedNotStranded() {
        // ADR-0069, the live 2026-07-26 defect: the equity book collapsed to a fraction of its
        // former size, leaving a hedge far above its own target. β̂=1.8 on $2,800 net equity →
        // target −0.018 ES ($5,040 at $280k/contract); held −0.045 ES ($12,600). The delta is
        // 0.027 ES = $7,560 — under the $10k ABSOLUTE guard, so the pure-ADR-0039 rule held it
        // forever and $7.6k of unwanted proxy exposure sat on the firm book. The relative leg is
        // 25% × $12,600 = $3,150, and $7,560 clears it, so the excess is trimmed.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of("STOCK", new BigDecimal("2800")), NO_BETA,
                Map.of("ES", new BigDecimal("-0.045")), ALL_TRADABLE);
        assertTrue(axis.hedging(), "a 150%-of-target excess is not churn: " + axis.rationale());
        assertEquals("BUY", axis.hedgeSide(), "buying back part of a too-short hedge");
        assertEquals(0, new BigDecimal("0.027").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void tinyResidualHedgeIsAlwaysUnwound() {
        // ADR-0039 Decision 1 promises "no underlying, no hedge". Under a pure absolute guard that
        // promise failed silently for any residual smaller than the guard: equities flat, held
        // −0.02 ES = $5,600 < $10k, so the naked proxy leg lingered. With the band at 25% of the
        // hedge's own scale ($1,400) a zero target is reachable at any size.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of(), NO_BETA, Map.of("ES", new BigDecimal("-0.02")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("0.02").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void smallBookStillDampsSubBandChurn() {
        // The relative leg must not turn into a churn machine: β̂=1.8 on $3,080 → target −0.0198
        // ES, held −0.02 → a 0.0002 ES / $56 trim, which is 1% of the $5,600 hedge and far under
        // the $1,400 band. Nothing trades.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov2()),
                Map.of("STOCK", new BigDecimal("3080")), NO_BETA,
                Map.of("ES", new BigDecimal("-0.02")), ALL_TRADABLE);
        assertFalse(axis.hedging(), "a 1% drift is churn at any book size: " + axis.rationale());
        assertEquals("ON-TARGET", axis.status());
    }

    @Test
    void structuralTierHedgesTheDeltaWhenNoCovariance() {
        // No covariance — structural target from assigned β=1.2: −1.2M/280k = −4.285714 ES;
        // held −2 → SELL 2.285714 more.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.empty(),
                LONG_1M, STOCK_BETA_1_2, Map.of("ES", new BigDecimal("-2")), ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("STRUCTURAL", axis.tier());
        assertEquals("SELL", axis.hedgeSide());
        assertNull(axis.effectiveness(), "structural effectiveness is asserted, never a measured ρ²");
        assertEquals(0, new BigDecimal("2.285714").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void seedHeavyCovarianceYieldsToTheStructuralTier() {
        // Only 25 observations — under the 40-day ADR-0041 gate: the estimate is still too
        // seed-heavy to size real money, so the assigned-beta structural tier carries the book.
        var thin = new CovMath.Covariance(cov2().instruments(), cov2().sigma(), 25);
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(thin),
                LONG_1M, STOCK_BETA_1_2, NONE_HELD, ALL_TRADABLE);
        assertTrue(axis.hedging());
        assertEquals("STRUCTURAL", axis.tier(), "statistical gated below 40 obs");
        assertEquals(0, new BigDecimal("4.285714").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void picksTheHigherEffectivenessProxy() {
        // ADR-0042: NQ hedges this book better (ρ² 0.9025 vs ES 0.81) → buy the best option.
        // β_NQ = 3.8e-4/4e-4 = 0.95 → notional −$950k → 950k/400k = 2.375 NQ.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov3()),
                LONG_1M, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertEquals("NQ", axis.proxyId());
        assertEquals("SELL", axis.hedgeSide());
        assertEquals(0.9025, axis.effectiveness(), 1e-9);
        assertEquals(0, new BigDecimal("2.375").compareTo(axis.hedgeQuantity()));
        assertTrue(axis.rationale().contains("→ NQ"), "the comparison is shown: " + axis.rationale());
    }

    @Test
    void sticksWithTheHeldProxyInsideTheSwitchMargin() {
        // NQ is better by 0.0925 but the margin is 0.10 — flipping instruments on estimation
        // noise pays two spreads; the held ES hedge stays (and is already on target).
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0", 0.10), Optional.of(cov3()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertEquals("ES", axis.proxyId());
        assertEquals("ON-TARGET", axis.status());
        assertFalse(axis.hedging());
    }

    @Test
    void switchesProxiesByUnwindingTheOldOneFirst() {
        // Margin 0.05 < NQ's 0.0925 edge → switch. Cycle one UNWINDS the ES hedge (the larger
        // delta); the NQ hedge builds on a later cycle once ES is flat.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0", 0.05), Optional.of(cov3()),
                LONG_1M, NO_BETA, Map.of("ES", new BigDecimal("-6.428571")), ALL_TRADABLE);
        assertEquals("ES", axis.proxyId(), "old proxy unwinds before the new builds");
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
        assertTrue(axis.rationale().contains("before building the NQ hedge"), axis.rationale());
    }

    @Test
    void quarantinedProxyIsNeverBought() {
        // NQ measures better but is in no shape to trade (mark-quarantined) → ES is chosen.
        var axis = eval(advisor(HedgeAdvisor.Mode.AUTO, "0"), Optional.of(cov3()),
                LONG_1M, NO_BETA, NONE_HELD, id -> !"NQ".equals(id));
        assertEquals("ES", axis.proxyId());
        assertEquals("SELL", axis.hedgeSide());
        assertEquals(0.81, axis.effectiveness(), 1e-9);
    }

    @Test
    void cannotSizeWhenNeitherCovarianceNorBeta() {
        var axis = eval(advisor(HedgeAdvisor.Mode.ADVISE, "0"), Optional.empty(),
                LONG_1M, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertFalse(axis.hedging());
        assertNull(axis.hedgeQuantity());
        assertEquals("WARMING", axis.status());
    }

    @Test
    void offModeProposesNothing() {
        var snap = advisor(HedgeAdvisor.Mode.OFF, "0")
                .evaluate(Optional.of(cov2()), LONG_1M, IS_EQUITY, PRICES, STOCK_BETA_1_2,
                        NONE_HELD, ALL_TRADABLE);
        assertFalse(snap.axes().get(0).hedging());
        assertEquals("OFF", snap.mode());
        assertEquals("OFF", snap.axes().get(0).status());
    }

    @Test
    void onlyEquityNamesEnterTheAxisNotTheProxyItself() {
        // A held ES position (the hedge itself) must not count as equity exposure — it enters
        // through the held-quantity feedback, never through the axis sum.
        var snap = advisor(HedgeAdvisor.Mode.ADVISE, "0").evaluate(Optional.of(cov2()),
                Map.of("STOCK", new BigDecimal("1000000"), "ES", new BigDecimal("9000000")),
                Set.of("STOCK")::contains, PRICES, NO_BETA, NONE_HELD, ALL_TRADABLE);
        assertEquals(0, new BigDecimal("1000000.00").compareTo(snap.axes().get(0).netExposureUsd()));
    }

    // ---- ADR-0098: the hedge neutralizes only exposure that stands clear of its own churn ----

    /** Advisor with the ADR-0098 shrinkage active at k σ (0 = the pre-ADR-0098 behaviour). */
    private static HedgeAdvisor churnAdvisor(String churnSigmaMultiple) {
        return new HedgeAdvisor(HedgeAdvisor.Mode.AUTO, BigDecimal.ZERO, new BigDecimal("10000"),
                new BigDecimal("0.25"), 0.25, 40, List.of("ES", "NQ"), 0.10, "ES", MULTIPLIERS,
                new BigDecimal(churnSigmaMultiple));
    }

    private static HedgeAdvisor.Axis evalChurn(HedgeAdvisor a, Map<String, BigDecimal> exposures,
                                               Map<String, BigDecimal> held, String sigmaUsd) {
        Function<String, Optional<BigDecimal>> churn = id ->
                "EQUITY".equals(id) ? Optional.of(new BigDecimal(sigmaUsd)) : Optional.empty();
        return a.evaluate(Optional.of(cov2()), exposures, IS_EQUITY, PRICES, NO_BETA, held,
                ALL_TRADABLE, churn).axes().get(0);
    }

    @Test
    void aPersistentHedgeIsShrunkByExactlyOneSigmaOfItsOwnStep() {
        // $1m long STOCK, β̂ = 1.8 → target −6.428571 ES; at $280k/contract the RAW target notional
        // is −6.428571 × 280,000 = −$1,799,999.88. With σ_step = $280,000 (one contract) and k = 1:
        //   |−1,799,999.88| − 280,000 = 1,519,999.88  → shrunk −$1,519,999.88
        //   → −1,519,999.88 / 280,000 = −5.428571 ES  (exactly one contract less)
        // The sign is unchanged and the magnitude fell: a real hedge is trimmed, never reversed.
        var axis = evalChurn(churnAdvisor("1.0"), LONG_1M, NONE_HELD, "280000");
        assertEquals(0, new BigDecimal("-1799999.88").compareTo(axis.rawTargetNotionalUsd()));
        assertEquals(0, new BigDecimal("280000").compareTo(axis.churnSigmaUsd()));
        assertEquals(0, new BigDecimal("-5.428571").compareTo(axis.targetProxyQty()));
        assertEquals("SELL", axis.hedgeSide(), "still short the proxy against a long book");
        assertEquals(0, new BigDecimal("5.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void aTargetInsideItsOwnChurnIsSetFlatAndTheResidualHedgeIsUnwound() {
        // The live 2026-07-27 defect: the beta-weighted net is a rounding error next to the amount
        // it moves between hedges, so the desk was taking a signed proxy position on noise.
        // $2,800 long STOCK → β̂ = 1.8 → target −0.018 ES = −$5,040 raw. σ_step = $10,000 and k = 1:
        //   |−5,040| − 10,000 < 0 → target flat. Held −0.045 ES ($12,600) → BUY 0.045 to unwind
        //   ($12,600 clears the 25%-of-scale band of $3,150).
        var axis = evalChurn(churnAdvisor("1.0"), Map.of("STOCK", new BigDecimal("2800")),
                Map.of("ES", new BigDecimal("-0.045")), "10000");
        assertEquals(0, new BigDecimal("-5040.00").compareTo(axis.rawTargetNotionalUsd()));
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.targetProxyQty()));
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("0.045").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void theShrinkageIsOneWay_aHugeSigmaNeverFlipsTheHedgeLong() {
        // σ ten times the target: the hedge goes flat, and NOT long. |T'| ≤ |T| by construction,
        // so an over-estimated σ can only ever make the hedge smaller — never lever the book up.
        var axis = evalChurn(churnAdvisor("1.0"), LONG_1M, NONE_HELD, "18000000");
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.targetProxyQty()));
        assertFalse(axis.hedging(), "flat target, flat book — nothing to trade");
    }

    @Test
    void aZeroMultipleRestoresTheHedgeToExactlyFlatBehaviour() {
        var axis = evalChurn(churnAdvisor("0"), LONG_1M, NONE_HELD, "280000");
        assertEquals(0, new BigDecimal("-6.428571").compareTo(axis.targetProxyQty()));
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    // ---- ADR-0100: the hedge closes the gap at its target's own directional efficiency ----

    /** Evaluate with no churn σ (so ADR-0098 does not bind) and the ADR-0100 rate {@code a}. */
    private static HedgeAdvisor.Axis evalTracked(Map<String, BigDecimal> exposures,
                                                 Map<String, BigDecimal> held, String rate) {
        Function<String, Optional<BigDecimal>> efficiency = id ->
                "EQUITY".equals(id) ? Optional.of(new BigDecimal(rate)) : Optional.empty();
        return churnAdvisor("1.0").evaluate(Optional.of(cov2()), exposures, IS_EQUITY, PRICES,
                NO_BETA, held, ALL_TRADABLE, id -> Optional.empty(), efficiency).axes().get(0);
    }

    @Test
    void growingTheOverlayClosesOnlyTheFractionTheTargetEarned() {
        // $1m long STOCK, β̂ = 1.8 → target −6.428571 ES, held flat. At a = 0.25 the hedge takes a
        // quarter of the gap: 0 + 0.25 × (−6.428571 − 0) = −1.60714275 → −1.607143 at 6dp.
        // The delta is that same −1.607143 (held is flat) = $450,000.04, well clear of the band.
        var axis = evalTracked(LONG_1M, NONE_HELD, "0.25");
        assertEquals(0, new BigDecimal("0.25").compareTo(axis.trackingRate()));
        assertEquals(0, new BigDecimal("-1.607143").compareTo(axis.targetProxyQty()));
        assertEquals("SELL", axis.hedgeSide());
        assertEquals(0, new BigDecimal("1.607143").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void reducingTheOverlayStillTradesInOneCycle() {
        // Held −6.428571 ES against a book that has halved to $500k → target −3.214286 ES. The move
        // SHRINKS the overlay, so the rate does not apply: the hedge buys the whole 3.214285 back.
        var axis = evalTracked(Map.of("STOCK", new BigDecimal("500000")),
                Map.of("ES", new BigDecimal("-6.428571")), "0.25");
        assertEquals(0, new BigDecimal("-3.214286").compareTo(axis.targetProxyQty()));
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("3.214285").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void aFullUnwindIsNeverSlowed() {
        // Flat book → target zero. ADR-0069's promise is untouched at any rate: the residual hedge
        // is unwound in one cycle, not decayed toward flat a fraction at a time.
        var axis = evalTracked(Map.of("STOCK", BigDecimal.ZERO),
                Map.of("ES", new BigDecimal("-0.045")), "0.02");
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.targetProxyQty()));
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("0.045").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void crossingFlatCutsForFreeAndRebuildsSlowly() {
        // Held +2 ES (a long overlay left by the previous view) against a target of −6.428571.
        // The cut to flat is free; only the rebuild beyond zero is rated: q = 0.25 × −6.428571
        // = −1.607143. The delta the desk sends is the whole 3.607143 — it just stops there
        // instead of running on to −6.428571.
        var axis = evalTracked(LONG_1M, Map.of("ES", new BigDecimal("2")), "0.25");
        assertEquals(0, new BigDecimal("-1.607143").compareTo(axis.targetProxyQty()));
        assertEquals("SELL", axis.hedgeSide());
        assertEquals(0, new BigDecimal("3.607143").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void theRateIsOneWay_aChurningTargetIsNotChasedAtAll() {
        // The live 2026-07-27 defect: a target that only oscillates earns E ≈ (1−λ)/(1+λ). Taken
        // to its limit — E = 0 — the hedge does not grow at all, and it can NEVER grow past the
        // ADR-0098 target: |q| ≤ |d| in every branch, so an estimated rate cannot lever the book.
        var axis = evalTracked(LONG_1M, NONE_HELD, "0");
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.targetProxyQty()));
        assertFalse(axis.hedging(), "flat target, flat book — nothing to trade");
    }

    @Test
    void aRateOfOneReproducesThePreviousBehaviourExactly() {
        var axis = evalTracked(LONG_1M, NONE_HELD, "1");
        assertEquals(0, new BigDecimal("-6.428571").compareTo(axis.targetProxyQty()));
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void aWarmingEfficiencyEstimatorTracksInFull() {
        // Fewer than two steps → no rate → the advisor behaves exactly as it did before ADR-0100.
        var axis = churnAdvisor("1.0").evaluate(Optional.of(cov2()), LONG_1M, IS_EQUITY, PRICES,
                NO_BETA, NONE_HELD, ALL_TRADABLE, id -> Optional.empty(), id -> Optional.empty())
                .axes().get(0);
        assertNull(axis.trackingRate());
        assertEquals(0, new BigDecimal("-6.428571").compareTo(axis.targetProxyQty()));
    }

    // ---- ADR-0105: the overlay hedges back to the edge of the desk's own band, not to flat ----

    /** Evaluate with no churn σ and no ADR-0100 rate binding, at the ADR-0105 habitual net {@code H}. */
    private static HedgeAdvisor.Axis evalBanded(Map<String, BigDecimal> exposures,
                                                Map<String, BigDecimal> held, String habitualUsd) {
        Function<String, Optional<BigDecimal>> habitual = id ->
                "EQUITY".equals(id) ? Optional.of(new BigDecimal(habitualUsd)) : Optional.empty();
        return churnAdvisor("1.0").evaluate(Optional.of(cov2()), exposures, IS_EQUITY, PRICES,
                NO_BETA, held, ALL_TRADABLE, id -> Optional.empty(), id -> Optional.empty(),
                habitual).axes().get(0);
    }

    @Test
    void theOverlayCoversOnlyTheExposureStandingAboveTheDesksOwnHabitualNet() {
        // $1m long STOCK, β̂ = 1.8 → the full hedge is −6.428571 ES ($1.8m of ES notional, which
        // neutralizes the whole $1m of STOCK). The desk's own median |net| is $600,000, so
        //   f = (1,000,000 − 600,000) / 1,000,000 = 0.4 exactly
        //   q' = −6.428571 × 0.4 = −2.5714284 → −2.571428 at 6dp (HALF_EVEN)
        // and the overlay neutralizes 0.4 × $1m = $400,000, leaving exactly $600,000 — the band
        // edge — standing. Hedge to the edge, never to the centre.
        var axis = evalBanded(LONG_1M, NONE_HELD, "600000");
        assertEquals(0, new BigDecimal("600000").compareTo(axis.habitualNetUsd()));
        assertEquals(0, new BigDecimal("0.4").compareTo(axis.excessFraction()));
        assertEquals(0, new BigDecimal("-2.571428").compareTo(axis.targetProxyQty()));
        assertEquals("SELL", axis.hedgeSide(), "still short the proxy against a long book");
        assertEquals(0, new BigDecimal("2.571428").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void aNetInsideTheBandWearsNoOverlayAndTheResidualIsUnwound() {
        // The live 2026-07-27 defect: with the floor at zero the desk hedged an exposure it always
        // carries, paying the round trip every time that exposure oscillated. $500k long STOCK
        // against a $600k habitual net → f = 0 → no overlay; the held −0.045 ES ($12,600, clear of
        // the $3,150 band) is unwound in one cycle.
        var axis = evalBanded(Map.of("STOCK", new BigDecimal("500000")),
                Map.of("ES", new BigDecimal("-0.045")), "600000");
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.excessFraction()));
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.targetProxyQty()));
        assertEquals("UNWIND", axis.status());
        assertEquals("BUY", axis.hedgeSide());
        assertEquals(0, new BigDecimal("0.045").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void theBandIsOneWay_anOverstatedBandNeverFlipsOrGrowsTheHedge() {
        // A band ten times the net: the overlay goes flat, and NOT long. f ∈ [0,1] and the
        // multiplier is positive, so |q'| ≤ |q| and the sign can never flip — an estimated band
        // can only ever leave the desk with less overlay than ADR-0098 already allowed.
        var axis = evalBanded(LONG_1M, NONE_HELD, "10000000");
        assertEquals(0, BigDecimal.ZERO.compareTo(axis.targetProxyQty()));
        assertFalse(axis.hedging(), "flat target, flat book — nothing to trade");
    }

    @Test
    void aBandOfZeroReproducesHedgingToFlatExactly() {
        // H = 0 → f = 1: the pre-ADR-0105 behaviour, byte for byte.
        var axis = evalBanded(LONG_1M, NONE_HELD, "0");
        assertEquals(0, BigDecimal.ONE.compareTo(axis.excessFraction()));
        assertEquals(0, new BigDecimal("-6.428571").compareTo(axis.targetProxyQty()));
        assertEquals(0, new BigDecimal("6.428571").compareTo(axis.hedgeQuantity()));
    }

    @Test
    void aWarmingBandHedgesExactlyAsItDidBefore() {
        // Fewer than min-sample observations → no band → no claim, and the advisor behaves exactly
        // as it did before ADR-0105.
        var axis = churnAdvisor("1.0").evaluate(Optional.of(cov2()), LONG_1M, IS_EQUITY, PRICES,
                NO_BETA, NONE_HELD, ALL_TRADABLE, id -> Optional.empty(), id -> Optional.empty(),
                id -> Optional.empty()).axes().get(0);
        assertNull(axis.habitualNetUsd());
        assertNull(axis.excessFraction());
        assertEquals(0, new BigDecimal("-6.428571").compareTo(axis.targetProxyQty()));
    }

    @Test
    void aWarmingEstimatorShrinksNothing() {
        // No σ yet (fewer than two steps) → the advisor behaves exactly as it did before ADR-0098.
        var axis = churnAdvisor("1.0").evaluate(Optional.of(cov2()), LONG_1M, IS_EQUITY, PRICES,
                NO_BETA, NONE_HELD, ALL_TRADABLE, id -> Optional.empty()).axes().get(0);
        assertNull(axis.churnSigmaUsd());
        assertEquals(0, new BigDecimal("-6.428571").compareTo(axis.targetProxyQty()));
    }
}

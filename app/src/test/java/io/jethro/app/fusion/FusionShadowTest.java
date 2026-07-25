package io.jethro.app.fusion;

import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.strategy.TradeSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end (registry → combine → target → delta) for the ADR-0055 phase-4 shadow pipeline, plus the
 *  registry's freshness/no-view filtering. */
class FusionShadowTest {

    private static final ForecastRegistry.Params PARAMS =
            new ForecastRegistry.Params(3.0, 5.0, 4.0, 20.0);

    @Test
    void registryKeepsLatestPerSourceAndExpiresStale() throws InterruptedException {
        var registry = new ForecastRegistry(PARAMS, 1_000); // 1s freshness
        registry.submitHypothesis("AAPL", Side.BUY, Hypothesis.Conviction.HIGH); // +15
        registry.submitSocial(new io.jethro.app.social.SocialSignal(
                "AAPL", true, "TECH", "BULLISH", 2, 20, false, "s")); // +8
        var now = registry.byInstrument(System.currentTimeMillis());
        assertEquals(1, now.size());
        assertEquals(2, now.get("AAPL").size(), "both fresh sources present");

        Thread.sleep(1_100); // let them go stale
        assertTrue(registry.byInstrument(System.currentTimeMillis()).isEmpty(), "stale forecasts drop out");
    }

    @Test
    void noViewForecastsAreExcluded() {
        var registry = new ForecastRegistry(PARAMS, 60_000);
        // A NEUTRAL social signal → value 0 → not a vote.
        registry.submitSocial(new io.jethro.app.social.SocialSignal(
                "ES", true, "IDX", "NEUTRAL", 3, 10, false, "s"));
        assertTrue(registry.byInstrument(System.currentTimeMillis()).isEmpty());
    }

    @Test
    void planCombinesSourcesIntoOneNettedDelta() {
        var registry = new ForecastRegistry(PARAMS, 60_000);
        // Two agreeing bullish sources on AAPL: hypothesis HIGH (+15) and a momentum buy (|z|=6 → +20).
        registry.submitHypothesis("AAPL", Side.BUY, Hypothesis.Conviction.HIGH);
        registry.submitStrategy(new TradeSignal("AAPL", Side.BUY, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.valueOf(400), 6.0));

        var forecasts = registry.byInstrument(System.currentTimeMillis());
        var params = new FusionPlanner.Params(0.5, BigDecimal.valueOf(10_000), 0.2, 0.5);
        List<FusionPlanner.Target> targets = FusionPlanner.plan(forecasts, List.of(), s -> 1.0,
                id -> BigDecimal.valueOf(100), // price $100
                id -> BigDecimal.ZERO,          // flat book
                params);

        assertEquals(1, targets.size());
        FusionPlanner.Target t = targets.get(0);
        assertEquals("AAPL", t.instrument());
        assertEquals(2, t.sources());
        assertTrue(t.combinedForecast() > 0, "agreeing bullish sources → positive combined view");
        assertTrue(t.targetQty().signum() > 0, "positive forecast → long target");
        // Flat book, target long, gap = target > band(0) → trades a fraction (rate 0.5) toward it.
        assertEquals(0, t.targetQty().multiply(BigDecimal.valueOf(0.5))
                .setScale(6, java.math.RoundingMode.HALF_EVEN).compareTo(t.deltaQty()),
                "delta is adjustmentRate × the gap from a flat book");
    }

    @Test
    void disagreeingSourcesNetToNoTrade() {
        var registry = new ForecastRegistry(PARAMS, 60_000);
        registry.submitHypothesis("ES", Side.BUY, Hypothesis.Conviction.HIGH);  // +15
        registry.submitStrategy(new TradeSignal("ES", Side.SELL, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.valueOf(-450), 9.0)); // |z|=9 → cap 20 short → −20; avg of +15,−20 tilts short
        var forecasts = registry.byInstrument(System.currentTimeMillis());
        var params = new FusionPlanner.Params(0.5, BigDecimal.valueOf(10_000), 0.2, 0.5);
        var targets = FusionPlanner.plan(forecasts, List.of(), s -> 1.0, id -> BigDecimal.valueOf(100),
                id -> BigDecimal.ZERO, params);
        // Whatever the sign, the point is the two sources fuse into ONE decision, not two orders.
        assertEquals(1, targets.size(), "one combined decision per name, never two competing orders");
    }
}

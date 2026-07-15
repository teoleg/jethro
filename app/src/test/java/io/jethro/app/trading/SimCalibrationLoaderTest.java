package io.jethro.app.trading;

import io.jethro.trading.marketdata.sim.CorrelatedFactorSimulator;
import io.jethro.trading.marketdata.sim.FactorModelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checked-in default calibration (ADR-0026) must load, cover the FULL price-quoted
 * universe, and be usable end to end — every regime matrix Cholesky-decomposable (the
 * simulator constructor validates), transition rows row-stochastic (the loader validates).
 */
class SimCalibrationLoaderTest {

    @Test
    void defaultCalibrationLoadsAndCoversTheUniverse() throws Exception {
        FactorModelConfig cfg = SimCalibrationLoader.load(null);

        Set<String> ids = cfg.instruments().stream()
                .map(FactorModelConfig.InstrumentSpec::id).collect(Collectors.toSet());
        // Full price-quoted universe: equities + index futures + FX. (Treasury futures and
        // swaps intentionally absent — they price FROM the curve, ADR-0026.)
        for (String required : List.of("AAPL", "MSFT", "AMZN", "GOOG", "SAP",
                "ES", "NQ", "EURUSD", "GBPUSD", "USDJPY")) {
            assertTrue(ids.contains(required), "calibration missing " + required);
        }
        assertEquals(5, cfg.regimes().size(), "CALM, TREND_UP, TREND_DOWN, RISK_OFF, INFLATION_SHOCK");
        assertTrue(cfg.regimeIndex("RISK_OFF") >= 0);
        assertTrue(cfg.regimeIndex("INFLATION_SHOCK") >= 0);

        // End-to-end usability: the simulator constructor Cholesky-decomposes every regime
        // matrix and derives idio vols — a non-PSD matrix or bad spec throws here.
        var sim = new CorrelatedFactorSimulator(1, cfg, List.of("ES", "AAPL", "EURUSD"),
                new long[]{5_450_000_000L, 190_000_000L, 1_085_000L}, 0.1, 120);
        sim.nextTick();
        assertTrue(sim.priceScaled(0) > 0);
    }
}

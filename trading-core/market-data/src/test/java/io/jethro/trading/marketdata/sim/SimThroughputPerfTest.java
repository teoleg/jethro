package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throughput guardrail for the correlated market simulator (ADR-0026) — see
 * docs/architecture/perf-budgets.md. One "tape tick" here is the full per-tick work the
 * adapter does per instrument: the correlated factor step (Cholesky draw, shared Student-t
 * scale, per-instrument idio + exp) plus bid/ask synthesis (ADR-0025) for every instrument.
 *
 * <p>Production asks for ~25 instruments at 50 ms = 20 tape ticks/s; the floor is set at
 * 2,000/s — 100× the production need and still 20–50× under what a 2-vCPU CI runner
 * measures — so the test only fails when the simulator's per-tick cost regresses by an
 * order of magnitude (e.g. an accidental allocation storm or O(n²) step). The measured
 * rate is printed for trend-watching in CI logs.
 */
class SimThroughputPerfTest {

    private static final int INSTRUMENTS = 25;
    private static final int WARMUP_TICKS = 5_000;
    private static final int MEASURED_TICKS = 50_000;
    private static final long FLOOR_TICKS_PER_SEC = 2_000;

    private static final double[][] CALM_CORR = {
            {1.00, 0.30, 0.10, -0.30},
            {0.30, 1.00, -0.20, -0.10},
            {0.10, -0.20, 1.00, 0.00},
            {-0.30, -0.10, 0.00, 1.00}};

    /** Blackhole so the JIT cannot dead-code the price/quote reads. */
    private static long sink;

    private static CorrelatedFactorSimulator sim(int instruments) {
        List<FactorModelConfig.InstrumentSpec> specs = new ArrayList<>();
        long[] startPrices = new long[instruments];
        for (int i = 0; i < instruments; i++) {
            specs.add(new FactorModelConfig.InstrumentSpec(
                    "SYM" + i, 0.20 + 0.01 * (i % 10), 0.8 + 0.05 * (i % 8), i % 5 == 0 ? -1.0 : 0.0));
            startPrices[i] = (100 + i) * 1_000_000L;
        }
        var cfg = new FactorModelConfig(0.16, 0.07, 5.0, 2.0, 5, specs,
                List.of(new FactorModelConfig.RegimeSpec("CALM", 0, 0, 0, 1.0, CALM_CORR)),
                new double[][]{{1.0}});
        return new CorrelatedFactorSimulator(42, cfg,
                specs.stream().map(FactorModelConfig.InstrumentSpec::id).toList(),
                startPrices, 0.05, 120);
    }

    private static void tape(CorrelatedFactorSimulator sim, Quotes.QuoteSpec spec, int ticks) {
        for (int t = 0; t < ticks; t++) {
            sim.nextTick();
            for (int i = 0; i < INSTRUMENTS; i++) {
                long mid = sim.priceScaled(i);
                sink += Quotes.bidScaled(mid, spec) + Quotes.askScaled(mid, spec);
            }
        }
    }

    @Test
    void correlatedTapeWithQuoteSynthesisStaysFarAboveTheProductionTickRate() {
        var sim = sim(INSTRUMENTS);
        var spec = new Quotes.QuoteSpec(500, false); // EQUITY 5bp, the common case
        tape(sim, spec, WARMUP_TICKS);

        long start = System.nanoTime();
        tape(sim, spec, MEASURED_TICKS);
        long nanos = System.nanoTime() - start;

        long ticksPerSec = MEASURED_TICKS * 1_000_000_000L / Math.max(1, nanos);
        System.out.printf("[perf] correlated sim tape (%d instruments + quotes): %,d ticks/s (floor %,d)%n",
                INSTRUMENTS, ticksPerSec, FLOOR_TICKS_PER_SEC);
        assertTrue(ticksPerSec > FLOOR_TICKS_PER_SEC,
                "sim tape degraded to " + ticksPerSec + " ticks/s — the floor "
                        + FLOOR_TICKS_PER_SEC + " is 100× the 20/s production tape");
    }
}

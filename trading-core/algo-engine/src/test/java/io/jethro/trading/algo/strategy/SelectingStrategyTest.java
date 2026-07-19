package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SelectingStrategy forwards only the chosen algo's signals per instrument, drops NO_TRADE names,
 *  and falls back to the default when an instrument hasn't been measured (ADR-0043). */
class SelectingStrategyTest {

    /** A stub that emits one BUY signal for each configured instrument, tagged with its algo name. */
    private static Strategy stub(String algo, String... instruments) {
        return new Strategy() {
            @Override public List<TradeSignal> evaluate(List<Observation> obs) {
                return java.util.Arrays.stream(instruments)
                        .map(id -> new TradeSignal(id, Side.BUY, BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ZERO, 0.0, 0.0, algo))
                        .toList();
            }
            @Override public String name() { return algo; }
        };
    }

    private static final List<Strategy.Observation> OBS = List.of(); // stubs ignore the observations

    @Test
    void forwardsOnlyTheChosenAlgoPerInstrument() {
        Map<String, Strategy> byAlgo = Map.of(
                "momentum", stub("momentum", "AAPL", "MSFT"),
                "mean-reversion", stub("mean-reversion", "AAPL", "MSFT"));
        // AAPL → momentum, MSFT → mean-reversion.
        Function<String, String> chooser = id -> "AAPL".equals(id) ? "momentum" : "mean-reversion";
        var out = new SelectingStrategy(byAlgo, chooser, "momentum").evaluate(OBS);
        assertEquals(2, out.size(), "one signal per instrument, from its chosen algo");
        assertEquals("momentum", signalFor(out, "AAPL").kind());
        assertEquals("mean-reversion", signalFor(out, "MSFT").kind());
    }

    @Test
    void noTradeInstrumentEmitsNothing() {
        Map<String, Strategy> byAlgo = Map.of(
                "momentum", stub("momentum", "AAPL"),
                "mean-reversion", stub("mean-reversion", "AAPL"));
        var out = new SelectingStrategy(byAlgo, id -> SelectingStrategy.NO_TRADE, "momentum").evaluate(OBS);
        assertTrue(out.isEmpty(), "NO_TRADE suppresses every algo's signal for the name");
    }

    @Test
    void unmeasuredInstrumentFallsBackToTheDefault() {
        Map<String, Strategy> byAlgo = Map.of(
                "momentum", stub("momentum", "NVDA"),
                "mean-reversion", stub("mean-reversion", "NVDA"));
        // chooser has no opinion (null) → default mean-reversion is used.
        var out = new SelectingStrategy(byAlgo, id -> null, "mean-reversion").evaluate(OBS);
        assertEquals(1, out.size());
        assertEquals("mean-reversion", signalFor(out, "NVDA").kind());
    }

    private static TradeSignal signalFor(List<TradeSignal> signals, String id) {
        return signals.stream().filter(s -> s.instrumentId().equals(id)).findFirst().orElseThrow();
    }

    // ---- ADR-0044: regime-aware path (detector picks the algo, edge gate may veto) ----

    private static Strategy.Observation mark(String id, String price) {
        return new Strategy.Observation(id, new BigDecimal(price), false);
    }

    /** Drive `n` evaluate cycles feeding the given prices in order; return the LAST cycle's signals. */
    private static List<TradeSignal> driveRegime(SelectingStrategy s, String id, String... prices) {
        List<TradeSignal> out = List.of();
        for (String p : prices) {
            out = s.evaluate(List.of(mark(id, p)));
        }
        return out;
    }

    @Test
    void regimeAwarePicksMomentumInATrend() {
        Map<String, Strategy> byAlgo = Map.of(
                "momentum", stub("momentum", "AAPL"),
                "mean-reversion", stub("mean-reversion", "AAPL"));
        var detector = new TrendDetector(2, new BigDecimal("0.5"), new BigDecimal("0.3"));
        var s = new SelectingStrategy(byAlgo, detector, "momentum", "mean-reversion", "mean-reversion",
                (id, candidate) -> candidate); // gate allows everything
        var out = driveRegime(s, "AAPL", "10", "11", "12"); // clean uptrend → TREND → momentum
        assertEquals(1, out.size());
        assertEquals("momentum", signalFor(out, "AAPL").kind());
    }

    @Test
    void regimeAwarePicksMeanReversionInChop() {
        Map<String, Strategy> byAlgo = Map.of(
                "momentum", stub("momentum", "AAPL"),
                "mean-reversion", stub("mean-reversion", "AAPL"));
        var detector = new TrendDetector(2, new BigDecimal("0.5"), new BigDecimal("0.3"));
        var s = new SelectingStrategy(byAlgo, detector, "momentum", "mean-reversion", "momentum",
                (id, candidate) -> candidate);
        var out = driveRegime(s, "AAPL", "10", "11", "10"); // oscillation → CHOP → mean-reversion
        assertEquals(1, out.size());
        assertEquals("mean-reversion", signalFor(out, "AAPL").kind());
    }

    @Test
    void edgeGateVetoesTheTrendMatchedAlgoToNoTrade() {
        Map<String, Strategy> byAlgo = Map.of(
                "momentum", stub("momentum", "AAPL"),
                "mean-reversion", stub("mean-reversion", "AAPL"));
        var detector = new TrendDetector(2, new BigDecimal("0.5"), new BigDecimal("0.3"));
        // The name trends → detector picks momentum → the OOS gate vetoes momentum (measured no-edge).
        var s = new SelectingStrategy(byAlgo, detector, "momentum", "mean-reversion", "mean-reversion",
                (id, candidate) -> "momentum".equals(candidate) ? SelectingStrategy.NO_TRADE : candidate);
        var out = driveRegime(s, "AAPL", "10", "11", "12");
        assertTrue(out.isEmpty(), "a measured-negative trend algo is vetoed, not traded");
    }

    @Test
    void regimeAwareWarmupRunsTheDefaultAlgo() {
        Map<String, Strategy> byAlgo = Map.of(
                "momentum", stub("momentum", "AAPL"),
                "mean-reversion", stub("mean-reversion", "AAPL"));
        var detector = new TrendDetector(4, new BigDecimal("0.5"), new BigDecimal("0.3"));
        var s = new SelectingStrategy(byAlgo, detector, "momentum", "mean-reversion", "mean-reversion",
                (id, candidate) -> candidate);
        var out = driveRegime(s, "AAPL", "10", "11"); // window not full → UNKNOWN → default (mean-reversion)
        assertEquals(1, out.size());
        assertEquals("mean-reversion", signalFor(out, "AAPL").kind());
    }
}

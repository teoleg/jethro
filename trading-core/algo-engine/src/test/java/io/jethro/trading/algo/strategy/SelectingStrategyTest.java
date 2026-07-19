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
}

package io.jethro.app.fusion;

import io.jethro.app.risk.TradingHaltSwitch;
import io.jethro.app.strategy.StrategySelector;
import io.jethro.messaging.FeedMode;
import io.jethro.messaging.Provenance;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0060 monitor-only veto at the sole order origin: fusion never routes an order for a
 * discovery-promoted (monitor-only) name, even though it flows into marks/indicators/signals.
 */
class FusionExecutorMonitorOnlyTest {

    /** Ref source where PLTR is monitor-only and AAPL is a normal tradable name. */
    private final InstrumentRefSource refs = new InstrumentRefSource() {
        @Override public Optional<InstrumentRef> find(String id) {
            return Optional.of(new InstrumentRef(id, "EQUITY", "USD", BigDecimal.ONE));
        }
        @Override public boolean monitorOnly(String id) {
            return "PLTR".equals(id);
        }
    };

    /** A no-op ObjectProvider that supplies no StrategySelector (backtest gate then passes through). */
    private static ObjectProvider<StrategySelector> noSelector() {
        return new ObjectProvider<>() {
            @Override public StrategySelector getObject() { throw new UnsupportedOperationException(); }
            @Override public StrategySelector getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public StrategySelector getIfAvailable() { return null; }
            @Override public StrategySelector getIfUnique() { return null; }
            @Override public Iterator<StrategySelector> iterator() { return Collections.emptyIterator(); }
        };
    }

    @BeforeEach
    void simMode() {
        Provenance.configure(FeedMode.SIM, "test-epoch"); // fusion routing is sim-only; put it in SIM
    }

    @Test
    void monitorOnlyNameIsVetoedBeforeAnyOrder() {
        var executor = new FusionExecutor(null, refs, null, new TradingHaltSwitch(), null, noSelector());
        FusionExecutor.Result r = executor.route("PLTR", new BigDecimal("100"));
        assertFalse(r.routed(), "a monitor-only name must never be routed");
        assertTrue(r.reason().contains("monitor-only"), "vetoed for the ADR-0060 monitor-only reason: " + r.reason());
    }

    @Test
    void aNormalNameIsNotVetoedForMonitorOnly() {
        // AAPL is tradable: it passes the monitor-only gate. (It fails later on the null guardrail — proving
        // it got PAST the monitor-only veto, which is the point.)
        var executor = new FusionExecutor(null, refs, null, new TradingHaltSwitch(), null, noSelector());
        FusionExecutor.Result r = executor.route("AAPL", new BigDecimal("100"));
        assertFalse(r.reason() != null && r.reason().contains("monitor-only"),
                "a normal name is not stopped by the monitor-only veto");
    }
}

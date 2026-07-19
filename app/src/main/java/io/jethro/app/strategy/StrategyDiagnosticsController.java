package io.jethro.app.strategy;

import io.jethro.trading.algo.strategy.SelectingStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Why is the book (not) trading right now" (ADR-0052). Combines the strategy's last-cycle snapshot
 * (how many names cleared the signal threshold and what became of each) with the OOS selector's
 * edge-gated names (measured but stood down as no-edge, ADR-0043/0049) — the two independent reasons
 * activity goes quiet. Read-only; drives the diagnostic panel on /strategy.html.
 */
@RestController
public final class StrategyDiagnosticsController {

    private final ObjectProvider<StrategyLifecycle> lifecycle;
    private final ObjectProvider<StrategySelector> selector;

    public StrategyDiagnosticsController(ObjectProvider<StrategyLifecycle> lifecycle,
                                         ObjectProvider<StrategySelector> selector) {
        this.lifecycle = lifecycle;
        this.selector = selector;
    }

    /** One measured-but-stood-down name and the medians behind the verdict. */
    public record EdgeGated(String instrumentId, String note) {
    }

    public record Diagnostics(StrategyLifecycle.Diag cycle, int measured, int tradable,
                              List<EdgeGated> edgeGated, long lastSelectionMillis, String selectionError) {
    }

    @GetMapping("/api/strategy/diagnostics")
    public Diagnostics diagnostics() {
        StrategyLifecycle lc = lifecycle.getIfAvailable();
        StrategyLifecycle.Diag cycle = lc != null ? lc.diagnostics()
                : new StrategyLifecycle.Diag(0, 0, 0, 0, 0, "—", "1", false, false, 0, 0, 0, List.of());

        StrategySelector sel = selector.getIfAvailable();
        List<EdgeGated> gated = new ArrayList<>();
        int measured = 0;
        int tradable = 0;
        long lastRun = 0;
        String error = null;
        if (sel != null) {
            Map<String, StrategySelector.Choice> selection = sel.selection();
            measured = selection.size();
            lastRun = sel.lastRunMillis();
            error = sel.lastError();
            for (var e : selection.entrySet()) {
                StrategySelector.Choice c = e.getValue();
                if (SelectingStrategy.NO_TRADE.equals(c.algo())) {
                    gated.add(new EdgeGated(e.getKey(), String.format(
                            "no positive OOS edge — momentum %s over %d paths, mean-rev %s over %d",
                            c.momentumMedianPnl().toPlainString(), c.momentumTrades(),
                            c.meanReversionMedianPnl().toPlainString(), c.meanReversionTrades())));
                } else {
                    tradable++;
                }
            }
        }
        return new Diagnostics(cycle, measured, tradable, gated, lastRun, error);
    }
}

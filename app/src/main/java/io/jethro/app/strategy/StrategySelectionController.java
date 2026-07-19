package io.jethro.app.strategy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Exposes the ADR-0043 per-instrument strategy selection so the call is legible, not a black box:
 * for each instrument, the chosen algo and the two OOS medians behind the decision. Empty until the
 * first background measurement completes (or when selection is disabled).
 */
@RestController
public final class StrategySelectionController {

    private final ObjectProvider<StrategySelector> selector;

    public StrategySelectionController(ObjectProvider<StrategySelector> selector) {
        this.selector = selector;
    }

    public record Row(String instrument, String chosen, BigDecimal momentumMedianPnl, int momentumTrades,
                      BigDecimal meanReversionMedianPnl, int meanReversionTrades) {
    }

    public record SelectionView(boolean available, long lastRunMillis, List<Row> rows) {
    }

    @GetMapping("/api/strategy/selection")
    public SelectionView selection() {
        StrategySelector s = selector.getIfAvailable();
        if (s == null) {
            return new SelectionView(false, 0, List.of());
        }
        List<Row> rows = s.selection().entrySet().stream()
                .map(e -> {
                    StrategySelector.Choice c = e.getValue();
                    return new Row(e.getKey(), c.algo(), c.momentumMedianPnl(), c.momentumTrades(),
                            c.meanReversionMedianPnl(), c.meanReversionTrades());
                })
                .sorted(java.util.Comparator.comparing(Row::instrument))
                .toList();
        return new SelectionView(true, s.lastRunMillis(), rows);
    }
}

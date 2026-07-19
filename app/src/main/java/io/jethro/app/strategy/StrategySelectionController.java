package io.jethro.app.strategy;

import io.jethro.trading.algo.strategy.SelectingStrategy;
import io.jethro.trading.algo.strategy.Strategy;
import io.jethro.trading.algo.strategy.TrendDetector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Exposes per-instrument strategy selection so the call is legible, not a black box: each name's OOS
 * medians (ADR-0043 evidence) and, when the regime-aware path is live (ADR-0044), the price-derived
 * trend regime that actually picks the algo plus its efficiency ratio, and the market breadth. Empty
 * until the first background measurement completes (or when selection is disabled).
 */
@RestController
public final class StrategySelectionController {

    private final ObjectProvider<StrategySelector> selector;
    private final ObjectProvider<Strategy> tradingStrategy;

    public StrategySelectionController(ObjectProvider<StrategySelector> selector,
                                       ObjectProvider<Strategy> tradingStrategy) {
        this.selector = selector;
        this.tradingStrategy = tradingStrategy;
    }

    public record Row(String instrument, String chosen, BigDecimal momentumMedianPnl, int momentumTrades,
                      BigDecimal meanReversionMedianPnl, int meanReversionTrades,
                      /** ADR-0044: live price-derived regime driving the pick (null if regime path off). */
                      String regime, BigDecimal efficiencyRatio) {
    }

    public record SelectionView(boolean available, boolean measuring, String error,
                                long lastRunMillis, boolean regimeAware, String breadth, List<Row> rows) {
    }

    @GetMapping("/api/strategy/selection")
    public SelectionView selection() {
        StrategySelector s = selector.getIfAvailable();
        if (s == null) {
            return new SelectionView(false, false, null, 0, false, null, List.of());
        }
        TrendDetector detector = detector();
        List<Row> rows = s.selection().entrySet().stream()
                .map(e -> {
                    StrategySelector.Choice c = e.getValue();
                    String regime = null;
                    BigDecimal er = null;
                    if (detector != null) {
                        regime = detector.regimeFor(e.getKey()).name();
                        BigDecimal raw = detector.efficiencyRatio(e.getKey());
                        er = raw == null ? null : raw.setScale(3, RoundingMode.HALF_EVEN);
                    }
                    return new Row(e.getKey(), c.algo(), c.momentumMedianPnl(), c.momentumTrades(),
                            c.meanReversionMedianPnl(), c.meanReversionTrades(), regime, er);
                })
                .sorted(java.util.Comparator.comparing(Row::instrument))
                .toList();
        boolean measuring = s.lastRunMillis() == 0 && s.lastError() == null;
        String breadth = detector == null ? null : detector.breadth().name();
        return new SelectionView(true, measuring, s.lastError(), s.lastRunMillis(),
                detector != null, breadth, rows);
    }

    /** The live strategy's trend detector when the ADR-0044 regime-aware path is wired, else null. */
    private TrendDetector detector() {
        Strategy strategy = tradingStrategy.getIfAvailable();
        return strategy instanceof SelectingStrategy ss ? ss.detector() : null;
    }
}

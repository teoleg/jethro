package io.jethro.app.strategy;

import io.jethro.trading.algo.strategy.SelectingStrategy;
import io.jethro.trading.algo.strategy.Strategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The market state the SYSTEM senses from prices — for the landing-page badges. Both are
 * price-derived, identical in sim/live/replay, and never read a sim regime label:
 * <ul>
 *   <li><b>trend</b> — cross-sectional breadth of the ADR-0044 trend detector (TREND / CHOP);</li>
 *   <li><b>regime</b> — the ADR-0051 volatility regime (CALM / ELEVATED) that gates risk-off sizing.</li>
 * </ul>
 */
@RestController
public final class MarketRegimeController {

    private final ObjectProvider<Strategy> tradingStrategy;
    private final ObjectProvider<StrategyLifecycle> lifecycle;

    public MarketRegimeController(ObjectProvider<Strategy> tradingStrategy,
                                  ObjectProvider<StrategyLifecycle> lifecycle) {
        this.tradingStrategy = tradingStrategy;
        this.lifecycle = lifecycle;
    }

    public record MarketState(String trend, String regime, String volRatio) {
    }

    @GetMapping("/api/market/regime")
    public MarketState marketState() {
        String trend = null;
        Strategy s = tradingStrategy.getIfAvailable();
        if (s instanceof SelectingStrategy ss && ss.detector() != null) {
            trend = ss.detector().breadth().name();
        }
        StrategyLifecycle lc = lifecycle.getIfAvailable();
        String regime = lc == null ? null : lc.volatilityRegime();
        String ratio = lc == null ? null : lc.volatilityRatio();
        return new MarketState(trend, regime, ratio);
    }
}

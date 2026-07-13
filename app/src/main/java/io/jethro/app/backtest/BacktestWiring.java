package io.jethro.app.backtest;

import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Backtest engine + service wiring (build step 8). The controller is component-scanned. */
@Configuration
public class BacktestWiring {

    @Bean
    BacktestEngine backtestEngine() {
        return new BacktestEngine();
    }

    @Bean
    BacktestService backtestService(BacktestEngine engine, TradingCoreProperties sim,
                                    StrategyProperties strategy, InstrumentRefSource refs) {
        return new BacktestService(engine, sim, strategy, refs);
    }
}

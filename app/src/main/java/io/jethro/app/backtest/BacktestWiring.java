package io.jethro.app.backtest;

import io.jethro.app.order.ExecutionProperties;
import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Backtest engine + service wiring (build step 8). The controller is component-scanned. */
@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class BacktestWiring {

    @Bean
    BacktestEngine backtestEngine() {
        return new BacktestEngine();
    }

    @Bean
    BacktestService backtestService(BacktestEngine engine, TradingCoreProperties sim,
                                    StrategyProperties strategy, InstrumentRefSource refs,
                                    ExecutionProperties execution) {
        // The backtest charges what live sim execution charges (ADR-0025): EQUITY
        // half-spread + fee per fill, so "backtest-supported" measures the same economics.
        return new BacktestService(engine, sim, strategy, refs, execution.perFillCostBps("EQUITY"));
    }

    /** Walk-forward replay over real daily bars (ADR-0027 point 2); the bars file is
     *  generated on a networked host by scripts/fetch_bars.py. */
    @Bean
    WalkForwardService walkForwardService(StrategyProperties strategy, ExecutionProperties execution,
                                          InstrumentRefSource refs,
                                          @org.springframework.beans.factory.annotation.Value(
                                                  "${jethro.backtest.bars-path:data/historical-bars.json}")
                                          String barsPath) {
        return new WalkForwardService(strategy, execution, refs, barsPath);
    }
}

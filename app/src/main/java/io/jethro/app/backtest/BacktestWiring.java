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
        // The backtest charges what live sim execution charges (ADR-0025), PER NAME (ADR-0085): half
        // that instrument's own refdata spread + its class fee, the same derivation OrderConfig hands
        // the SimulatedExecutor — so "backtest-supported" measures the economics the desk would
        // actually face in that name. The EQUITY blend stays as the fallback for a name refdata and
        // the class config together cannot price.
        return new BacktestService(engine, sim, strategy, refs, execution.perFillCostBps("EQUITY"), execution);
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

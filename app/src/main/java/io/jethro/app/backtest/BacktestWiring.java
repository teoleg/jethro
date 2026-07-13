package io.jethro.app.backtest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Backtest engine wiring (build step 8). The controller is component-scanned. */
@Configuration
public class BacktestWiring {

    @Bean
    BacktestEngine backtestEngine() {
        return new BacktestEngine();
    }
}

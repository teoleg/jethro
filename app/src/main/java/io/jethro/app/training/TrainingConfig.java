package io.jethro.app.training;

import io.jethro.app.trading.TiingoHistoryClient;
import io.jethro.app.trading.TradingCoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * Wires the ADR-0053 training-bars foundation (step 1): a dedicated, rebuildable Tiingo daily-bar
 * store for the learned advisory signal, separate from the runtime {@code daily_close}. DB-backed, so
 * gated on persistence; gated on {@code jethro.training.enabled} (default true). Reuses the Tiingo
 * token from the hedge history config — same free account. Training data only; no model here yet.
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TrainingConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.training", name = "enabled", havingValue = "true", matchIfMissing = true)
    TrainingBarsStore trainingBarsStore(JdbcTemplate jdbc) {
        return new TrainingBarsStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.training", name = "enabled", havingValue = "true", matchIfMissing = true)
    FeatureService featureService(TrainingBarsStore store,
                                  @Value("${jethro.training.label-threshold-bps:10}") double labelThresholdBps) {
        return new FeatureService(store, labelThresholdBps);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.training", name = "enabled", havingValue = "true", matchIfMissing = true)
    LearnedSignalService learnedSignalService(
            FeatureService features,
            @Value("${jethro.training.backtest.folds:5}") int folds,
            @Value("${jethro.training.backtest.embargo-days:2}") int embargoDays,
            @Value("${jethro.training.backtest.cost-bps:10}") double costBps,
            @Value("${jethro.training.backtest.min-train-rows:200}") int minTrainRows,
            @Value("${jethro.training.backtest.iterations:400}") int iterations,
            @Value("${jethro.training.backtest.learning-rate:0.3}") double learningRate,
            @Value("${jethro.training.backtest.l2:0.001}") double l2) {
        var cfg = new WalkForwardBacktest.Config(folds, embargoDays, costBps, minTrainRows, iterations, learningRate, l2);
        return new LearnedSignalService(features, cfg);
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "jethro.training", name = "enabled", havingValue = "true", matchIfMissing = true)
    TrainingBarsLoader trainingBarsLoader(TrainingBarsStore store,
                                          io.jethro.trading.riskpnl.InstrumentRefSource refs,
                                          @Value("${jethro.hedge.tiingo-token:}") String token,
                                          @Value("${jethro.training.years:5}") int years,
                                          @Value("${jethro.hedge.history-request-spacing-millis:800}") long spacingMillis) {
        var client = new TiingoHistoryClient(token, Duration.ofSeconds(15), years);
        var loader = new TrainingBarsLoader(store, client, refs, spacingMillis);
        loader.start(); // background; rebuild-on-empty, no-op when already populated / no token
        return loader;
    }
}

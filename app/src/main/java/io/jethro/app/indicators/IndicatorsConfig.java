package io.jethro.app.indicators;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.app.trading.TradingCoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Market-indicators wiring (ADR-0023). Pure sim runs get the strip from the SIM tape
 * itself (regime + key marks + day-over-day vs the previous session close) — offline by
 * construction; live-provider runs poll real (delayed) index levels from Yahoo. CI/tests
 * set jethro.indicators.enabled=false so nothing extra runs at all.
 */
@Configuration
@EnableConfigurationProperties(IndicatorsProperties.class)
public class IndicatorsConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.indicators", name = "enabled", havingValue = "true", matchIfMissing = true)
    IndicatorsSource indicatorsSource(IndicatorsProperties props, TradingCoreProperties trading,
                                      ObjectProvider<TradingCoreLifecycle> tradingCore,
                                      ObjectProvider<JdbcTemplate> jdbc) {
        if ("sim".equals(trading.providerOrDefault())) {
            return new SimIndicatorsSource(tradingCore.getIfAvailable(), jdbc.getIfAvailable());
        }
        return new IndicatorsService(props); // SmartLifecycle: the context starts/stops the poll
    }

    @Bean
    IndicatorsController indicatorsController(ObjectProvider<IndicatorsSource> source) {
        return new IndicatorsController(source);
    }
}

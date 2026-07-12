package io.jethro.app.trading;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TradingCoreProperties.class)
public class TradingCoreConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.trading", name = "enabled", havingValue = "true", matchIfMissing = true)
    TradingCoreLifecycle tradingCoreLifecycle(TradingCoreProperties properties) {
        return new TradingCoreLifecycle(properties);
    }
}

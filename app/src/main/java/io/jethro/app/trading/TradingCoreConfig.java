package io.jethro.app.trading;

import io.jethro.refdata.RefDataRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TradingCoreProperties.class)
public class TradingCoreConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.trading", name = "enabled", havingValue = "true", matchIfMissing = true)
    TradingCoreLifecycle tradingCoreLifecycle(TradingCoreProperties properties,
                                              ObjectProvider<RefDataRepository> refData) {
        // RefData present only when persistence is on; needed to map yahoo symbols (ADR-0023).
        return new TradingCoreLifecycle(properties, refData.getIfAvailable());
    }
}

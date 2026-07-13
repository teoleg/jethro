package io.jethro.app.indicators;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Market-indicators wiring (ADR-0023). Enabled by default; CI/tests set
 * jethro.indicators.enabled=false (build system property) so nothing polls Yahoo offline.
 */
@Configuration
@EnableConfigurationProperties(IndicatorsProperties.class)
public class IndicatorsConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.indicators", name = "enabled", havingValue = "true", matchIfMissing = true)
    IndicatorsService indicatorsService(IndicatorsProperties props) {
        return new IndicatorsService(props);
    }

    @Bean
    IndicatorsController indicatorsController(ObjectProvider<IndicatorsService> service) {
        return new IndicatorsController(service);
    }
}

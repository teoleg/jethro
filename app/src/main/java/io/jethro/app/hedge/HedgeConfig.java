package io.jethro.app.hedge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/** Hedge advisor wiring (ADR-0038/0039). All dials are config; the advisor is firm-level v1. */
@Configuration
public class HedgeConfig {

    @Bean
    HedgeAdvisor hedgeAdvisor(
            @Value("${jethro.hedge.mode:ADVISE}") String mode,
            @Value("${jethro.hedge.equity-cap-usd:250000}") BigDecimal equityCapUsd,
            @Value("${jethro.hedge.effectiveness-floor:0.25}") double effectivenessFloor,
            @Value("${jethro.hedge.equity-proxy:ES}") String equityProxy,
            @Value("${jethro.hedge.equity-proxy-multiplier:50}") BigDecimal equityProxyMultiplier) {
        return new HedgeAdvisor(HedgeAdvisor.Mode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT)),
                equityCapUsd, effectivenessFloor, equityProxy, equityProxyMultiplier);
    }
}

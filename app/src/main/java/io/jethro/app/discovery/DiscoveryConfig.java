package io.jethro.app.discovery;

import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires universe discovery (ADR-0045/0050 §7). Gated on {@code jethro.discovery.enabled} (default
 * true). The {@link UniverseCandidates} register is a bean so BOTH the news lifecycle here and the
 * social lifecycle feed it. Real RSS only; suggestions only — never adds an instrument or trades.
 */
@Configuration
@EnableConfigurationProperties(DiscoveryProperties.class)
@ConditionalOnProperty(prefix = "jethro.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DiscoveryConfig {

    @Bean
    UniverseCandidates universeCandidates(DiscoveryProperties props) {
        return new UniverseCandidates(props.maxCandidatesOrDefault());
    }

    @Bean
    RssNewsFeed rssNewsFeed(DiscoveryProperties props) {
        return new RssNewsFeed(props.outletsOrEmpty(), Duration.ofSeconds(8));
    }

    @Bean
    CompanyDirectory companyDirectory() {
        return CompanyDirectory.fromClasspath("discovery/company-tickers.csv");
    }

    @Bean
    DiscoveryLifecycle discoveryLifecycle(RssNewsFeed news, UniverseCandidates candidates,
                                          InstrumentRefSource refs, DiscoveryProperties props,
                                          CompanyDirectory companies) {
        return new DiscoveryLifecycle(news, candidates, refs, props, companies);
    }
}

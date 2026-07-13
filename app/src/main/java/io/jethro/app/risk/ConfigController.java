package io.jethro.app.risk;

import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the running configuration (ADR-0017 evidence surface): risk limits,
 * strategy parameters, and the simulation setup — the numbers that were previously
 * visible only in application.properties. No secrets here (datasource/broker creds are
 * deliberately not exposed). Editing limits at runtime is a separate, audited feature.
 */
@RestController
public final class ConfigController {

    public record ConfigDto(RiskLimitProperties risk, StrategyProperties strategy,
                            TradingCoreProperties sim) {
    }

    private final RiskLimitProperties risk;
    private final StrategyProperties strategy;
    private final TradingCoreProperties sim;

    public ConfigController(RiskLimitProperties risk, StrategyProperties strategy,
                            TradingCoreProperties sim) {
        this.risk = risk;
        this.strategy = strategy;
        this.sim = sim;
    }

    @GetMapping("/api/config")
    public ConfigDto config() {
        return new ConfigDto(risk, strategy, sim);
    }
}

package io.jethro.app.risk;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The instrument's measured correlation with the current portfolio (ρ_ip) — the
 * covariance-aware sizing input. Empty when unmeasured (warm-up, uncovered instrument,
 * empty book) — callers fall back to standalone vol-targeting, never a guessed ρ.
 */
public interface PortfolioCorrelationSource {

    Optional<BigDecimal> correlationToPortfolio(String instrumentId);

    PortfolioCorrelationSource NONE = instrumentId -> Optional.empty();
}

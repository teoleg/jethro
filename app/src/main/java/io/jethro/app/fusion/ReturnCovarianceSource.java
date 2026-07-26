package io.jethro.app.fusion;

import java.util.OptionalDouble;

/**
 * Measured covariance of two instruments' DAILY RETURNS (ADR-0079) — the input the fusion layer needs
 * to know how much of the target book's risk is one bet repeated.
 *
 * <p>Deliberately narrow: one measured statistic, never a guessed one. Empty means the pair is not
 * covered by the estimate (warm-up, an instrument with no admissible daily-close history in this feed
 * mode, a name outside the strict-coverage intersection) — and the caller must then make NO claim about
 * that name's correlation, exactly as {@code PortfolioCorrelationSource} already requires of the sizing
 * path. Inventing a ρ to fill the gap would be a risk number without provenance (ADR-0016 / invariant 7).
 *
 * <p>The unit is a variance of fractional daily returns (so {@code covariance(x, x)} is σ²_daily); the
 * quantity is a statistic, not money, which is why it is a {@code double} and not a {@code BigDecimal}
 * — the same boundary {@code CovMath} draws. Money stays exact on the other side of it.
 */
public interface ReturnCovarianceSource {

    /** Σ(a,b) over daily returns; empty when either name is uncovered. */
    OptionalDouble covariance(String a, String b);

    /** Nothing measured — every caller falls back to leaving the book untouched, disclosed. */
    ReturnCovarianceSource NONE = (a, b) -> OptionalDouble.empty();
}

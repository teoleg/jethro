package io.jethro.app.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Risk-limit configuration (jethro.risk). Default caps apply to every book; per-book
 * entries under {@code books.<BOOK>} override any subset. A null/absent value means no
 * limit for that metric.
 */
@ConfigurationProperties(prefix = "jethro.risk")
public record RiskLimitProperties(
        BigDecimal maxGrossExposure,
        BigDecimal maxNetExposure,
        BigDecimal maxLossPnl,
        BigDecimal warnRatio,
        Map<String, BookLimits> books) {

    public record BookLimits(BigDecimal maxGrossExposure, BigDecimal maxNetExposure, BigDecimal maxLossPnl) {
    }

    /** Warn ratio, defaulting to 80% when unset. */
    public BigDecimal warnRatioOrDefault() {
        return warnRatio != null ? warnRatio : new BigDecimal("0.80");
    }
}

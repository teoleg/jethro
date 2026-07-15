package io.jethro.app.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Risk-limit configuration (jethro.risk). Default caps apply to every book; per-book
 * entries under {@code books.<BOOK>} override any subset; {@code firm.*} caps the
 * aggregate across all books. A null/absent value means no limit for that metric.
 */
@ConfigurationProperties(prefix = "jethro.risk")
public record RiskLimitProperties(
        BigDecimal maxGrossExposure,
        BigDecimal maxNetExposure,
        BigDecimal maxLossPnl,
        BigDecimal maxInstrumentExposure,
        BigDecimal warnRatio,
        /** Firm max drawdown from peak total P&L (ADR-0027): at/over this, the breaker halts
         *  ALL auto-execution (strategy + AI autonomy; manual orders still allowed) until an
         *  operator resets. Null/absent = breaker disabled. */
        BigDecimal maxFirmDrawdown,
        Map<String, BookLimits> books,
        BookLimits firm) {

    public record BookLimits(BigDecimal maxGrossExposure, BigDecimal maxNetExposure,
                             BigDecimal maxLossPnl, BigDecimal maxInstrumentExposure) {
    }

    /** Warn ratio, defaulting to 80% when unset. */
    public BigDecimal warnRatioOrDefault() {
        return warnRatio != null ? warnRatio : new BigDecimal("0.80");
    }
}

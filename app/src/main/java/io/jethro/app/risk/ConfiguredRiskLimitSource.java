package io.jethro.app.risk;

import io.jethro.trading.riskpnl.RiskLimits;
import io.jethro.trading.riskpnl.RiskLimitSource;

import java.math.BigDecimal;

/** Binds {@link RiskLimitSource} to configured limits: per-book override over defaults. */
public final class ConfiguredRiskLimitSource implements RiskLimitSource {

    private final RiskLimitProperties props;

    public ConfiguredRiskLimitSource(RiskLimitProperties props) {
        this.props = props;
    }

    @Override
    public RiskLimits limitsFor(String bookId) {
        RiskLimitProperties.BookLimits override = props.books() == null ? null : props.books().get(bookId);
        if (override == null) {
            return new RiskLimits(props.maxGrossExposure(), props.maxNetExposure(), props.maxLossPnl());
        }
        return new RiskLimits(
                pick(override.maxGrossExposure(), props.maxGrossExposure()),
                pick(override.maxNetExposure(), props.maxNetExposure()),
                pick(override.maxLossPnl(), props.maxLossPnl()));
    }

    private static BigDecimal pick(BigDecimal override, BigDecimal fallback) {
        return override != null ? override : fallback;
    }
}

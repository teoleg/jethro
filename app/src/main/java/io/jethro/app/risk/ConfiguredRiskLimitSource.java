package io.jethro.app.risk;

import io.jethro.trading.riskpnl.RiskLimits;
import io.jethro.trading.riskpnl.RiskLimitSource;

import java.math.BigDecimal;

/** Binds {@link RiskLimitSource} to configured limits: per-book override over defaults, plus firm caps. */
public final class ConfiguredRiskLimitSource implements RiskLimitSource {

    private final RiskLimitProperties props;

    public ConfiguredRiskLimitSource(RiskLimitProperties props) {
        this.props = props;
    }

    @Override
    public RiskLimits limitsFor(String bookId) {
        RiskLimitProperties.BookLimits override = props.books() == null ? null : props.books().get(bookId);
        if (override == null) {
            return new RiskLimits(props.maxGrossExposure(), props.maxNetExposure(),
                    props.maxLossPnl(), props.maxInstrumentExposure());
        }
        return new RiskLimits(
                pick(override.maxGrossExposure(), props.maxGrossExposure()),
                pick(override.maxNetExposure(), props.maxNetExposure()),
                pick(override.maxLossPnl(), props.maxLossPnl()),
                pick(override.maxInstrumentExposure(), props.maxInstrumentExposure()));
    }

    @Override
    public RiskLimits firmLimits() {
        RiskLimitProperties.BookLimits firm = props.firm();
        if (firm == null) {
            return RiskLimits.none();
        }
        return new RiskLimits(firm.maxGrossExposure(), firm.maxNetExposure(),
                firm.maxLossPnl(), firm.maxInstrumentExposure());
    }

    private static BigDecimal pick(BigDecimal override, BigDecimal fallback) {
        return override != null ? override : fallback;
    }
}

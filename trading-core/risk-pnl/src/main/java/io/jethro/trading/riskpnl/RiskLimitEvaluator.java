package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic risk-limit evaluation (invariant 7: guardrails are code, never a model).
 * Compares each book's exposures and PnL — plus per-instrument concentration and the
 * firm-wide aggregate — against {@link RiskLimits}, emitting a {@link LimitBreach} at
 * WARN (>= warn ratio of the limit) or ALERT (>= the limit). All comparisons are exact
 * BigDecimal — no float on money, even for the threshold (invariant 1).
 */
public final class RiskLimitEvaluator {

    /** Book id used for firm-aggregate breaches. */
    public static final String FIRM = "FIRM";

    private final BigDecimal warnRatio;

    /** @param warnRatio fraction of a limit at which a WARN fires (e.g. 0.80). */
    public RiskLimitEvaluator(BigDecimal warnRatio) {
        this.warnRatio = warnRatio;
    }

    public List<LimitBreach> evaluate(ConsolidatedRisk risk, RiskLimitSource limits) {
        List<LimitBreach> breaches = new ArrayList<>();
        for (ConsolidatedRisk.Group book : risk.byBook()) {
            RiskLimits lim = limits.limitsFor(book.key());
            check(breaches, book.key(), LimitBreach.Metric.GROSS_EXPOSURE,
                    book.grossExposure(), lim.maxGrossExposure(), "");
            check(breaches, book.key(), LimitBreach.Metric.NET_EXPOSURE,
                    book.netExposure().abs(), lim.maxNetExposure(), "");
            check(breaches, book.key(), LimitBreach.Metric.LOSS,
                    loss(book.comprehensivePnl()), lim.maxLossPnl(), ""); // actual money incl. FX (ADR-0037)
        }
        // Per-instrument concentration within each book.
        for (PositionRisk p : risk.positions()) {
            RiskLimits lim = limits.limitsFor(p.bookId());
            check(breaches, p.bookId(), LimitBreach.Metric.INSTRUMENT_EXPOSURE,
                    p.grossExposure(), lim.maxInstrumentExposure(), p.instrumentId());
        }
        // Firm aggregate across every book.
        RiskLimits firm = limits.firmLimits();
        check(breaches, FIRM, LimitBreach.Metric.GROSS_EXPOSURE,
                risk.total().grossExposure(), firm.maxGrossExposure(), "");
        check(breaches, FIRM, LimitBreach.Metric.NET_EXPOSURE,
                risk.total().netExposure().abs(), firm.maxNetExposure(), "");
        check(breaches, FIRM, LimitBreach.Metric.LOSS,
                loss(risk.total().comprehensivePnl()), firm.maxLossPnl(), ""); // actual money incl. FX (ADR-0037)
        return breaches;
    }

    private static BigDecimal loss(BigDecimal totalPnl) {
        return totalPnl.signum() < 0 ? totalPnl.negate() : BigDecimal.ZERO;
    }

    private void check(List<LimitBreach> out, String bookId, LimitBreach.Metric metric,
                       BigDecimal actual, BigDecimal limit, String subject) {
        if (!RiskLimits.isSet(limit)) {
            return;
        }
        if (actual.compareTo(limit) >= 0) {
            out.add(new LimitBreach(bookId, metric, LimitBreach.Severity.ALERT, actual, limit, subject));
        } else if (actual.compareTo(limit.multiply(warnRatio)) >= 0) {
            out.add(new LimitBreach(bookId, metric, LimitBreach.Severity.WARN, actual, limit, subject));
        }
    }
}

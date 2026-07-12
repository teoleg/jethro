package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic risk-limit evaluation (invariant 7: guardrails are code, never a model).
 * Compares each book's exposures and PnL in a {@link ConsolidatedRisk} snapshot against
 * its {@link RiskLimits} and emits a {@link LimitBreach} at WARN (>= warn ratio of the
 * limit) or ALERT (>= the limit). All comparisons are exact BigDecimal — no float on
 * money, even for the threshold (invariant 1).
 */
public final class RiskLimitEvaluator {

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
                    book.grossExposure(), lim.maxGrossExposure());
            check(breaches, book.key(), LimitBreach.Metric.NET_EXPOSURE,
                    book.netExposure().abs(), lim.maxNetExposure());
            BigDecimal loss = book.totalPnl().signum() < 0 ? book.totalPnl().negate() : BigDecimal.ZERO;
            check(breaches, book.key(), LimitBreach.Metric.LOSS, loss, lim.maxLossPnl());
        }
        return breaches;
    }

    private void check(List<LimitBreach> out, String bookId, LimitBreach.Metric metric,
                       BigDecimal actual, BigDecimal limit) {
        if (!RiskLimits.isSet(limit)) {
            return;
        }
        if (actual.compareTo(limit) >= 0) {
            out.add(new LimitBreach(bookId, metric, LimitBreach.Severity.ALERT, actual, limit));
        } else if (actual.compareTo(limit.multiply(warnRatio)) >= 0) {
            out.add(new LimitBreach(bookId, metric, LimitBreach.Severity.WARN, actual, limit));
        }
    }
}

package io.jethro.trading.riskpnl;

/** Port supplying the risk limits for a book. Wired from config in the app assembly. */
public interface RiskLimitSource {

    /** Limits for a book; {@link RiskLimits#none()} when the book has no limits set. */
    RiskLimits limitsFor(String bookId);
}

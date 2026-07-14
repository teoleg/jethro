package io.jethro.app.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Vol-targeted order notional — the ONE formula both the momentum strategy and the AI
 * hypothesis sleeve size with, so cross-engine risk per position is comparable:
 *
 * <pre>  notional = riskBudgetDaily / σ_daily,  capped at the per-class order cap</pre>
 *
 * Worked: budget $250/day — AAPL at σ=1.8%/day → 250/0.018 = $13,888.88; ZN at
 * σ=0.40%/day → $62,500 (a Treasury future NEEDS more notional to carry the same daily
 * risk); a meme-vol name at 6%/day → $4,166.66. The cap keeps a near-zero vol estimate
 * from exploding the order (a 0.01%/day instrument would otherwise ask for $2.5M).
 */
public final class VolTargeting {

    private VolTargeting() {
    }

    /** @return the capped vol-targeted notional; callers divide by price × multiplier. */
    public static BigDecimal notionalFor(BigDecimal riskBudgetDaily, BigDecimal dailyVol, BigDecimal cap) {
        BigDecimal notional = riskBudgetDaily.divide(dailyVol, 2, RoundingMode.DOWN);
        return cap != null && notional.compareTo(cap) > 0 ? cap : notional;
    }
}

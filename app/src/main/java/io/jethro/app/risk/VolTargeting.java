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

    /** ρ never enters sizing below this floor: a low measured correlation quadruples size at
     *  most (4× at 0.25), and negative ρ (a hedge) gets the same conservative floor — we do
     *  not super-size hedges on a correlation estimate. */
    static final BigDecimal RHO_FLOOR = new BigDecimal("0.25");

    /**
     * Marginal-risk sizing (covariance-aware): size to the position's CONTRIBUTION to
     * portfolio vol, not its standalone vol —
     *
     * <pre>  notional = riskBudgetDaily / (σ_daily × clamp(ρ_ip, 0.25, 1))</pre>
     *
     * ρ_ip = correlation of the instrument with the current portfolio. A name that just
     * duplicates the book (ρ→1) sizes like standalone; a genuine diversifier (low ρ) earns
     * up to 4× (the ρ floor), still bounded by the per-class cap. Worked: budget $250,
     * σ=2%/day, ρ=0.5 → 250/(0.02×0.5) = $25,000 — twice the standalone $12,500, because
     * only half its vol adds to the book.
     */
    public static BigDecimal marginalNotionalFor(BigDecimal riskBudgetDaily, BigDecimal dailyVol,
                                                 BigDecimal rho, BigDecimal cap) {
        BigDecimal clamped = rho.max(RHO_FLOOR).min(BigDecimal.ONE);
        BigDecimal notional = riskBudgetDaily.divide(dailyVol.multiply(clamped), 2, RoundingMode.DOWN);
        return cap != null && notional.compareTo(cap) > 0 ? cap : notional;
    }
}

Nothing this desk measures predicts returns, so I stopped tuning the machinery and built a new predictor: cross-sectional residual reversion (ADR-0121), which fades a name against its peer group instead of against its own past.

*Every figure below is read from the live endpoints, `reports/run-status.json`, the ledger or this run's
report; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number that gates money).
The t-statistics, p-values and params quoted are the edge gate's own published output.*

## 1–4. The live situation, in plain numbers

**Money.** Total PnL `$0.61209817` — unchanged for a fifth consecutive run. Since last run `+0.00`;
over the last three runs `+0.00`. Not bleeding, but flat and off target: `pnl_growth_pct` `0.0%`
against `pnl_target_pct` `1.0%`, `on_track=False`, `stale=True`, `underwater=False`.

**Risk.** Gross exposure `$0.00`, net `$0.00`. VaR `0.00` with `note: "no positions"`. Breaker clear
(`halted: false`). Nothing at risk, so nothing to de-risk — the danger state does not apply.

**Cause.** The pending change `9be1633c2` (ADR-0120, a cohort is one sweep) completed its evaluation
window and scored `⚠️ INCONCLUSIVE` — `risk-adj return/cycle +0.000000 over 7 cycles, t=+0.00`. That
is the correct verdict and not a failure of the change: `orders_day.total: 7` with the newest order
now hours old, so **zero orders** were placed across the whole window. The move decomposes exactly:
`+0.00` from market and `+0.00` from code. Nothing to attribute, in either direction.

**Danger.** None. Not bleeding, exposure not rising, breaker clear, feed live
(`lastUpdateAgeMillis: 10`, provider `alpaca`, 35 instruments, `ticksDropped: 0`).

## 5–6. Post-mortem and memory

No orders this window, so there is no trigger to blame or strengthen. The memory (Rule 56, last
cycle) already retired the queued edge-gate admission fix — I re-checked that it stays retired, and it
does. The compounding lesson from the last five cycles is unambiguous: the ledger's INCONCLUSIVE wall
is not noise about good changes, it is what happens when every change targets the *combiner*, the
*gate* or the *sensor mechanics* while the *measurements themselves* are null.

## 7. What I checked on the edge mission, and what I did about it

I read every source × horizon row before deciding. The gate reads `mayIncrease: false`; against a
`roundTripCostBps` of `0.6278285714285714`, no source clears at any rung — the best `tStat` anywhere
is `0.6564771217276463` (`social`@3600 s on 5 cohorts). I also computed the other two rungs' t-stats
from the published cohort dispersions rather than assuming: `reversion`@900 s ≈ `0.27` on 29 cohorts,
`reversion`@225 s ≈ `0.25` on 77. So the "horizon ladder" lever I flagged last cycle is **dead** —
moving the gate's measurement rung would not open it either, and I am recording that so it is not
re-attempted.

But the null is not uniform, and its shape is informative. `reversion` is the only source
positive-signed at *every* rung (`0.6500729504188325` bps at 225 s over 77 cohorts,
`1.2532288851764135` at 900 s over 29, `6.940456315814394` at 3600 s over 8), while `trend` measures
negative on both its best-sampled rungs (`-0.21794567180606306` at 225 s over 87,
`-8.299129091035356` at 3600 s over 10). That is a weak positive structure with real sample behind it
and no way to sharpen it by re-weighting.

The reversal literature says precisely why a raw own-price reversal signal measures like that: the
documented effect lives in the **idiosyncratic** component. Fading a name that is down *because the
whole cross-section is down* is a bet on the market factor — roughly zero expectancy over an hour,
plus the reversal trade's full turnover cost — and pooling it with the residual bet dilutes the one
that works. ADR-0019 hedges net equity toward flat anyway, so that factor component is exposure the
desk deliberately does not keep, currently being measured as if it were alpha.

## The change

A fifth forecast source, `xsreversion`, on the identical ADR-0066/0070 contract: it publishes a
conviction, records every reading in the phase-1 telemetry, and must earn its own measured expectancy
through the edge gate before it sizes anything. Per sweep, in the feed's own clock: a name is admitted
only if it printed in **both halves** of the lookback window (dial-free — so a name that stopped
printing cannot pass off a stale partial return as current); its move is `ln(P_last/P_first)/√span`,
vol-time normalised because print rates here span seconds to tens of minutes; peers are its **asset
class from the instrument master**; the score is `−clamp((r − median)/(1.4826·MAD), ±4)` within a
group of at least four. Median/MAD because peer groups are single-digit and one bad print would
otherwise flip everyone else's sign. It emits the whole cross-section at once — exactly one ADR-0120
cohort — so the gate counts it correctly with no special case. No money/risk/exposure number is
introduced; the three shape dials carry provenance in `application.properties`.

**Why this is safe while it measures.** The gate is reduce-only and this source cannot change that; it
can only alter how a held position is worked down, and the book is flat. The cost of being wrong is
measurement time, not money.

**The honest cost, stated up front.** The gate's Bonferroni correction divides α by *rungs* only, not
by sources, so a fifth source makes the un-corrected source multiplicity worse — a passing
`xsreversion` reading deserves more scepticism than the gate will express. Fixing that is the queued
follow-up, not this change.

**What would falsify it.** If `xsreversion` also measures null once it has real cohorts, that is
strong evidence this universe has no short-horizon reversal to capture at all — and the right next
move is a different feed, not a sixth source. I will say so plainly rather than adding one.

Re-measured must-fix #1 with code and it is dead: the "one-sided execution costs −11.6 bps" figure was an n=65 artifact — at n=186 the cohort is +1.68 bps, statistically zero — so I closed it instead of spending the next change superseding ADR-0084, and the real #1 is what it has always been: no source has edge.

*(Every figure below is read live from `/api/risk`, `/api/fusion/targets`, `/api/attribution`,
`/api/signals/telemetry`, or the tables in `logs/report.md`, or computed by the FIFO reconstruction
described below. None is authored here — invariant 7 / ADR-0016. The PnL verdict stays the scorer's.)*

## Situation

Total PnL **−$68.86**, gross **$20,547.61**, net **$379.75**. Since last run PnL **−31.16**, gross
**+2,530.03**; over the last 3 runs PnL **−46.50**, gross **−7,785.02**. Gross is **1.4%** of the
$1,500,000 firm cap (**$1,479,452** headroom), net **0.0%** of the $1,000,000 net cap. Breaker
`halted: false`; regime CALM, trend CHOP, volRatio 1.33. The flag is **UNDERWATER** only — this is **not**
the DANGER state, because the book is nowhere near a cap or the breaker. Nothing here calls for de-risking.

**No code change this cycle.** `reports/.pending-baseline.json` is present and the scorer prints
`3e7817e4f still accumulating evidence (3/6 cycles)`. Piling a change on top would destroy the evidence.

## Step 0 — verdict 1: ADR-0124 → ✅ VERIFIED, third consecutive cycle

Deployment proven behaviourally, not from `git log`: `uptimeSeconds` **1430** at a report stamped
**18:00:01.608Z** puts the JVM start at **17:36:11Z**, and `/api/fusion/targets` reads TSLA / META / GOOGL
`sources=1 → agreement 0.000, fc 0.000, targetQty 0.00` — a value only the ADR-0124 code produces. The
book's largest conviction is corroborated: NVDA `sources=3, agreement 0.798, fc −5.544`.

## Step 0 — verdict 2: must-fix #1 → ✅ VERIFIED *as falsified*, and closed

Its VERIFY-BY was *"aggregate round-trip bps moves toward zero from −11.6, on a count materially above
65."* I re-measured it with code — FIFO round-trip reconstruction over all **276** LIVE fills joined to
`orders.order_type`, in exact `Decimal`, with each instrument's `contract_multiplier` applied. The
reconstruction **reconciles to live**: it returns MACRO **−$35.82** against `/api/risk`
`MACRO.realizedPnl` **−35.82347655**.

| cohort | round-trips | closed notional | net PnL | net bps |
|---|---|---|---|---|
| LIMIT-in → MARKET-out | **186** (was 65) | $104,394.11 | **+$17.49** | **+1.68** (was **−11.6**) |
| ALPHA (all equities) | 189 | $106,291.89 | +$20.30 | +1.91 |
| HEDGE (ES) | 5 | $8,521.60 | −$9.31 | −10.92 |
| MACRO (NQ) | 2 | $4,263.64 | −$35.82 | −84.02 |

Clustered by instrument (9 clusters), ALPHA's round-trip net bps is **+1.220 mean, t = +0.13** —
indistinguishable from zero. The one-sidedness is structurally real (**186 of 196** round-trips are
LIMIT-in → MARKET-out) but it is **not costing money**; the **−11.6 bps was an n=65 artifact**. I closed
the item rather than spend the desk's next change writing an ADR to supersede ADR-0084 against a cost that
is not there.

## What that leaves as #1 — the standing priority, now with direct proof

The desk turns over **$106,291.89** of closed equity notional across **189** round-trips to earn
**+$20.30**. Execution is essentially free and the result is essentially zero, because the signal being
executed has no measured edge. **Reversion is the one live candidate and the only source positive at every
horizon**, with expectancy scaling in horizon the way a real signal does and noise does not: **225 s
+0.144 bps (266 cohorts, t +0.40) → 900 s +2.300 bps (101 cohorts, t +1.38) → 3600 s +9.066 bps (29
cohorts, t +1.26)**. Every other source is negative at the long horizons — trend **−8.634 / t −1.25**,
xsreversion **−8.887 / t −0.87**, momentum **−13.257 / t −1.45**, social **−2.111 / t −0.20**. It needs
**cohorts, not tuning**.

## Attribution this window — honest split

ADR-0124 silences TSLA, META and GOOGL; all three sat at `targetQty 0.00` and did not trade. Every name
that did trade (GOOG, AAPL, AMZN, NVDA, JNJ, JPM, MSFT, ES) is a multi-source name the change does not
touch. So **none** of the **−$31.16** is attributable to ADR-0124. Decomposed by book, the firm's realized
loss is **entirely** two NQ round-trips (MACRO **−$35.82**, closed and flat) against ALPHA equities at
**+$20.30**; the rest is **−$31.54** of unrealized mark on 9 open equity shorts — market, not change. The
gross rise is the hedge: EQUITY gross **$11,433.45** at net **−$8,734.41** against a **$9,114.16** long ES
leg, firm net **$379.75**.

## Checked and deliberately not promoted

- **NQ is 100% of the realized loss.** Hypothesis *"fusion sizes futures without the contract multiplier,
  so NQ is 20× oversized"* → **falsified**: `TargetPlanner` sizes on `price × contractMultiplier`. n=2, so
  log, don't chase.
- **GOOG `targetQty −73.22` vs `currentQty +4.00` at `deltaQty −0.010`** is not a freeze — ADR-0102
  `withinTarget` clamps the aim to flat because the holding opposes the current target, and
  `bufferedDelta` then trades to the near edge of the band. Working as designed.
- **`fusion re-plan` churn is unchanged** at **9 of 20** post-restart orders, with GOOG the third named
  ramp after ORCL and AAPL. Loud, but it lives in the cohort that just measured **+1.68 bps**, so it is
  not where the money goes.

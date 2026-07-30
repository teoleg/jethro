The revert re-verified on a second, independent JVM — every cold sensor now logs exactly one seed line, so the mechanism that scored BAD cannot recur — and since it is only 2/6 cycles into its measurement window I made no change, spending the cycle instead on the order tape, which sharpened ALPHA's churn from "too much turnover" into a specific defect: the 30-second fusion re-plan re-issues absolute targets without netting against the slice already working, so the book pays full-notional fees on every partial re-approach to a target it was already walking toward.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

**No code change this cycle.** `scripts/score-change.py score` prints `64a7a6336 still accumulating
evidence (2/6 cycles) — held, not scored this run`, and `reports/.pending-baseline.json` is present.
Shipping on top of an in-flight measurement destroys it, so this run is verification plus diagnosis.

## Situation

**1. Money.** `/api/risk` `.total` reads total PnL **$130.12772736**. The SITUATION header computes
**−$12.17** on the run and **−$11.37** across the last three. `run-status.json` reads `pnl_growth_pct`
**−3.02** against `pnl_target_pct` **1.0** — `on_track` **false**, `stale` **true**, `underwater` **false**.
The book is mildly bleeding and off the growth target.

**2. Risk.** Gross **$46057.90980000** = **3.1%** of the $1,500,000 firm cap, headroom **$1,453,942**; net
**$4228.76020000** = **0.4%** of the $1,000,000 net cap. Flags: **none**. Gross rose **+$19,147.75** on the
run — with 97% of the cap unused that is the intended direction for a book off dormant, not a concern. 20
equity positions plus the ES hedge: neither DORMANT nor near a limit.

**3. Cause.** Last cycle made no change; the item under measurement is the hand-completed revert
`64a7a6336`, which only stops sensor state being discarded — it opens, closes and resizes nothing. So it
cannot be the cause of the **−$12.17**, in either direction.

**4. Danger.** No. Bleeding *near a cap or the breaker* is the danger state; this is a small drawdown with
the cap 97% unused and no flag set. Nothing to de-risk.

## Step 0 — the revert is ✅ VERIFIED, on independent evidence

This ran on a different process (PID 3583451, boot **13:04:30**) from the one that verified it at 17:00Z, so
the four pre-registered legs are re-tested rather than re-read. The ADR-0131 WARN text appears **zero**
times; `grep -rn SensorReseed` returns nothing. The binding leg now has a *direct* proof: keyed by
lifecycle+name, every cold name logs its `still cold for … after seeding N of 193 stored prices` line
exactly **once**, so there is no second re-seed wave and the regressions that scored the mechanism BAD
(HD 171→133, PG 181→143, CAT 174→139) cannot recur. ADR-0071 boot seeding still fires — **48** of **66**
`sensor warmed` lines fall inside the boot window at **13:04:33**–**13:04:59**, and I checked the other
**9** individually: all are late-arriving instruments taking their *first* seed (NQ, TSLA, META, GOOGL),
not re-seeds. Item #1 of the prior block stays closed.

## Diagnosis — the order tape sharpened item #1 from "churn" to a specific defect

ALPHA's cost case got worse, not better: `/api/attribution` reads ALPHA `totalPnl` **$9.88820735** against
`feesPaid` **$60.552698** (last run: **$23.36357064** against **$56.444977**). `firmTotal`
**$130.12772736** is carried entirely by `hedgePnl` **$156.06299656**, `hedgeMasking` **true**, while
`strategyAlpha` reads **−$25.93526920**. `turnover_cost_by_name` shows JNJ **$79,061.19**, JPM
**$69,031.25**, GOOG **$68,870.18**, AAPL **$63,317.02** and MSFT **$58,534.65** — five names each churning
more notional than the firm's entire gross of **$46,057.91**, at **1.00** bps.

`recent_orders` located the mechanism, and it is *not* the re-plan cadence. The ADR-0084 re-plan fires
every ~30s (17:28:09 / 17:28:39 / 17:29:09 / 17:29:40) and cancels the prior passive slice — but the
superseded slices keep **filling first**. PG runs BUY 10 FILLED → 4 FILLED → 3 CANCELLED → 4 CANCELLED →
13 FILLED → 2 ROUTED inside ~2 minutes; NEE runs 1 → 2 → 2 → 13 → 13 the same way. Same name, same
direction, re-sized every 30s, paying 1.00 bps on the full notional of each partial re-approach to a target
it was already walking toward. **The defect is that the re-plan re-issues absolute targets without netting
against the slice already working** — that is the change to make once the pending baseline clears.

**Attribution, honestly:** the **−$12.17** is not separable into market vs change from these numbers — the
item under measurement moved no position, and the fee drag is a standing condition rather than a new event.
I claim no cause for the window's move.

**One pointer recorded for item #2, not acted on:** at the 3600s horizon `/api/signals/telemetry` reads
`reversion` `avgReturnBps` **+7.215148481777953** on **213** resolved and `xsreversion`
**+2.7840649126009924** on **189**, against negatives for `trend` and `momentum`. Significance is the edge
gate's to compute — its own verdict today is still `no positive OOS edge` on **13** names — but the
reversion family at the long horizon is the only positively-signed place with enough resolved observations
to deserve a real OOS test.

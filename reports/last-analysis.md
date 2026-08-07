# Last analysis — 2026-08-07 18:30Z

**No change (`3c43242ba` is at 4/6 cycles in its evaluation window) — and this cycle caught my own
measuring stick lying: with zero code deployed, the churn ratio I specified last cycle as the proof metric
for the desk's cost problem fell from 4.4× to 1.17×. I would have credited a change for that. The metric is
withdrawn and re-specified onto the scorer's own 6-cycle horizon before anything ships against it.**

## Situation (live, read from this run's report — never authored)

1. **Money.** Total PnL **$-1,025.94**. Down **$1.07** on the window, **$22.22** across the last three runs.
   Bleeding, but slowly and at a decelerating rate (the prior window was -$8.17). UNDERWATER, off the
   +1%/3-iteration target (`pnl_growth_pct` **-8.41%**, `on_track=False`, `stale=True`).
2. **Risk.** Gross **$27,161.97** = **1.8%** of the $1,500,000 firm cap, headroom **$1,472,838**; net
   **$-8,793.46** = **0.9%** of the $1,000,000 net cap. Gross **more than doubled**, up **$16,259.98** on the
   window. No `NEAR FIRM CAP` flag, no breaker. Under ADR-0132 this is the right direction, not a danger —
   the book coming off dormant into a budget that is 98% unused is the goal, and I will not de-risk it.
3. **Cause.** No change was deployed this cycle or last. `3c43242ba` (the hand-completed revert of ADR-0144)
   is still under measurement — `scripts/score-change.py score` prints *"still accumulating evidence
   (4/6 cycles)"* and `reports/.pending-baseline.json` is present — so the contract freezes new code.
4. **Danger.** None. Bleeding, yes; near the cap or the breaker, no. The live danger state does not apply.

## Step 0 — verification: `3c43242ba` ✅ VERIFIED, third consecutive window

`ops_jvm.uptimeSeconds` **6852** at a report stamp of **18:30:02Z** puts JVM start at **16:35:50Z** — the
same process as the last two cycles (5052s, then 3251s), so no restart has intervened and nothing here is
boot transient. `fusion exit — target decayed to flat` fired **three times** this window: `BAC SELL 3`
(18:27:02), `PG BUY 2` (18:09:48) and `KO SELL 13` (17:59:09), the last **114 minutes** into the process.
The exit leg is live, warm and repeating. Closed.

## What this cycle actually bought: a falsified VERIFY-BY

Last cycle I specified item #1's proof metric as the ALPHA **churn ratio** — gross notional traded ÷ net
notional moved — reading **4.4×**, with **KO trading 184 shares for exactly zero net position**. I called it
drift-proof. A script re-ran it on this window, against a **running commit that did not change**:

| computed from `recent_orders` + `marks` | 18:00Z | 18:30Z |
|---|---|---|
| gross notional traded | $60,988 | $45,649 |
| \|net notional moved\| | $13,846 | $39,130 |
| **churn ratio** | **4.4×** | **1.17×** |
| zero-net round-trip names | 1 of 8 | **0 of 10** |

Same defect, same code, ratio down 73%. The failure is structural, not luck: over a 30-minute slice the
denominator is just whatever the forecast left on the book at two arbitrary endpoints, so the ratio measures
*where the sawtooth got sampled*, not how much the desk churns. **Had I shipped the damper last cycle, this
window would have handed me a false ✅.** That is precisely the change-vs-market confusion the contract warns
about, and it is the third time this register has picked a small-sample rate and watched it drift. The same
script check also undercuts item #2's refined metric: of the **6 of 11** entry cancels with no successor,
**4 sit in the window's last ten minutes** and are simply right-censored.

## The defect itself is unchanged — only its measurement was wrong

The cost is the part that does not drift. `totalFees` **$477.838078** against `firmTotal`
**$-1,025.93604547** is **46.6%** of the entire cumulative loss, against 46% and 46% in the two prior
windows — stable to a tenth of a point while the churn ratio moved 4×. Cumulative LIVE turnover is
**$5,623,205** over **3,267** fills. The mechanism still shows in the `forecast=` series: `KO SELL 82 at
fc=-8.78` (18:21:58) → `SELL 45 at fc=-5.04` → `BUY 19 at fc=-0.165` (18:27:33) → `BUY 4 at fc=-0.389`
(18:28:03) — the forecast walks from -8.78 to -0.165 in five and a half minutes and the target follows 1:1.
So item #1 keeps its rank and its candidate fix (σ-scaled hysteresis on the *target*, plus a minimum holding
period — a damper, never a size cut, since ADR-0132 forbids buying quiet by holding nothing). Its VERIFY-BY
is now **Δ cumulative turnover ÷ mean gross exposure, measured across the change's full 6-cycle ADR-0116
window** — both endpoints cumulative and monotone, so sampling cannot move them — confirmed by `totalFees ÷
|firmTotal|` falling from 46.6%.

## Edge, re-checked per the standing priority — still none

`signal_observations` LIVE hit rates span **0.467–0.512** across every source and horizon. At 3600s:
`xsreversion` **-3.016** bps (n=810, std 64.7), `trend` **+0.668** (n=837, std 52.5), `reversion` **+0.105**
(n=793), `social` **+1.953** (n=304, std 96.9), `momentum` **+1.605** (n=78, std 51.8). Nothing clears its
own dispersion, and the two largest means sit on the two smallest samples. Against a **1.00 bps** per-side
equity fee, no source's mean return covers a round trip. Re-weighting sources without edge cannot create
edge — which is exactly why the one change stays on cost, where the sign of the saving is deterministic.

## Change vs. market — attributed honestly

**The window's -$1.07 and +$16,259.98 gross are credited to nothing I did.** No logic was deployed this
cycle or last; the running commit only removed code. The PnL move is market on positions I did not choose,
and the gross doubling is the fusion book still re-deploying in a process now 114 minutes warm — the same
clock effect Rules 433/459 record. I claim no credit and accept no blame for either. The churn-ratio
collapse is the sharpest available proof of exactly that point.

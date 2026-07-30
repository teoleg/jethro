The hand-completed revert of the BAD cold-sensor re-seed is VERIFIED live on every pre-registered leg — the mechanism logs nothing, seeds only at boot, and is gone from the tree — and since it is only 1/6 cycles into its measurement window I made no new change, ranking ALPHA's notional churn as the next target instead.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Situation triage

1. **Money.** `/api/risk` `.total` reads total PnL **$145.76317190** (realized **$159.90126235**,
   unrealized **−$14.13809045**). The report SITUATION header computes **+$20.51** on the run and
   **−$0.96** across the last three. Not bleeding this window; the last three are essentially flat,
   which is what `stale=true` on the last heartbeat is reporting.
2. **Risk.** Gross **$33028.88788750** = **2.2%** of the firm cap $1,500,000, headroom **$1,466,971**;
   net **−$10212.24788750** = **1.0%** of the $1,000,000 net cap. Flags: **none**. Gross fell
   **$3,279.94** on the run and **$17,223.30** across the last three. Not near a cap and not DORMANT —
   20 equity positions plus the ES hedge, filling continuously through the window.
3. **Cause.** Last cycle's change was the hand-completed revert of the ❌ BAD ADR-0131 cold-sensor
   re-seed (`64a7a6336`). It is **not yet scored**: `scripts/score-change.py score` prints
   `still accumulating evidence (1/6 cycles)` and `reports/.pending-baseline.json` is present.
4. **Danger.** No. Not bleeding, not near the exposure cap, breaker not tripped. The inverse does not
   apply either — gross is *falling*, so there is nothing to de-risk.

## Step 0 — did last cycle's revert land and work? ✅ VERIFIED

All four pre-registered legs check out against this run's JVM log and working tree:

1. The ADR-0131 WARN text (`re-seeding every … sightings until it does (ADR-0131)`) appears **zero**
   times. Last run's JVM logged it, which is what proved the BAD code was live.
2. Every `trend sensor warmed … from … stored prices` line is timestamped between **12:36:32** and
   **12:36:57**, against a `Started JethroApplication` at **12:36:29** — all inside the boot window.
   That is ADR-0071 boot seeding, which must and does still appear; there are **no** post-boot lines,
   which is the retry being gone.
3. With no retry there is no second seeding wave, so the six negative wave-over-wave deltas last run
   recorded (HD, PG, CAT, UNH, MCD, GOOG) have no mechanism to recur — and none do.
4. `grep -rn SensorReseed` over the tree returns nothing, and the JVM booted at **12:36:29** against a
   revert committed **12:35:46**, so the running process is this revert or later.

Item #1 is closed. Per the contract that frees the next must-fix — but the window is at 1/6, so the
next item is *ranked*, not *acted on*.

## Order-level post-mortem and honest attribution

`recent_orders` shows a steady ALPHA re-plan cadence roughly every 30s: each wave cancels the prior
passive orders with `fusion re-plan — passive order superseded by a fresh target (ADR-0084)` and
re-issues 1–8 share slices, alongside a continuous small ES hedge. No single trigger stands out as
having opened the window's loser; the move is spread across 20 names.

**Market vs change:** the revert removed a mechanism that *discarded* warm sensor state — it opens and
closes nothing directly. PnL up **$20.51** and gross down **$3,279.94**, on a book the desk was trading
throughout, is not separable from one cycle's numbers into market drift versus sensors now staying warm.
I claim **neither** as credit. Making that separation is exactly what the 6-cycle window exists for, and
it is 1/6 in.

## What I am watching, and why the next item ranks where it does

`/api/attribution` reads `firmTotal` **$145.76317190** = `strategyAlpha` **−$12.45990591** +
`hedgePnl` **$158.22307781**, `hedgeMasking` **true**. Two things there matter:

- **ALPHA is no longer negative on its book line** — the ALPHA book reads `totalPnl` **$23.36357064**
  (realized **$37.40103081**, unrealized **−$14.03746017**), against **$11.04806156** last run. But it
  paid `feesPaid` **$56.444977** to get there, so its fees exceed its net result. That is the
  fee-to-PnL ratio the last finding flagged, now the largest addressable cost on the desk.
- **`turnover_cost_by_name` names the mechanism, and it is turnover, not slicing.** Fees are charged in
  bps of notional (**1.00** bps on equities, **0.20** on ES), so fill *count* is not the driver —
  notional is. JNJ turned over **$75,222.83**, JPM **$67,623.49**, GOOG **$64,535.97**, AAPL
  **$61,986.59**, MSFT **$56,713.32**, on a book whose entire gross exposure is **$33,028.89**. Several
  single names each churned more notional this window than the whole firm has at risk.

That leak does **not** require finding new edge to fix, which is why it outranks the edge item —
`strategy_diag` still reads `measured` **29**, `tradable` **16**, **13** names `no positive OOS edge`,
and no source clears significance (at the 1h horizon reversion's **+6.628431761402847** bps mean is the
largest positive of any source, but its `stdCohortMeanBps` **35.44103416663609** over 46 cohorts dwarfs
it; trend reads **−4.429432051199271**). Cutting notional churn raises risk-adjusted PnL directly.

**No code change this cycle** — the revert is under measurement and stacking a change on top would
destroy its evidence. On the next scored cycle the one change targets ALPHA turnover.

Held again (4 of 6 evidence cycles) — and the predicted bad admission arrived: `trend`@3600s was let into the significance test on 35 observations that are only **9 independent sweeps**, and only its negative sign kept the desk out of a position.

*Every figure below is read from this run's `logs/report.md`, the live endpoints, the ledger or
`reports/run-status.json`; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number
that gates money). The t-statistics and p-values quoted are the edge gate's own published output.*

**No change this cycle — the evidence window is open.** `score` prints `9be1633c2 still accumulating
evidence (4/6 cycles)`; `reports/.pending-baseline.json` still exists for `9be1633` (ADR-0120), stamped
`2026-07-28T16:17:48Z`. Per the contract I diagnosed, checked last cycle's prediction, and stopped without
touching code. A new change committed on top would destroy the measurement of the one already live.

## Situation triage

1. **Money.** Total PnL reads `$0.61209817`, identical to the last run and to the run before it — `+0.00`
   over 1 run and `+0.00` over 3. `run-status.json` has `pnl_growth_pct: 0.0` against `pnl_target_pct: 1.0`,
   so `on_track: false`, `stale: true`, `underwater: false`. Not bleeding; stalled.
2. **Risk.** Gross `$0.00`, net `$0.00`. HEDGE (ES) and ALPHA (AAPL) each still carry a position row, both
   at `quantity: 0`. `var95`/`es95`/`var99` all `0.00` with `note: "no positions"`. Breaker
   `halted: false`. Feed live — `alpaca` connected, `lastUpdateAgeMillis: 848`. Zero risk because zero book.
3. **Cause.** The change under measurement is `9be1633` (ADR-0120 — a cohort is one sweep of the
   cross-section). It has **no verdict yet**; the last scored row was `⚠️ MIXED`. Since it went in, the book
   has been flat and PnL has not moved a cent, so there is nothing for it to have helped or hurt.
4. **Danger.** None. Not bleeding, exposure not rising, breaker clear, no stale-mark exposure.
5. **Order post-mortem.** `orders_day` shows `total: 7`, all `FILLED`, newest at `15:50:41Z` — hours before
   this window opened. **Zero orders this cycle.** `edgeGate.mayIncrease: false`, so `fusion_targets`
   publishes non-zero `targetQty` on all 8 routed names (NVDA `+68.44`, MSFT `−48.19`, …) while every
   `deltaQty` is `0`. The desk is forming views and declining to act on them — the designed reduce-only
   behaviour when no source clears cost.
6. **Change vs market — cleanly separable this cycle.** With zero orders and a zero book, the window's PnL
   move is `+0.00` from market conditions and `+0.00` from my last change. Neither credit nor blame is
   available. This is the rare cycle where the attribution question has an exact answer rather than an
   estimate.

## The prediction I logged last cycle, checked

I wrote that `trend`@3600s would cross `resolved: 30` at `cohorts` 8–9, be admitted to the t-test, and fail
at roughly `tStat −0.97`. It reads `resolved: 35`, `cohorts: 9`, `tStat: -0.9491041554975994`,
`pValue: 0.8148231827460571`, `passes: false`. The gate holds, and it holds for the right reason.

**But note what just happened.** Rule 54 said the admission bound (`resolved < params.minSample`) is
counted in observations while the standard error and the Student-t degrees of freedom are counted in
cohorts — `app/src/main/java/io/jethro/app/fusion/EdgeGate.java:171`, whose very next lines divide by
`stdErrorBps` and call `Significance.studentTUpperTail(t, cohorts - 1.0)`. Until now that was an argument.
This cycle it is a fact on the tape: a source was admitted to the significance test on **35 observations
that are only 9 independent sweeps**, and its verdict was then decided on 8 degrees of freedom. It failed
only because its measured expectancy happens to be negative (`avgReturnBps: -6.333250868827161`). Had those
same 9 cohort means landed positive, the desk would have sized on them. ADR-0108 moved the evidence
*budget* to cohorts and stated in as many words that `min-sample` does not move; that leftover is precisely
the hole ADR-0108's own table was written to close.

## Edge mission — re-checked, still honestly negative

Against a measured round trip of `roundTripCostBps: 0.6278285714285714`, no source clears at 3600s:
`reversion` `p=0.3096`, `social` `p=0.4992`, `momentum` `p=0.6507`, `trend` `p=0.8148`. The two rows with
the most sample (`trend` 35 resolved / 9 cohorts, `reversion` 27 / 7) are the two whose net edge is worst.
The per-name OOS gate agrees independently — 10 of the 18 measured names carry `no positive OOS edge`
notes, and `strategy_diag` reports `signals: 0`, `executed: 0`. The binding constraint remains sample, and
invariant 8 forbids borrowing SIM history to manufacture it. A fifth signal would lengthen the
INCONCLUSIVE wall, not escape it.

**Queued for the cycle after `9be1633` scores (deliberately not shipped — one change per run):**
denominate the gate's admission bound in cohorts, the unit its standard error and df already use. One
honest consequence to state before shipping: at 3600s the best row has `cohorts: 9`, so a
cohort-denominated `min-sample` would shut that horizon entirely until far more sweeps accumulate, while
225s (`trend` 81, `reversion` 68) stays admitted. That is stricter — and it is the strictness the statistic
has been claiming all along.

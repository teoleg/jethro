Held: last cycle's cohort re-specification is 1 of 6 evidence cycles in, and its exact pre-ship prediction landed — but checking it surfaced that `min-sample` is now the ONLY thing keeping the desk out of a 2-cohort momentum reading with a degenerate standard error.

*Every figure below is read from this run's `logs/report.md`, the live endpoints, the ledger or
`reports/run-status.json`; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number
that gates money). The p-value arithmetic quoted at the end is the gate's own published output.*

## No change this cycle — and why that is the rule, not a judgement call

`reports/.pending-baseline.json` exists for `9be1633` (ADR-0120), stamped `16:17:48Z`. Exactly one
heartbeat has accrued since (`16:18:09Z`) against `MIN_CYCLES=6`, and no ledger row or
`reports/attribution/` snapshot has been written for it. Under ADR-0116 that is `still accumulating
evidence`, and the contract is unambiguous: piling a new change on top destroys the evidence for the
one being measured. So I diagnosed, verified last cycle's prediction, and stopped.

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `$0.61209817`, **unchanged** on the run (`+0.00`), **up** `+$1.98` across the
last three. All of it realized; unrealized is `$0.00`. Not bleeding.

**2. Risk.** Gross `$0.00`, net `$0.00`. VaR95 `$0.00`, ES95 `$0.00` (`note: "no positions"`), breaker
`halted: false`. Both legs show `quantity: "0"`. The book is **flat** — there is no exposure to sit
near any limit.

**3. Cause.** The pending change is unscored, so it has no verdict yet. The one before it
(`b2a569f32`, ADR-0119) scored ⚠️ MIXED, and it is what put the desk here: composed with ADR-0118 it
drove the AAPL round trip that closed both legs. The window's PnL move is `0.00` for a mechanical
reason worth naming — **with zero positions there are no marks to drift**, so total PnL is frozen at
realized and will not move by a cent until the gate lets the desk trade again.

**4. Danger.** None. Flat book, zero VaR, breaker clear.

**5. Order post-mortem.** No orders this window. `recent_orders` ends at `15:50:41` with the ES hedge
unwind; `turnover_cost_by_name` shows the whole LIVE session as 7 fills, `$0.0857` of fees.

**7. Change vs market — attribution.** **Neither.** Zero orders and zero positions, so nothing in the
window is attributable to the market *or* to my code. There is nothing here to claim or be blamed for.

**A flag I want to contradict out loud.** `run-status.json` reads `on_track: true` with
`pnl_growth_pct: 152.37`. That is arithmetically correct and economically misleading: it measures one
step off a negative base, not ongoing earning. The desk is currently earning **nothing**. I am not
treating the green flag as permission to rest — I am barred from acting by the evidence window, which
is a different thing, and I have queued the next change below.

## ADR-0120 verified — the prediction landed, including the exact number

I wrote the check into last cycle's finding so it could be confirmed rather than re-derived. It was:
`cohorts` roughly halves at every source/horizon while the observation set is unchanged. Live
`signals_telemetry` now reads `trend` at 7 / 27 / 71 cohorts across 3600s / 900s / 225s, against
predictions of ≈7 / ≈25 / ≈68. The decisive one: the pre-ship SQL said `trend`@3600s would move from
16 cohorts at `-2.41` bps to 7 at `-8.40` bps, and the live gate now publishes `cohorts: 7`,
`avgReturnBps: -8.40379205357143`. A cohort-weighted mean can only land on a pre-computed value if the
grouping changed and nothing else did. The re-specification took effect exactly as designed, and it
made the gate honestly harder. The gate stayed shut and the book stayed flat, as predicted.

## What verifying it surfaced — the real defect, queued not shipped

Last cycle I noted that `min-sample=30` is expressed in **observations** while ADR-0108 moved the
estimator's sample bound to **cohorts**, and dismissed it as changing nothing today. Coarsening the
cohorts has made that dismissal wrong.

`momentum`@3600s now reads `resolved: 5`, `cohorts: 2`, `avgReturnBps: 17.5960475`,
`stdErrorBps: 0.9438605000000002`, `tStat: 18.151461471266142`, `pValue: 0.017518613305248686`. The
`stdReturnBps` on the same row is `95.96` — the tiny standard error is not precision, it is two cohort
means that happened to land close together. The gate is doing its Student-t correctly: that p-value is
exactly df=1. But `tHurdle` is `2.0` and `t` is `18.15`, so **the significance test passes**. The only
thing holding this back is `minSample: 30` compared against `resolved: 5`.

That is a bound in the wrong unit standing in for the bound that matters. `resolved` will cross 30 on
observation count while `cohorts` is still in single digits — precisely the regime where a degenerate
two-or-three-cohort standard error can clear a t-hurdle on noise. The fix is to denominate the gate's
admission test in cohorts, the same unit its standard error and degrees of freedom already use. It is
the next change, and it waits for the pending row to score.

I did **not** attempt the cross-sectional demeaning I flagged as ADR-0120's natural successor. Its
precondition — cohorts genuinely merging — is now confirmed, so it remains askable, but this unit
mismatch is the more urgent of the two: it sits on the admission test that gates exposure.

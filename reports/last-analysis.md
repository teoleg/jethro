Held again (5 of 6 evidence cycles) — and the fix I had queued for next cycle just failed its own test, so I am **retracting** it: `reversion`@3600s was admitted with a POSITIVE mean on 8 cohorts and the significance clause stopped it cold, which is the sample bound I was worried about doing its job.

*Every figure below is read from the live endpoints, `reports/run-status.json`, the ledger or this run's
report; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number that gates money).
The t-statistics, p-values and params quoted are the edge gate's own published output.*

**No change this cycle — the evidence window is open.** `score` prints `9be1633c2 still accumulating
evidence (5/6 cycles)`; `reports/.pending-baseline.json` still exists for `9be1633` (ADR-0120), stamped
`2026-07-28T16:17:48Z`. Diagnosed, checked last cycle's prediction, retracted a queued change, stopped.

## Situation triage

1. **Money.** Total PnL reads `$0.61209817` — identical to the last run, and to the three before it.
   `+0.00` over 1 run, `+0.00` over 3. `run-status.json`: `pnl_growth_pct: 0.0` vs `pnl_target_pct: 1.0`,
   `on_track: false`, `stale: true`, `underwater: false`. Not bleeding; stalled.
2. **Risk.** Gross `$0.00`, net `$0.00`. Both book rows flat — HEDGE (ES) `+0.95512117`, ALPHA (AAPL)
   `-0.34302300`, summing to the firm total. Breaker `halted: false`. Feed live (`alpaca` connected,
   `lastUpdateAgeMillis: 62`). Zero risk because zero book.
3. **Cause.** `9be1633` (ADR-0120) has **no verdict yet**; the last scored row was `⚠️ MIXED`. The book has
   been flat throughout its window, so there is nothing for it to have helped or hurt.
4. **Danger.** None. Not bleeding, exposure not rising, breaker clear.
5. **Order post-mortem.** The newest order is `159.9` minutes old — **zero orders this window**. All 8
   routed names publish a non-zero `targetQty` (AMZN `-211.914746`, MSFT `-108.375505`, …) with every
   `deltaQty: 0` and every `aims` entry `0.0`: the designed reduce-only behaviour while
   `edgeGate.mayIncrease: false`.
6. **Change vs market — exactly separable.** Zero orders, zero book: the window's PnL move is `+0.00` from
   market **and** `+0.00` from code. Neither credit nor blame is available this cycle.

## Last cycle's prediction, checked — it landed exactly

I wrote that `reversion`@3600s was the row to watch, that it would cross `resolved: 30` on single-digit
cohorts, and that a **positive** mean admitted on ~8 cohorts would make the queued fix load-bearing. It now
reads `resolved: 34`, `cohorts: 8`, `avgReturnBps: 7.374500649147727` — positive — `stdErrorBps:
10.218113619092685`, `netEdgeBps: 6.910900649147727`, `tStat: 0.6763382074979686`,
`pValue: 0.26026966244441807`, `passes: false`. Admitted on the observation count, positive-signed, and
rejected by the **significance** clause rather than by the sign.

## Why that retracts the queued change

Rule 55 said a gate that rejects only on sign leaves its sample bound untested. This cycle supplied the
test — and the sample bound turned out not to be what protects the desk. I re-implemented the gate's
Student-t tail independently and reproduced its published p-values (`0.26027` vs `0.26026966`; `0.793545`
vs `0.79354495`), then asked what `tStat` clears `params` (`minSample: 30`, `tHurdle: 2.0`,
`hypotheses: 3`, so α = `0.00758337731605974`) at each degrees-of-freedom the gate actually uses:

| df (= cohorts − 1) | 1 | 2 | 3 | 7 | 10 | 32 | 85 |
|---|---|---|---|---|---|---|---|
| t required | 41.97 | 8.03 | 5.03 | 3.195 | 2.925 | 2.566 | 2.479 |

The t-distribution **already** imposes the small-cohort penalty, and imposes it savagely — it is the
textbook-correct correction for precisely the failure I was worried about, a standard error estimated from
few draws. At its 8 cohorts, `reversion`@3600s would need on the order of `33` bps of expectancy to clear
against the `7.374500649147727` it measures. Its observation count was never what let it through, and its
cohort count was never what stopped it.

Meanwhile the cost of shipping the fix is now measurable: a cohort-denominated `min-sample: 30` would shut
**9 of the 12** source×horizon rows — all four at 3600s (best is `trend` at `cohorts: 9`), three of four at
900s (only `trend` at `33` survives), and two of four at 225s. That is a large loss of measurement surface
to buy a protection the reference distribution already provides. **Retracted — I will not ship it.** The
`resolved`/`cohorts` unit mismatch in `EdgeGate.clears`
(`app/src/main/java/io/jethro/app/fusion/EdgeGate.java:171`) is worth a clarifying comment someday; it is
cosmetic, not a safety hole, and not worth a cycle.

## Edge mission — one structure worth naming, honestly discounted

`reversion` is the only source with a positive mean at **every** horizon: `225s +0.533252798747244`,
`900s +1.2789941648057297`, `3600s +7.374500649147727`, rising with horizon while
`roundTripCostBps: 0.6278285714285714` stays fixed — net of cost that is negative at 225s and positive at
900s and 3600s. That is the shape of an edge amortising a fixed cost over a longer hold. **I am not going
to oversell it:** those three rows measure the *same* signal over *overlapping* windows, so they are
nowhere near three independent confirmations, and no single one is significant (`p = 0.26026966244441807`
at its best). Every other source is negative-mean at its best-sampled horizon — `trend` `-0.0895626400383285`
@900s, `momentum` `-6.556053555555556` @3600s, `social` `-0.8379598939393939` @900s. The binding constraint
is still sample, and invariant 8 forbids borrowing SIM history to manufacture it.

**Next cycle `9be1633` scores (6/6) and I can act.** The lead I intend to take up then is the horizon
question this structure raises — whether the ladder should prefer the hold at which `reversion`'s
expectancy clears its cost — not another admission-bound edit.

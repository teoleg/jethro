Held again (3 of 6 evidence cycles) — and the momentum row that read t=18.15 two cycles ago has now walked 18.15 → 0.49 → **−0.45** on three single observations, which is the cohort-denominated min-sample bug writing its own proof in production.

*Every figure below is read from this run's `logs/report.md`, the live endpoints, the ledger or
`reports/run-status.json`; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number
that gates money). The t-statistics and p-values quoted are the edge gate's own published output.*

## No change this cycle — the rule, not a judgement call

`scripts/score-change.py score` prints `9be1633c2 still accumulating evidence (3/6 cycles) — held, not
scored this run`. `reports/.pending-baseline.json` still exists for `9be1633` (ADR-0120) at `16:17:48Z`;
the newest `reports/attribution/` snapshot is still `160006Z-b2a569f32.json`, so no ledger row has been
written. Shipping on top of an open evaluation window destroys the evidence for the change being
measured. I diagnosed, verified the standing predictions, and stopped.

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `$0.61209817`, **unchanged** on the run and across the last three. All realized;
unrealized `$0.00`. Not bleeding.

**2. Risk.** Gross `$0.00`, net `$0.00`. Breaker `halted: false`, `reason: null`. Both legs
`positionCount: 1` at `quantity: "0"` — HEDGE/ES `+$0.95512117`, ALPHA/AAPL `-$0.34302300`. Marks are
live (`markAgeMillis: 768`), so this is a genuinely flat book, not a stale one. No exposure to sit near
any limit.

**3. Cause.** The pending change is unscored and has no verdict. The prior one (`b2a569f32`, ADR-0119)
scored ⚠️ MIXED and is what closed both legs. The window's `+0.00` is mechanical, not stability: **zero
positions ⇒ no marks to drift**, so PnL is pinned at realized until the gate reopens.

**4. Danger.** None.

**5. Order post-mortem.** No orders this window. `recent_orders` still ends at `15:50:41` with the ES
hedge unwind; the whole LIVE session is 7 fills — ES 5 fills / `$897.38` turnover / `0.20` bps, AAPL
2 fills / `$678.08` / `1.00` bps.

**7. Change vs market — attribution.** **Neither.** Zero orders, zero positions: nothing in this window
is attributable to the market *or* to my code. Nothing to claim or be blamed for.

`run-status.json` reads `on_track: true` with `pnl_growth_pct: 221.05`. Arithmetically correct,
economically misleading — that is one step off a negative base, not ongoing earning. The desk earns
nothing right now. I am barred from acting by the evidence window, which is not the same as resting on
a green flag.

## Predictions from last cycle, checked

- Gate stays shut — `mayIncrease: false`, ✓.
- Book flat, zero orders — ✓.
- PnL pinned at `$0.61209817` barring a fill — ✓, to the cent.
- Scorer prints `3/6`, no ledger row — ✓.
- `trend`@3600s crosses `minSample: 30` with cohorts 8–10 — **not yet**: it reads `resolved: 29`,
  `cohorts: 8`. One observation short. Its `tStat` is `-0.970` (I said `-1.02`; the gate recomputes each
  cycle), so when it is admitted next cycle it will fail, and the gate stays shut for the right reason.

## The queued fix, now demonstrated three times over

Two cycles ago `momentum`@3600s read `resolved: 5`, `cohorts: 2`, `tStat: 18.151`, `pValue: 0.0175` —
the significance test **passing**, held out only by `minSample: 30`. Since then, on one new observation
each time: `tStat: 0.487` (`cohorts: 2`), and now `resolved: 7`, `cohorts: 3`, `avgReturnBps: -5.838`,
`stdErrorBps: 14.095`, `tStat: -0.447`, `pValue: 0.651`. **18.15 → 0.49 → −0.45.** The same row crossed
from far above the `tHurdle: 2.0` to plainly negative in three draws. A two- or three-cohort standard
error is not precision; it is where a couple of means happened to land, and it traverses the hurdle
freely in both directions.

The defect is one line. `EdgeGate.clears` (`app/src/main/java/io/jethro/app/fusion/EdgeGate.java:171`)
gates admission on `resolved < params.minSample()` while requiring only `cohorts < 2` to reject — yet
the very next line takes `Significance.studentTUpperTail(t, cohorts - 1.0)`. The standard error and the
degrees of freedom are both in **cohorts**; the admission bound alone is in **observations**. Across
this epoch `resolved`/`cohorts` runs ~3.6× (`trend`@3600s 29/8, `reversion`@3600s 26/7, `trend`@225s
434/79), so `resolved` clears 30 while `cohorts` is still single-digit — exactly the regime the momentum
row just walked through. That is ADR-0108's cohort-denominated sample bound defeated by the one test
never converted.

**Queued, not shipped (one change per run):** denominate the gate's admission test in cohorts. Ships the
cycle after `9be1633` gets a ledger row.

## On the standing edge mission

Re-checked, still honestly negative. Every source fails at the demanded confidence against a
`0.6278` bps round trip: `reversion` `p=0.327`, `social` `p=0.580`, `momentum` `p=0.651`, `trend`
`p=0.818`. Not one is close, and the two with the most sample are the two with negative net edge. The
binding constraint stays sample — 29 resolved 3600s `trend` observations in this LIVE epoch, and
invariant 8 forbids borrowing SIM history. A fifth signal into a measurement that cannot separate four
from zero would grow the INCONCLUSIVE wall, not escape it. The honest work is to make the admission test
correct *before* the sample crosses it.

## Predicted next, so it can be checked

Gate shut, book flat, zero orders, PnL `$0.61209817`, scorer `4/6`, no ledger row. `trend`@3600s crosses
`resolved: 30` with `cohorts` at 8–9 and is admitted to the t-test; at `tStat −0.97` it fails and the
gate holds. Regime stays `CHOP`/`CALM` (`volRatio 0.86`). If any source is admitted and *passes* on
single-digit cohorts before `9be1633` scores, the queued fix stops being merely correct and becomes the
thing standing between the desk and exposure taken on two data points.

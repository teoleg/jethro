Held again (2 of 6 evidence cycles) — and the check paid for itself: the momentum reading I flagged last cycle as "one observation from opening the gate" collapsed from t=18.15 to t=0.49 on a single new observation, which is the cohort-denominated min-sample bug demonstrating itself in production.

*Every figure below is read from this run's `logs/report.md`, the live endpoints, the ledger or
`reports/run-status.json`; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number
that gates money). The t-statistics and p-values quoted are the edge gate's own published output.*

## No change this cycle — the rule, not a judgement call

`reports/.pending-baseline.json` still exists for `9be1633` (ADR-0120), stamped `16:17:48Z`. Two
heartbeats have accrued since (`16:18:09Z`, `16:33:45Z`) against `MIN_CYCLES=6`. No ledger row, no
`reports/attribution/` snapshot — the newest is still `160006Z-b2a569f32.json`. Under ADR-0116 that is
`still accumulating evidence`, and shipping on top of it would destroy the evidence for the change
being measured. So I diagnosed, verified the predictions, and stopped.

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `$0.61209817`, **unchanged** on the run, up `+$1.12` across the last three. All
realized; unrealized `$0.00`. Not bleeding.

**2. Risk.** Gross `$0.00`, net `$0.00`. VaR95 `$0.00`, breaker `halted: false`. Both legs
`positionCount: 1` at zero quantity — FUTURE `+$0.95512117`, EQUITY `-$0.34302300`. The book is
**flat**; there is no exposure to sit near any limit.

**3. Cause.** The pending change is unscored, so it has no verdict. The prior one (`b2a569f32`,
ADR-0119) scored ⚠️ MIXED and is what put the desk here. The window's `+0.00` is mechanical, not
stability: **zero positions ⇒ no marks to drift**, so PnL is pinned at realized until the gate reopens.

**4. Danger.** None.

**5. Order post-mortem.** No orders this window. `recent_orders` still ends at `15:50:41` with the ES
hedge unwind; the whole LIVE session is 7 fills and `$0.09` of fees.

**7. Change vs market — attribution.** **Neither.** Zero orders, zero positions: nothing in this window
is attributable to the market *or* to my code. Nothing to claim or be blamed for.

`run-status.json` reads `on_track: true` with `pnl_growth_pct: 144.62`. Arithmetically correct,
economically misleading — that is one step off a negative base, not ongoing earning. The desk earns
nothing right now. I am barred from acting by the evidence window, which is a different thing from
resting on a green flag.

## Every prediction from last cycle landed

I wrote them down with numbers so they could be checked rather than re-derived:
- Gate stays shut — `mayIncrease: false`, ✓.
- Book stays flat, zero orders — ✓.
- PnL pinned at `$0.61209817` barring a fill — ✓, to the cent.
- Scorer keeps accumulating, no ledger row — ✓, `2/6`.
- Cohorts keep growing as observations resolve — `trend` was 7 / 27 / 71 at 3600s / 900s / 225s, now
  reads **8 / 28 / 75**. ✓.

Nothing outside the loop touched the book.

## What the check overturned — and why it strengthens the queued fix

Last cycle I reported `momentum`@3600s at `resolved: 5`, `cohorts: 2`, `avgReturnBps: 17.60`,
`stdErrorBps: 0.944`, `tStat: 18.151`, `pValue: 0.0175` — the significance test **passing**, held out
only by `minSample: 30` against `resolved: 5`. I called it the gate being one hair from opening on
noise.

One new observation later, the same row reads `resolved: 6`, `cohorts: 2`, `avgReturnBps: 6.386`,
`stdErrorBps: 12.154`, `tStat: 0.487`, `pValue: 0.356`. **The t fell from 18.15 to 0.49 on a single
draw.** The urgency framing was wrong — this reading was never going to persist. But the *diagnosis* is
now demonstrated rather than argued: a two-cohort standard error is not precision, it is an accident of
where two means happened to land, and it swings across the hurdle freely in either direction. It landed
harmlessly this time. Nothing in the gate makes that the general case.

The arithmetic that makes it live rather than theoretical: `resolved`/`cohorts` runs ~3.4× across every
row (`trend`@3600s 27/8, `reversion`@3600s 20/6, `trend`@225s 386/75). So `resolved` crosses
`minSample: 30` while `cohorts` is still around 9. `trend`@3600s sits at `resolved: 27` after roughly
four hours of LIVE session — it will cross 30 within a cycle or two and be admitted to the t-test on
single-digit cohorts. That is ADR-0108's cohort-denominated sample bound defeated by the one test that
never got converted: an admission bound in observations guarding a statistic whose standard error and
Student-t degrees of freedom are both in cohorts.

**Queued (not shipped — one change per run, window open):** denominate the edge gate's admission test
in cohorts, the unit the rest of its statistics already use. Ship once `9be1633` has a ledger row.

## On the standing edge mission

Checked, and the answer is still no: every source fails at the demanded confidence — `reversion`
`p=0.243`, `momentum` `p=0.356`, `social` `p=0.468`, `trend` `p=0.829`, against a round-trip cost of
`0.628` bps. Not one is close. The binding constraint remains sample: this LIVE epoch has 27 resolved
3600s observations in total, and invariant 8 forbids borrowing the SIM history. Building a fifth signal
into a measurement that cannot yet distinguish four of them from zero would be adding to the
INCONCLUSIVE wall, not escaping it. The honest work is to make the gate's admission test correct
*before* the sample crosses it — which is exactly the queued change.

## Predicted next, so it can be checked

Gate stays shut, book flat, zero orders, PnL pinned at `$0.61209817` barring a fill. Scorer prints
`3/6`. `trend`@3600s `resolved` crosses 30 with `cohorts` in the 8–10 range and is admitted to the
t-test — its `tStat` is `-1.02`, so it still fails, and the gate stays shut for the right reason. If
`trend`@3600s is admitted and *passes* on single-digit cohorts, the queued fix is urgent rather than
merely correct.

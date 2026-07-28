Held at 1/6 while ADR-0121 measures — the new source landed exactly as predicted, and it exposed a real structural asymmetry: the two names where it is the ONLY view are the two names that currently cannot be stopped out.

*Every figure below is read from the live endpoints, `reports/run-status.json`, the ledger or this run's
report; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number that gates money).
The t-statistics quoted are computed by script from the gate's own published cohort dispersions.*

**No change this cycle — the evidence window is open.** `score` prints `68ff73eb2 still accumulating
evidence (1/6 cycles)`; `reports/.pending-baseline.json` exists for `68ff73eb2` (ADR-0121), stamped
`2026-07-28T19:17:20Z`. Diagnosed, checked last cycle's prediction against the live telemetry, recorded
one new structural finding, stopped.

## 1–4. The live situation, in plain numbers

**Money.** Total PnL `$0.61209817` — unchanged for a sixth consecutive run. Since last run `+0.00`; over
the last three runs `+0.00`. `pnl_growth_pct` `0.0%` against `pnl_target_pct` `1.0%`, `on_track=False`,
`stale=True`, `underwater=False`. Not bleeding; stalled.

**Risk.** Gross `$0.00`, net `$0.00`. VaR `0.00` with `note: "no positions"`. Breaker `halted: false`.
Books flat and summing to the firm total: HEDGE (ES) `+0.95512117`, ALPHA (AAPL) `-0.34302300`. Feed live
(`alpaca` connected, `lastUpdateAgeMillis: 10`, 35 instruments, `ticksDropped: 0`). Nothing at risk.

**Cause.** `68ff73eb2` (ADR-0121) has no verdict yet — `1/6`. The last scored row was `⚠️ INCONCLUSIVE`.

**Danger.** None. Not bleeding, exposure not rising, breaker clear.

**5. Order post-mortem.** `orders_day.total: 7`, newest created `2026-07-28 15:50:41` UTC against a report
clock of `1785267002113` — roughly three and a half hours old. **Zero orders this window.** Every routed
name shows `deltaQty: 0` and `currentQty: 0`: the designed reduce-only behaviour while
`edgeGate.mayIncrease: false`. No trigger to blame or strengthen.

**7. Change vs market — exactly separable.** Zero orders and a flat book: the window's move is `+0.00`
from market **and** `+0.00` from code. Neither credit nor blame is available.

## Last cycle's prediction, checked — it landed on every clause

I predicted `xsreversion` would appear in `signals_telemetry` within a cycle or two, with cohorts in the
low single digits at 225 s and nothing yet at 3600 s, and that the EQUITY group would be the only one
clearing `min-peers: 4`. All three landed. It reads `resolved: 17`, `cohorts: 2`, `open: 9` at 225 s and
`cohorts: 0`, `open: 10` at both 900 s and 3600 s — the longer rungs have simply not resolved yet. Every
one of its eight contributions in `fusion_targets` is an equity (TSLA, MSFT, AMZN, JPM, GOOG, NVDA, NFLX,
JNJ); `NQ` is the only non-equity routed name and carries no `xsreversion` term. So the source is live,
correctly EQUITY-scoped, and the `min-peers` failure branch is **closed** — the diagnosis is the signal
from here, not the plumbing. Its weight sits mid-pack at `1.0113609266093764`.

**Its early sign is negative, and I am deliberately not reading it.** `avgReturnBps
-2.857963236111111`, `hitRate 0.25` — but on `cohorts: 2`, i.e. one degree of freedom, where my own
computed table says the gate demands `t ≈ 41.97`. This desk has already watched a 2-cohort row walk from
far above the hurdle to negative. Two cohorts is not evidence in either direction.

## The one thing worth flagging, which the flat book is hiding

Reading `fusion_targets` name by name: `xsreversion` is the **sole** source on **TSLA** and **NFLX** —
and TSLA carries the largest `|combinedForecast|` (`-19.482833721224054`, near the clamp) and the largest
notional target in the book. The mechanism is plain in `ForecastCombiner.combine`
(`app/src/main/java/io/jethro/app/fusion/ForecastCombiner.java:104`): the combined value is
`average × dm × agreement`, and at one source the average IS that source's raw reading — no cross-source
shrinkage — while `dm = 1.0` and `agreement = 1.0`. That is correct per ADR-0119/0076 as written (the
javadoc says so explicitly: "unanimous sources (and the single-source case) return 1 and change nothing"),
but note what it means: the fusion layer has a **bonus for broad coverage and no discount for thin
coverage**. A name held up by one source with two cohorts of measurement is treated exactly like GOOG,
where four sources agree.

Why those two names specifically is answered by the WARN log, and it is the uncomfortable part: TSLA and
NFLX are precisely where the own-history sensors are still cold (`trend` seeded `66 of 193` for TSLA,
`34 of 193` for NFLX), which is *why* only the cross-sectional source has a view. And the same log says
`risk-cut σ sensor still cold for TSLA` / `for NFLX` — "this name cannot be stopped out until its mark
history has accumulated". So the two names with the thinnest conviction and the largest targets are the
two names with **no working stop**.

**This is currently harmless** — `mayIncrease: false` holds every `deltaQty` at 0, so none of it routes.
I am not acting on it this cycle and I would not act on it as a combiner tweak. But it is a risk-control
asymmetry, not an edge question, and it is the lever I intend to pre-check (quantitatively, before it
becomes a commit) once ADR-0121 scores: conviction should not be independent of whether the name can be
stopped.

## Predicted next, so it can be checked

Scorer at `2/6` next run; no ledger row for another five cycles and PnL flat at `$0.61209817` absent
orders. `xsreversion`@900 s should resolve its first cohorts within a cycle or two; 3600 s later. The
falsification standard from last cycle stands unchanged: **if `xsreversion` measures null once it has real
cohorts, that is evidence this universe has no short-horizon reversal to capture, and the honest next move
is a different feed — not a sixth source.**

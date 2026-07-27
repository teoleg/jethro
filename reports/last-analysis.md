The desk has been reduce-only its whole life on this feed because the edge gate's evidence budget was counted in ROWS, and a source that calls its whole cross-section at once spends that budget at its own width (ADR-0108).

*Every figure below is quoted from the live endpoints, the report or the ledger, or computed by a script
against the live database; none is authored here. The ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** The report's SITUATION header reads total PnL `$5725.54`, with the last three runs at
`+4834.17`. `run-status.json`'s last measured entry read `pnl_growth_pct 11.96` against a `1.0` target,
`on_track` true, `stale` false, `underwater` false. The book is **not** bleeding and is far ahead of the
owner target. `/api/attribution` splits it `ALPHA +8,419.40`, `MACRO +376.996` (unchanged to the cent for
a **thirteenth** cycle), `HEDGE −3,070.86`, fees `$471.21`.

**2. Risk.** Gross `$0.00`, net `$0.00` — every position reads `quantity 0`. VaR/ES `0.00` with the note
"no positions"; the breaker is clear; the hedge axis reads `status FLAT`. There is no danger state, and
none is possible: the book carries no risk at all.

**3. Cause.** Last cycle's `cdef5f160` (ADR-0107) scored **✅ GOOD**. Its own finding predicted "average
gross exposure UP, because positions now persist through crossings" — the opposite happened, and the
whole `+4,798.49` is realised, with `unrealizedPnl 0.00000000` on every book. So this is a book that
**unwound to flat and booked what it had**, not a change that earned. I do not claim the move for
ADR-0107: the window contains a restart (the JVM now logging booted at 09:29 EDT, after the window's
orders), and a realised-only jump across an unwind cannot be separated from the market on the numbers
alone. Reported as unattributable rather than credited.

**4. Danger.** None — and that *is* the problem. `/api/fusion/targets` reads `edgeGate.mayIncrease:
false` with `deltaQty: 0` on all nine planned names against `grossExposure $0.00`. Zero exposure earns
zero going forward, so the PnL growth that is on-track today goes stale by construction. This is the
binding constraint, and everything else on the desk is downstream of it.

**5. Order-level post-mortem.** The window's tape is dominated by two non-trades. `ALPHA JPM SELL 23`
was REJECTED **45 times in a row**, every ~30 s for twenty minutes, `no market data for JPM` — the order
module's own `LastPriceCache` is fed off `md.marks`, which `MarkPublisher` skips for a stale mark, while
`/api/marks` shows JPM live from alpaca. And `ALPHA MSFT BUY` was CANCELLED on every cycle, `fusion
re-plan — passive order superseded by a fresh target (ADR-0084)`. Both are real; neither is why the book
is flat, because the gate refuses to plan a delta at all. Logged for a later cycle.

## What I checked before choosing, and what it said

`/api/fusion/targets` shows every one of the nine target names with `sources: 1` — the fused book is
**entirely `trend`**, at weight 1.23, on a 3 600 s reading of `+72` bps whose t-stat is 0.88. Nothing
clears. I pulled `signal_observations` and re-ran the gate's own arithmetic (α = Φ̄(2.0)/3 = 0.007583
after the ADR-0082 haircut, Student's t on cohorts−1 df, net of the cheapest measured round trip) under
one change only: the sample bound.

`SignalTelemetryStore` capped the read at `sample-limit = 500` **rows**. That bound predates ADR-0077,
which moved the standard error onto **cohorts**. So the budget is spent at cross-section width: a source
calling ~23 names per burst is pinned at ~58 independent draws **forever** — it cannot accumulate a 59th
however long the desk runs — while `momentum`, at 234 rows, gets 186. The gate's power was capped by
breadth rather than by evidence, which is precisely the thing it exists to reward. Measured on the live
table: `reversion` @ 225 s goes from 58 cohorts, t = 0.99, p = 0.16237 (shut) to 250 cohorts, t = 2.50,
p = 0.00653 (**open**) — with the point estimate *falling*, 10.35 → 6.27 bps, because the standard error
falls faster. Not a bigger edge; a better-measured one.

I also tested and **rejected** cross-sectional demeaning before writing any code: it moves `reversion`
@ 3 600 s only from t = 1.04 to 1.14, because mean and standard error shrink together (222 → 26 bps),
and it collapses any one-name cohort to identically zero. Recorded in the ADR so it is not re-attempted.

## The change

`jethro.signals.sample-limit` (rows) becomes `jethro.signals.cohort-limit` (cohorts), the ADR-0077
gap-cut grouping moves into SQL where the rows live, and each cohort comes back as its sufficient
statistics — so this reads *less* from the database while covering an order of magnitude more history.
No arithmetic changed; a test pins the in-memory and SQL groupings to the same `Stats`. Expected: the
gate opens at the 225 s rung on `reversion`, permission arrives name by name under the ADR-0075 per-name
cost test (only `ES` clears today; `AAPL` reads p = 0.011), and `trend` — which carries the whole book —
is demoted to ADR-0097's minimum weight because at that rung it measures **significantly negative**,
t = −2.47. Gross exposure will rise from `$0.00`, which a book at zero cannot avoid; if the desk does
not earn on it, ❌ BAD and the revert are the correct answer, and the scorer settles it.

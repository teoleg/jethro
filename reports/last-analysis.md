Repaired the loop's own cost/turnover post-mortem lens: it queried a column that does not exist and read the previous SIM epoch, so it has been hiding every order the live desk placed.

*Every figure below is read from the live endpoints, `logs/report.md` or the ledger; none is authored here
(invariant 7 / ADR-0016 — the scorer owns every number that gates money). The query output quoted at the
end is Postgres' answer to the fixed query, not my arithmetic.*

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `-$0.88`, **down** `-$2.07` on the last run. Realized is `-$0.04` (fees only); the
whole move is unrealized mark drift on two legs. Over the last three runs the book went `$0.00 → -$0.88`,
but that window straddles the SIM→LIVE epoch boundary, so the "3-run" figure is a fresh-book artefact, not
a drawdown.

**2. Risk.** Gross `$759.76`, net `$83.80`, gross **up** `+$1.25` — which is also pure mark drift: **no
order was placed in this window at all**. VaR95 `$8.23`, ES95 `$12.29`, breaker `halted: false`, nowhere
near it. Two positions: short 1 AAPL (`$337.98`) and the `0.001136` ES hedge (`$421.78`).

**3. Cause.** Last cycle's change (ADR-0117, the warm-restart seed off-by-one) scored **⚠️ MIXED — "no
material change (within noise band)"**, as predicted. Its own success check passed cleanly: every risk-cut
σ WARN this boot now reads a demand of `121` against a smaller supply (`109 of 121`, `86 of 121`,
`104 of 121`, `73 of 121`) — i.e. every remaining cold name is genuinely history-bound, and the
`n of n, and still cold` lines that were the bug printing its own diagnosis are gone, covariance included.
It cannot be credited or blamed for a dollar: it opened, closed and resized nothing.

**4. Danger.** The header flags DANGER (bleeding + exposure rising). **I checked it and it is not a danger
state.** Danger means the desk is adding risk into a loss; the desk added nothing — zero orders, and the
`+$1.25` "exposure rise" is the ES mark moving under a position that has not changed size since 13:40:54Z.
De-risking here would crystallise a loss and pay the spread to do it. I am not cutting on mark noise.

**Order post-mortem, and the honest change-vs-market split.** The window's PnL is **entirely market**.
AAPL fell `0.2715%` from its `338.90` fill and the short is a **winner** at `+$0.92`; ES fell `0.4147%`
from its `7456.67` fill and the beta-1.25 overlay leg is `-$1.76`. Two names that co-moved, the hedge
correspondingly losing a shade more than the short made — one hour of that says nothing about the hedge
ratio and I will not touch ADR-0040's beta on it. Nothing I have shipped opened, closed or resized either
leg. The desk is reduce-only because the edge gate went active on the first measured cost and now sees
what `signals_telemetry` sees: on LIVE data trend@225s has `118` resolved observations at `-1.11` avg bps
and reversion@225s has `58` at `-0.60` avg bps — **negative measured expectancy before costs**. It is
correctly refusing to pay to trade an unproven signal. Forcing it open is the one thing here that would
reliably lose money, so I left it alone. That leaves no money lever with a real, well-understood edge this
cycle, which is why I spent the cycle on the thing that has been blinding every cycle.

## What I fixed — the post-mortem lens has been dark for sixteen cycles

The loop contract names `turnover_cost_by_name` as its order-level post-mortem source. It has errored on
every report since 2026-07-26 with `column "qty" does not exist` — `fills` calls it `quantity`. Reading it
to fix that surfaced two worse faults in the same lens:

- **It reported share count, not money.** `sum(abs(qty))` under a header saying *turnover cost*. A futures
  fill of `0.001136` lots is not `0.001136` dollars of anything; turnover is `quantity × price ×
  contract_multiplier`, and the multiplier is reference data. Now joined from `instrument`, never assumed.
- **`recent_orders` was hardcoded to `feed_mode='SIM'`.** The desk went LIVE. So the lens the contract
  tells me to attribute PnL with has been showing me *yesterday's sim epoch* — 60 rows of AAPL/JPM churn
  from 2026-07-27 — while the two orders that actually built today's book were invisible in it. That is
  also an invariant-8 violation in the report itself (`fills_by_day` was pooling 2,770 sim fills with the
  live ones on the same date row). All three are now scoped to the mode of the most recent row.

Verified against the live database: the fixed lens returns the live epoch, and picks the ES contract
multiplier up correctly (`ES 1 fill, 0.001136 qty, $423.54 turnover, 0.20 fee_bps` — versus AAPL's
`1.00 fee_bps`), which is the first time this cycle's actual trading cost has been visible at all.

**The honest limit.** This is a **report-only** change — `scripts/system-report.py`, three SELECTs. It
moves no dial, touches no money path, and is invisible to `scripts/score-change.py`, which reads the live
endpoints; it cannot flatter the score and I expect **MIXED** again on a reduce-only book. No ADR: fixing a
broken diagnostic query is small, local and reversible. No Java or Gradle file is touched, so there is no
build to break and I did not spend a third of the cycle running a suite that covers none of it; the
verification that matters is the one above, run against the live Postgres. The thing to check next cycle is
simply that `turnover_cost_by_name` and `recent_orders` in `logs/report.md` show **this** epoch — at which
point cost-per-name finally becomes an input to the loop instead of an error row.

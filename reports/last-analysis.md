The edge gate is one hour from levering the whole book on a single hour of market — the t-statistic it trusts treats 23 names called in one 300ms burst as 23 independent draws, so the standard error behind every risk decision is 2.23× too small; fixed the statistic before it opened the gate (ADR-0077).

## Situation triage (live endpoints, read first)

**1. Money.** Total PnL reads `-867.73` on `/api/risk` `.total`, unchanged run-over-run and across the
last three runs (`run-status.json`: `pnl_growth_pct` 0.0 vs `pnl_target_pct` 1.0, `stale`, `underwater`).
Not bleeding — frozen. It is frozen because it is entirely *realised*: unrealised PnL is `0.00`.

**2. Risk.** Gross exposure `0.00`, net `0.00`. The book is **completely flat** — no positions in any
name, nowhere near the drawdown breaker, VaR reports no positions. There is no risk because there is no
book, and equally no forward earning power. The last fill was ~2h ago.

**3. Cause.** Last cycle's ADR-0076 (diversification multiplier on the weights actually used) scored
⚠️ MIXED "no material change" — inert, exactly as it predicted of itself, because nothing trades. It is
the **eighth** consecutive cycle inside the fusion/gate/weights subsystem to score no-material-change.
Not the culprit; the culprit is that `/api/fusion/targets` reports `edgeGate.mayIncrease = false`, so all
23 planned names carry `deltaQty = 0`.

**4. Danger.** Not the bleeding-plus-rising-exposure danger state — the opposite. But there *is* a live
danger one cycle out, and it is why this is not a no-change cycle: **the gate is about to open on
evidence that does not exist.**

## 5–7. Order post-mortem, memory, and change-vs-market

The window's orders are two 1-share ALPHA buys with matching fractional HEDGE trims, all under the
reduce-only gate — **no trigger opened a position**. With a flat book and zero unrealised PnL, neither
the market nor my last change had anything to act on; the honest split is that there is no market
component and no change component, because there was no exposure. Nothing to credit or blame.

So I went to the evidence the gate consumes and grouped the resolved observations by entry time — the
follow-up the previous cycle's finding explicitly left open:

- `trend`'s 115 resolved observations are **five** hourly bursts of 23 names.
- `reversion`'s 23 — the source the gate is waiting on, and the only positive one — are **one** burst,
  entered 18:08:17 and resolved 19:08.
- `momentum`, `social` and `mean-reversion` are genuinely staggered, one name at a time.

`stdErrorBps` was `σ/√resolved`. For the burst emitters that divides by √23 a dispersion measured across
*names within the same hour*. On the live `trend` cohorts the Fama–MacBeth standard error is
`12.8985/√5 = 5.7684 bps` against the `27.7797/√115 = 2.5905 bps` the desk used — **2.23× too small**,
turning t = −2.31 into t = −5.25. For `reversion` there is no standard error to form from one draw, yet
`σ/√23` manufactured one, and with it t = 1.54. On the next resolution the raw count crosses
`minSample = 30` and the naive t reaches ≈2.17 — **the gate opens and the desk levers into the full
23-name target book on two hourly snapshots.** The ledger already records that exact failure once
(ADR-0067's continuous risk appetite: gross $0 → $43,624, no PnL gain, ❌ BAD, auto-reverted), and the
pending book is several times larger.

## Decision

Ship the statistic, not another gate refinement — the previous cycle's own rule was that the gate was
never the binding constraint, the evidence was. `SignalScoring` now groups resolved observations into
emission cohorts by entry time and estimates expectancy across cohorts (Fama & MacBeth, *JPE* 1973):
point estimate = mean of cohort means, `stdErrorBps = sd(cohort means)/√B`, and **zero when B < 2** — one
cross-section is not evidence however wide it is. Both consumers already read that correctly: the edge
gate requires `se > 0`, and `TelemetryWeights` reads `t = 0` as `Φ(0) = ½`, a neutral weight. A staggered
emitter has one observation per cohort and comes out byte-identical to today; equal-sized cohorts leave
the point estimate exactly unchanged. `resolved`, `minSample` and `tHurdle` are untouched, so volume and
significance keep their separate jobs. Proposed ADR-0077 in the same commit.

**This keeps the book flat this cycle, and I want to be plain about that trade.** It is not a stall: it
is the change that decides whether the imminent gate opening is trustworthy, and its error direction is
one-way — merging observations can only widen the standard error, never narrow it. The honest cost is
that evidence now accrues at one independent draw per source per hour, which is slow. **The next lever
is the emission rate, not the gate:** overlapping cross-sections on a ~10-minute cadence against the same
1h horizon multiply independent draws per hour, with a Newey–West/Hansen–Hodrick correction for the
induced overlap. That must come after this change — raising the rate on the i.i.d. estimator would have
inflated significance faster still.

The hedge has never once measured whether it hedges anything — it sizes itself from assigned betas, its ρ² reads null, and it is the only book losing money; it now measures its own effectiveness on the mark stream and unwinds when that measurement says no.

## Situation — read off the live endpoints; every figure below is quoted, none computed here

**1. Money.** Total PnL `$187.36`. The report's SITUATION header puts the window at `+26.02` and the
last three runs at `+146.27`. `run-status.json` reports `on_track` true with `pnl_growth_pct` far ahead
of the 1% target, and `underwater` / `stale` both false. **The book is not bleeding.**

**2. Risk.** Gross exposure `$24,662.35` against net `$4,586.05` — the header flags **EXPOSURE RISING**
at `+4,030.30` on the window and `+1,626.84` over three. VaR95 `$240.67` / ES95 `$322.81` on
`$24,648.21` covered; the firm drawdown breaker is `halted: false` and nowhere near tripping. Regime
`CALM`, trend `CHOP`. Rising exposure against rising PnL is the ⚠️ MIXED shape, not a danger state.

**3. Cause.** Last cycle's change (the reversion sensor's Donchian window doubled) scored **❌ BAD** and
the scorer already reverted it — the culprit behind the exposure leg was gone before I started, so no
de-risk override applies. **4. Danger.** No: PnL is rising, the breaker is far, and the one flag is
attached to an already-reverted commit.

**Change vs market — what I can and cannot attribute.** The window's `+26.02` is not separable into
market and change from these numbers: the reverted commit had re-scaled every planned name, so there is
no untouched control group to read the market off, and I claim credit for none of it. What *is*
separable is the hedge, because it is its own book with its own order flow: `HEDGE` sits at
**`-$382.08`** (`realizedPnl -378.27`, `feesPaid 36.89`) against `ALPHA +$192.45` and `MACRO +$376.99`.
No loop change has ever touched the hedge advisor, so that line is neither credit nor blame for any
change scored so far — it is a standing structural drag, and it is roughly the size of both strategy
books' gains put together.

## Diagnosis — the mechanism, not the symptom

`/api/hedging` is the tell, and it has been saying the same thing every cycle: `"effectiveness": null`,
`"grossSigmaUsd": null`, `"tier": "STRUCTURAL"`, rationale `"(assigned betas, no covariance)"`. The
advisor's statistical tier (ADR-0038) computes the Ederington minimum-variance ratio *and* the measured
ρ² it refuses to hedge below; the structural tier (ADR-0040) is the history-free floor beneath it,
`Σβᵢ·Eᵢ` from assigned fundamental betas with effectiveness **asserted**. Two things pin the desk on
that floor forever:

1. **Coverage.** The statistical tier reads the `daily_close` covariance, which ADR-0073 scopes by feed
   mode — and it covers nothing this desk holds. The parametric VaR built on the identical matrix says
   so out loud: `coveredExposure 0.00`, `skippedExposure 24,648.21`. So `HedgeMath.betaHedge` returns
   empty for every candidate, ρ² is never computed, and ADR-0042 proxy selection never runs. Same gap
   ADR-0089 found and fixed for the fusion sizing controls — the hedge is the consumer it missed.
2. **Precedence.** Even when the measurement *does* exist and fails the ρ² floor, `sizeTarget` falls
   through to assigned betas and hedges anyway. "Measured not to hedge this book" is answered with
   "assumed to hedge it". `effectiveness-floor=0.25` is not mistuned — it is **unreachable by
   construction**, which is exactly the ADR-0094 lesson (*a control is dead until you have seen it
   fire*) applied to the one gate that governs whether the hedge should exist at all.

Meanwhile that unmeasured hedge carries `$2,794.84` of the firm's `$24,662.35` gross and churns: 310 ES
fills, with the recent order flow alternating BUY/SELL on the same tiny clip every couple of minutes. It
is charging real exposure and real spread for a variance reduction nobody has ever checked.

## The change — ADR-0095 (Proposed, same commit)

`HedgeStreamCovariance` reuses ADR-0089's `StreamCovariance` to measure the hedge axis on the stream it
actually trades — one synchronised snapshot per hedge cycle of every equity name carrying exposure plus
every proxy candidate, seeded on first sight from the durable mark history on a shared bucket grid
anchored on the feed's provider clock. `sizeTarget` becomes three tiers of evidence, strongest first:
daily-close → mark-stream (consulted only when the daily estimate cannot measure the book at all) →
assigned betas. And **a measurement that says no is an answer**: below the floor the target is flat and
the residual hedge unwinds through the ordinary delta path, instead of being carried on assumption.

Interval-return units are admissible here because both quantities the advisor takes from Σ —
`E_F* = −(ΣᵢEᵢΣ[i,F])/Σ[F,F]` and `ρ² = (ΣᵢEᵢΣ[i,F])²/((ΣᵢΣⱼEᵢEⱼΣ[i,j])·Σ[F,F])` — are homogeneous of
degree **zero** in Σ, pinned as a test on a worked example. The two figures that are *not* scale-free,
`grossSigmaUsd` and `residualSigmaUsd`, are withheld from a stream-sourced proposal rather than
labelled as a daily σ.

No money number is authored: the floor value, the no-trade band, the churn guard, the switch hysteresis
and the order path are untouched, and `jethro.hedge.stream-covariance.enabled=false` restores today's
behaviour exactly. The pre-trade guardrail and the firm drawdown breaker are untouched.

**Expected next.** Either the proxy explains the book — the hedge stays, sized on a measured β rather
than an assigned one, and `/api/hedging` finally reports a real ρ² — or it does not, the hedge unwinds,
firm gross falls by roughly the ES leg, and the `HEDGE` P&L line stops deepening. Both directions move
total PnL per unit of total gross the right way. If gross falls and PnL does *not* improve, the ES short
was doing real work and the next lever is the floor's discreteness (deferred-register row), not a
re-attempt here.

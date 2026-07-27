# ADR-0095: The hedge measures its own effectiveness on the stream it trades

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, risk, hedging, sizing

## Context

The firm equity hedge is the only book losing money. It carries roughly a ninth of firm gross exposure
in short ES against the strategy book's net equity, and its P&L line is negative and getting more so
run over run while the strategy books are positive. That alone is not a verdict — a hedge is *supposed*
to cost money when the market it is short of rises; the question is whether it is buying the variance
reduction it charges for. The advisor's own answer to that question is the field `effectiveness` on
`/api/hedging`, and it reads **`null`**, next to `"tier": "STRUCTURAL"` and the rationale
`"(assigned betas, no covariance)"`.

`HedgeAdvisor` has two tiers. The **statistical** tier (ADR-0038) computes the Ederington
minimum-variance ratio and its measured ρ² from an EWMA return covariance, and refuses to hedge below
`jethro.hedge.effectiveness-floor` — "a weak proxy is never dressed up as a real hedge". The
**structural** tier (ADR-0040) is the history-free floor beneath it: `Σβᵢ·Eᵢ` from *assigned
fundamental betas*, with effectiveness asserted rather than measured. Two things make the first tier
unreachable and the second permanent:

1. **Coverage.** The statistical tier reads the `daily_close` EWMA covariance. Under ADR-0073 that
   series is feed-mode scoped, and a name enters it only after enough admissible consecutive sessions
   *in the running mode*. On this stream it covers nothing the desk holds — the parametric VaR built on
   the identical matrix reports its **entire** exposure as skipped. `HedgeMath.betaHedge` therefore
   returns empty for every candidate, ADR-0042 proxy selection never runs, and ρ² is never computed.
   This is exactly the gap ADR-0089 found and fixed for the fusion sizing controls; the hedge is the
   consumer it did not reach.

2. **Precedence.** Even when the statistical tier *does* measure the book and every candidate fails the
   ρ² floor, `sizeTarget` falls straight through to the structural tier and hedges on assigned betas
   anyway. So "measured not to hedge this book" is answered with "assumed to hedge it". The
   effectiveness floor is unreachable by construction — not tuned wrong, **inert**. That is the sibling
   of the ADR-0094 lesson (*a control is dead until you have seen it fire*) and of ADR-0092's clip-rate
   rule: check a gate's bind rate, not its configured value.

The consequence is a hedge that is sized, cycle after cycle, from a static refdata number nobody has
checked against the stream, while carrying real gross exposure and paying a real spread on every
rebalance. Whether the assigned betas happen to be right is beside the point: the desk has no evidence
either way, and the control written to demand that evidence cannot speak.

## Decision

**Give the hedge axis a covariance measured on the mark stream, and let a measurement that says "no"
be an answer rather than a gap.**

1. **A second statistical basis.** `HedgeStreamCovariance` reuses ADR-0089's `StreamCovariance` — one
   synchronised snapshot of every axis member plus every proxy candidate per hedge cycle, EWMA of log
   return cross-products, nothing reported until a pair is warm — and republishes it as an immutable
   `CovMath.Covariance`. It is seeded on first sight from the durable mark history on one shared bucket
   grid (`SensorWarmup.jointSeedSamples`, ADR-0071/0089), anchored on the feed's provider clock, because
   its warm-up outlasts a redeploy.

2. **Three tiers of evidence, strongest first.** `STATISTICAL` (daily-close, past the ADR-0041 session
   gate) → `STATISTICAL-STREAM` (consulted only when the daily estimate cannot measure this book at
   all) → `STRUCTURAL` (assigned betas). The ADR-0041 gate counts *sessions* and is not applied to the
   stream estimate, which counts sampling intervals and enforces its own warm-up before a pair speaks.

3. **A measurement that says no is an answer.** When an estimate can measure the book and no candidate
   clears the ρ² floor, the target is **flat**: the residual hedge unwinds through the ordinary
   ADR-0039 delta path. The structural tier stays what ADR-0040 built it for — the answer when there is
   *no* measurement — and stops overriding one.

Nothing else moves. The floor value, the no-trade band, the churn guard, the switch hysteresis, the
proxy universe and the order path are untouched; `jethro.hedge.stream-covariance.enabled=false`
restores today's behaviour exactly.

### Why an interval covariance may size a hedge

The advisor takes exactly two things from Σ, and both are **homogeneous of degree zero** in it. With
book exposures `Eᵢ` and proxy `F`:

```
E_F* = −(Σᵢ Eᵢ·Σ[i,F]) / Σ[F,F]                      minimum-variance hedge notional
ρ²   = (Σᵢ Eᵢ·Σ[i,F])² / ((ΣᵢΣⱼ EᵢEⱼ·Σ[i,j]) · Σ[F,F])  its measured effectiveness
```

Replace `Σ` by `k·Σ` for any `k > 0`: `E_F*`'s numerator and denominator both scale by `k`; ρ²'s
numerator scales by `k²` against a denominator that also scales by `k²`. Both are unchanged. So the
sampling period the covariance was measured over cannot move the hedge quantity or the floor test —
which is what makes ADR-0089's deliberately unrescaled interval-return units admissible here, exactly
as it made them admissible for the two fusion controls.

Worked, with a two-name book and per-interval Σ (`E_A = +$100,000`, `E_B = −$50,000`; proxy `F` at
5,000 × 50 = $250,000/contract; `Σ[A,A]=4e-6`, `Σ[B,B]=1e-6`, `Σ[A,B]=1e-6`, `Σ[A,F]=2e-6`,
`Σ[B,F]=1e-6`, `Σ[F,F]=1e-6`):

```
Cov(P&L,F) = 100,000·2e-6 − 50,000·1e-6                       = 0.15
Var(P&L)   = 1e10·4e-6 − 2·5e9·1e-6 + 2.5e9·1e-6              = 32,500
E_F*       = −0.15 / 1e-6                                     = −$150,000
qty        = −150,000 / 250,000                               = −0.600000  (SELL 0.6 F)
ρ²         = 0.15² / (32,500 · 1e-6) = 0.0225 / 0.0325        = 0.692308
```

Scale every Σ entry by 100 (a sampling period 100× longer): `Cov = 15`, `Var(P&L) = 3,250,000`,
`Σ[F,F] = 1e-4` → `E_F* = −15/1e-4 = −$150,000` and `ρ² = 225/325 = 0.692308`. **Identical.** But
`√Var(P&L)` moves from $180.28 to $1,802.78.

That last line is the one thing that does not carry: `grossSigmaUsd = √Var(P&L)` and
`residualSigmaUsd = grossσ·√(1−ρ²)` are absolute USD σ *per sampling interval*, not per day. They are
display-only — nothing sizes off them — so a stream-sourced proposal reports them as **absent** rather
than label a per-interval σ as a daily one. Stating a risk number in a unit it was not measured in is
the invented-number failure this repo has already paid for once.

## Consequences

- The hedge sizes on a β measured against the stream it trades, and the desk finally learns its own ρ².
  If the proxy explains the book, the hedge stays and is better sized than an assigned beta; if it does
  not, the hedge unwinds and the firm stops carrying proxy exposure that is not a hedge. Both directions
  move the objective — total PnL per unit of total gross exposure — the right way.
- ADR-0042 proxy selection (ES vs NQ by measured ρ²) becomes reachable for the first time on this feed.
- The floor is a real gate now, so `jethro.hedge.effectiveness-floor=0.25` starts gating money. Its value
  is unchanged and its provenance is unchanged (ADR-0038); what changes is that it can fire.
- **A hedge can now be switched off by a statistic.** That is the intent, and it is bounded: the axis
  cannot go long the proxy against a long book, an unwind is always executable at any book size
  (ADR-0069), and the pre-trade guardrail and firm drawdown breaker are untouched.
- During warm-up — a cold history and no seed — the structural tier carries the book exactly as today.
- One estimator per consumer: the fusion tick and the hedge cycle each own theirs, each thread-confined,
  because they sample on different grids and a covariance stitched across grids measures neither.
  Promoting `StreamCovariance` out of `io.jethro.app.fusion` into `risk-pnl` alongside `CovMath` is the
  right home once there is a third consumer; tracked in the deferred register rather than done here.

## Alternatives considered

- **Rescale the stream covariance to daily units.** Would let the σ figures survive, but requires
  asserting how many sampling intervals make a trading day — a convention this code has no business
  inventing, and one that would silently move a risk number. ADR-0089 refused it for the same reason.
- **Lower `min-covariance-days` so the daily series qualifies.** Does nothing: the problem is that the
  names are absent from the matrix, not that the matrix is young.
- **Turn the hedge off.** Trades a measurable question for an unmeasured answer, and leaves the firm
  running naked equity beta with no statement about it either way.
- **Share the fusion estimator.** Two consumers on different cadences would have to sample one series
  off-grid; the sample cost here is a handful of names per five seconds.

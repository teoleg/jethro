# ADR-0067: Size the fusion book to the measured evidence, and stop only on measured harm

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** strategy, risk, execution

## Context

ADR-0064 gated risk-*increasing* fusion trades on a significance test: for each source, compare
measured expectancy to measured round-trip cost, and permit opening a position only if some source
clears `t = (mean − cost)/SE ≥ 2`. It claimed two properties that made it safe to run unattended — it
is *monotone* (can only remove trades) and *self-healing* ("it opens by itself the moment a source
earns its cost"). The first is true. **The second is false, and the platform has been sitting in the
consequence.**

The desk is currently flat: the fusion loop plans a full target book across 23 names, and every
`deltaQty` is zero. It has been reduce-only continuously, so total PnL cannot move and the owner's
growth target (ADR-0063) is structurally unreachable — not because there is no edge, but because the
gate cannot open. The reason is an identity ADR-0064 did not draw out: `t = ((mean − cost)/σ)·√n`, so
`t ≥ 2` demands the source have **realised a Sharpe of 2/√n over the sample window**, on raw
single-name directional returns at horizon. Against this platform's own measured dispersion — LIVE-mode
momentum sits near 22 bps of per-observation standard deviation, the wider sources far above that —
clearing the bar at the configured `min-sample` of 30 needs a mean hourly return of order 25–30 bps.
No real signal produces that; a genuinely good trend system contributes well under 1 bp per hour. The
gate is therefore not "the permissive end of the defensible range" as ADR-0064 described it — it is
**unsatisfiable by construction**, and the self-healing it promised never arrives.

Underneath the arithmetic is an inverted null. ADR-0064 required *proof of edge* as a precondition for
the trading that generates the proof, and then treated the two distinct states "we have measured harm"
and "we have measured nothing" identically — a full stop for both. Its own worked example concedes the
distinction and then discards it: a t of −1.095 is "not evidence of an edge, **and not evidence of a
reliable anti-edge either**", yet it stops the desk exactly as hard as a demonstrated loser would. For
a desk whose thesis is risk-managed trend-following — a strategy supported by decades of published
out-of-sample evidence (Moskowitz, Ooi & Pedersen, "Time Series Momentum", *JFE* 2012; Hurst, Ooi &
Pedersen, "A Century of Evidence on Trend-Following Investing", 2017) — "prove it locally on 30
one-hour observations or hold no position" is the wrong test at the wrong sample size.

## Decision

We will replace ADR-0064's binary switch with a **continuous risk appetite**: the fusion layer bets in
proportion to the probability that its best-evidenced source is right, and stops entirely only when a
source's expectancy is measured to be significantly *below* its cost.

Per cycle, over the same feed-mode-scoped telemetry and the same measured round-trip TCA cost:

```
evidence_s  = resolved_s ≥ minSample  ∧  stdError_s > 0     a sample thin or degenerate is not evidence
t_s         = (avgReturnBps_s − roundTripCostBps) / stdError_s
tBest       = max over sources WITH evidence of t_s          (0 when none has evidence yet)
appetite    = Φ(tBest)                                       Φ = standard normal CDF
mayIncrease = tBest > −tHurdle
```

`appetite ∈ [0,1]` multiplies `unit-notional-usd`, the per-name cash-at-risk the plan plays for. It is
applied **before** planning, so deltas are computed against the size we intend to hold rather than
creeping to full size over successive cycles. Three properties carry the safety:

- **It carries no new dial.** Φ(tBest) is the posterior probability, under a diffuse prior, that the
  cost-adjusted expectancy is positive. With no evidence either way it is Φ(0) = **1/2 exactly** — half
  the configured notional — by symmetry, not by anyone's choice. `min-sample` and `t-hurdle` are the
  existing ADR-0064 statistical conventions, unchanged. This is fractional-Kelly sizing: stake in
  proportion to confidence (Thorp, "The Kelly Criterion in Blackjack, Sports Betting and the Stock
  Market", 2006).
- **It is still monotone against the configuration.** The appetite is clamped to [0,1], so it can only
  ever *shrink* the notional Oleg set — never grow it. `unit-notional-usd` remains the ceiling, and the
  ADR-0018 pre-trade guardrail and ADR-0017 firm breaker remain the floor beneath all of it.
- **ADR-0064's protection is kept where it was earned.** A source measuring `t ≤ −tHurdle` is
  demonstrated harm, and there fusion is still fully reduce-only: positions may be cut or closed, none
  opened or grown. What changes is only that an *un*measured source no longer gets the same treatment
  as a *measured loser*.

Worked example: with no source at `min-sample` yet, appetite = 0.500000 and a $50,000 unit notional
plans $25,000.000000 per name at a typical forecast. A source that later measures t = +4.0 gives
Φ = 0.999968 → $49,998.400000. One measuring t = −1.0 gives Φ = 0.158655 → $7,932.750000. At t = −2.0
the desk is reduce-only regardless of the residual 0.022750.

## Alternatives considered

**Keep ADR-0064 and lower `t-hurdle` / `min-sample`.** Rejected: it treats an unsatisfiable *design* as
a tuning problem. The bar scales as √n against a Sharpe the signal does not have, so any hurdle low
enough to ever open is low enough to open on noise — the binary shape is what fails, not its setting.

**Test the portfolio's realised Sharpe instead of per-signal expectancy.** This is the statistically
right unit — it is what the desk actually earns, and it nets overlapping positions correctly. Deferred,
not rejected: it needs a clean per-strategy realised-return series the platform does not yet keep, and
it has an even worse small-sample problem at this cadence. Revive it once the equity curve carries
enough independent observations to support a standard error.

**Amortise cost over the holding period before comparing.** ADR-0064 charges a full round trip against
a single one-hour observation, which is a units mismatch. Deferred rather than adopted here because the
correction runs the *wrong* way for permissiveness: with a 30-second re-plan cadence the desk turns over
far more often than once per horizon, so an honest amortisation *raises* the hurdle. That is a real and
separate problem — cadence versus information horizon — and it deserves its own change, not a rider.

**Do nothing and escalate to a live feed.** Rejected: the block is in our code, not in the feed. A
frozen book would be exactly as frozen on live data.

## Consequences

- **Positive:** the desk can hold risk again, so the improvement loop has a measurable vector instead of
  an unbreakable zero. Size now tracks evidence continuously in both directions — a decaying source
  shrinks the book smoothly rather than falling off a cliff, and a strengthening one earns its way back
  toward the configured size without a switch anyone has to remember to flip.
- **Negative — and this is the real cost:** the book goes from $0 gross to a genuinely risk-bearing size
  on evidence that is, today, *absent* rather than *favourable*. Half-size on no information is a
  defensible prior for a trend desk, but it is a prior, and if the sim has no trend edge to capture this
  will lose money before the telemetry accumulates enough to shrink it. That is bounded by the ADR-0018
  guardrail ($500k ALPHA gross, $1.5M firm) and by the loop's own scorer, which will revert this if the
  vector regresses.
- **Negative:** `roundTripCostBps` is still a single fill-weighted average applied to the whole universe.
  It is currently dominated by one wide-spread name, so a cheap name (EURUSD, ~0.9 bps round trip) is
  judged against a hurdle set by an expensive one. This ADR does not fix that; it inherits it.
- **Follow-ups:** (1) per-instrument cost hurdle from the quoted spread rather than one blended TCA mean;
  (2) reconcile trading cadence with the signal horizon — a 30s re-plan against a 3600s horizon; (3) a
  firm-level risk budget, so growing breadth (7 names → 23 with ADR-0066) stops silently multiplying
  gross exposure. All three are in `docs/deferred-register.md`.

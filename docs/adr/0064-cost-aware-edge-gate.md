# ADR-0064: Gate risk-increasing fusion trades on measured edge net of measured cost

- **Status:** Proposed
- **Date:** 2026-07-25
- **Deciders:** Oleg
- **Tags:** strategy, risk, execution

## Context

ADR-0062 decided that execution must be gated on **measured live** edge and not only on a simulator
out-of-sample backtest, and left the design of the gate open ("a rolling window + minimum-sample design
so it neither over-fits to a lucky streak nor never fires"). It was never implemented. Its own
follow-up list named the other half of the problem: "recalibrate the turnover controls that already
exist for the churn half (ADR-0055 no-trade band width, ADR-0059 conviction floor) **against live
cost**." This ADR implements both halves as one control, at the sole order origin.

What the running platform shows is the case ADR-0062 anticipated. The fusion loop re-plans on a fixed
cadence and trades a fraction of the gap to a target derived from forecasts whose **measured**
per-signal expectancy is negative and, once dispersion is accounted for, statistically indistinguishable
from zero — for both deterministic sources, over hundreds of resolved observations. Meanwhile the desk's
own TCA measures a real, repeatable implementation shortfall on every fill, and the strategy book has
turned over many multiples of its capital while its realised P&L bled. **Turnover is a certain cost;
edge is an uncertain benefit.** By Grinold's Fundamental Law (IR ≈ IC·√breadth, *JPM* 1989), routing a
zero-or-negative IC across breadth scales the loss rather than diversifying it, so the response the
evidence supports is *trade less*, not *trade differently*.

Two existing controls sit close to this but cannot answer it. The ADR-0055 no-trade band is a fraction
of |target| — a *shape* control that knows nothing about what execution actually costs. The ADR-0059
conviction floor asks whether the *forecast* is strong, not whether the *source* has ever been right.
And the ADR-0055 telemetry weights read hit-rate alone: a source whose wins and losses are equally
frequent but whose losses are larger scores as a coin flip and keeps its allocation. None of the three
compares expectancy to cost.

## Decision

We will add a **cost-aware edge gate** to the fusion loop (the sole order origin, ADR-0055/0059) that
decides one thing each cycle: **may the platform increase risk, or may it only reduce it?**

For each source, over the rolling telemetry window (feed-mode scoped, invariant 8):

```
netBps_s = avgReturnBps_s − roundTripCostBps        expectancy the desk actually keeps
t_s      = netBps_s / (stdReturnBps_s / √n_s)       is that surplus distinguishable from zero?
passes_s = n_s ≥ minSample  ∧  t_s ≥ tHurdle
```

`roundTripCostBps` is **measured**, not assumed: twice the fill-weighted mean implementation-shortfall
slippage from the platform's own TCA rows for the running feed mode. `avgReturnBps` and `stdReturnBps`
come from the ADR-0055 phase-1 signal telemetry; this ADR adds the dispersion, without which a mean is
not evidence.

Risk may be **increased** when any source passes — one demonstrated edge justifies putting on risk, and
the existing per-source weights then decide how much each contributes. When none passes, fusion is
**reduce-only**: each planned delta is projected onto the part that does not increase |position|
(trim, close, or nothing; a sign flip is truncated at flat). Cutting risk is *always* permitted.

Three properties make this safe to run unattended:

- **It is monotone.** The gate can only ever remove trades the planner already wanted. It never adds
  one, never enlarges one, and never reverses a sign. Turnover and gross exposure can only fall.
- **It is self-healing.** Both inputs are re-measured every cycle from live data. The gate opens by
  itself the moment a source earns its cost and closes again if that edge decays. It is not a switch
  anybody has to remember to flip back.
- **It sits strictly above the deterministic floor.** The pre-trade guardrail and the firm drawdown
  breaker still decide what is *safe*; this only decides what is *worth paying for*.

Worked example, using the shape of the observed telemetry. A source with mean −51.26 bps and sample
dispersion 854.12 bps over 233 resolved observations has a standard error of 854.12/√233 = 55.96 bps.
Against a 10 bps round trip its surplus is −61.26 bps, i.e. t = −1.095 — not evidence of an edge, and
not evidence of a reliable *anti*-edge either. It does not pass, so it may not open a position; it
keeps the ones it has and may cut them. Contrast a source with mean +30 bps, dispersion 60 bps, n=144:
standard error 5 bps, surplus +20 bps, t = 4.0 → it passes and the gate opens for everyone.

The dials are `min-sample` and `t-hurdle`. Both are **statistical conventions, not money numbers**:
t = 2.0 is the ordinary ~95% two-sided bar, and Harvey, Liu & Zhu (*RFS* 2016) argue for ≥3.0 once
multiple testing is counted, so 2.0 is the permissive end of the defensible range. They are set in
`application.properties` with that provenance and are Oleg's to move.

## Alternatives considered

- **Gate on the sign of the measured mean (no significance test).** Rejected: with per-signal dispersion
  an order of magnitude above the mean, the sign of a window is noise. The book would flip between
  trading and not trading on samples that carry no information — the fastest known way to overfit a
  live control (Bailey, Borwein, López de Prado & Zhu, *J. Comp. Finance* 2017).
- **Full ADR-0062 gate: route nothing at all unless a `(name, algo)` proves positive edge.** Deferred,
  not rejected — this is the stricter end-state and remains ADR-0062's decision. Reduce-only is its
  monotone first step: it stops the platform *adding* risk it cannot justify while leaving the existing
  exit paths to unwind what is already on, so the change is attributable and reversible on its own.
- **Widen the no-trade band / raise the conviction floor instead.** Rejected as the primary lever: both
  are shape parameters that would need hand-chosen values with no measurement behind them (exactly the
  invented-number failure CLAUDE.md warns about), and neither ever consults whether the source has been
  right. They remain useful *below* this gate.
- **Down-weight losing sources rather than gating them.** Rejected on the Fundamental Law: with IC ≤ 0
  a smaller size still loses, just more slowly, and it still pays full round-trip cost per trade.
  Weighting decides *relative* allocation among sources worth trading; it cannot answer whether *any*
  are.
- **Invert the losing signals (trade them contrarian).** Rejected: the measured expectancies are not
  significantly negative either, so an inversion would be fitting noise with the sign flipped — and it
  would pay the same cost to do it.

## Consequences

- **Positive:** the platform stops paying measured execution cost to chase forecasts with no measured
  edge; gross exposure and turnover fall while the evidence stays absent; the previously-ignored TCA and
  signal-telemetry measurements become an actual control; the gate's verdict and its per-source
  arithmetic are surfaced on `/api/fusion/targets` so an operator can see *why* the book is not trading.
- **Negative:** with today's telemetry no source clears the hurdle, so the expected near-term state is a
  book that only reduces — that is correct, but it means "few trades" is the intended behaviour and not
  a fault to be debugged. If a real edge exists but is small relative to its dispersion, the gate will
  refuse it until the sample grows (a deliberate type-II bias: the cost of missing a marginal edge is
  bounded, the cost of trading a phantom one is not). The measured cost currently counts price slippage
  only — commissions are real and additional but are stored as cash without the contract multiplier
  needed to express them in bps, so the hurdle is a documented lower bound (deferred register).
- **Follow-ups:** add the commission leg to the measured cost once the TCA row carries notional; a
  per-instrument rather than desk-wide cost, so a cheap name is not held to an expensive name's hurdle;
  and the stricter ADR-0062 per-`(name, algo)` positive-edge gate once this one has run long enough to
  show how often it opens.

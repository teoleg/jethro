# ADR-0070: A continuous mean-reversion sensor (range position) as a fusion forecast source

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** strategy, signals, fusion

## Context

The fusion layer is the sole order origin (ADR-0055/0059), and it is currently unable to open a
position at all: the ADR-0064 cost-aware edge gate is reduce-only because **no source's measured
expectancy beats measured execution cost with significance**. That is not a marginal reading. Every
routed source is measured *negative* on this stream, and the best-sampled one decisively so — the
`trend` source's realised expectancy over its resolved observations is materially below zero at a
t-statistic several standard errors negative, with `momentum` and `social` negative alongside it. The
gate is doing exactly what ADR-0064 designed it to do, and the desk is flat as a result.

Three independent measurements agree on *why*, and they are not the same data:

1. **The regime detector** (ADR-0044, price-derived): breadth reads CHOP, with per-name Kaufman
   efficiency ratios clustered well below the CHOP band across most of the tradable universe.
2. **The walk-forward selector** (ADR-0027/0043, out-of-sample, cost-honest): on the names it can
   trade, momentum's median path PnL is deeply negative while mean-reversion's is strongly positive —
   and it selects mean-reversion wherever it selects anything on the chop names.
3. **The live signal telemetry** (ADR-0055 phase 1): the continuation family loses money per
   observation at the one-hour horizon.

The structural problem is that **every continuous forecast source the desk owns is a continuation
bet.** `trend` (ADR-0066) is a crossover, `momentum` is a breakout z-score, `social` is sentiment
follow-through. A range-bound tape is precisely the state in which that entire family is wrong
together — and it is the state the desk's own detector reports. The mean-reversion algo exists, but it
is a *threshold detector*: it is silent until a window move breaches its sigma threshold, so on a quiet
tape it contributes nothing to the fusion cross-section (a single telemetry observation against the
trend sensor's dozens) and therefore accumulates no evidence about itself either. The desk cannot even
*measure* whether reversion pays here, let alone trade it.

So the gap is not "the gate is too strict". The gate is right. The gap is that the desk has **no
continuous view for the regime it is actually in**, and no way to earn one.

## Decision

We will add **`reversion`**, a sixth fusion forecast source: a continuous, self-calibrating
**range-position** sensor that fades a name's stretch against its own realised range, weighted by how
*non*-directional that range is. Per instrument, on each cycle's fresh mark:

```
window = the last Nr price steps (Nr+1 prices) — the name's own realised range
hi, lo = max, min over the window                        the Donchian channel
pos    = (2·price − hi − lo) / (hi − lo) ∈ [−1, 1]       where in its own range we sit
er     = Kaufman efficiency ratio over the window ∈ [0,1] — trend vs chop
q      = −pos × (1 − er)                                 fade the stretch, weighted by CHOPPINESS
scale  = EWMA of |q| over span Nn                        what "typical" means ON THIS STREAM
score  = q / scale                                       E|score| ≈ 1 by construction
```

`score × TARGET_ABS` is published into the same `ForecastRegistry` every other source pushes to, and
each published reading is recorded as a directional call in the phase-1 signal telemetry — so the new
source is measured exactly like every other one and **must earn its own expectancy before the ADR-0064
gate lets it size anything**. It arrives with no evidence and no privilege.

### It is deliberately *not* the trend sensor negated

This is the load-bearing part of the decision. The telemetry says the continuation family loses money;
the naive response is to invert it. **We are explicitly not doing that** — inverting a losing signal is
the classic overfit, it makes the desk a contrarian bet on its own trend model, and it inherits every
one of that model's biases with the sign flipped. `reversion` is a different statistic with its own
economics:

| | `trend` (ADR-0066) | `reversion` (this ADR) |
|---|---|---|
| Statistic | smoothed **rate of change** (EWMA crossover) | bounded **level** vs realised extremes |
| Unbounded? | yes, normalised by step vol | no — `pos ∈ [−1,1]` by construction |
| Largest when | mid-move, crossover widest | at the edge of the range |
| Zero when | fast ≈ slow | mid-range |
| Quality weight | `× er` (rewards trending) | `× (1 − er)` (rewards chopping) |
| Hypothesis | the move carries on | the name reverts to its own centre |

The two sensors **partition** the same quality measure rather than competing for it, so on any given
name at most one of them speaks loudly. Their forecasts are combined and weighted independently by the
ADR-0067 expectancy weighting; they are never collapsed into one view.

### Why the efficiency-ratio weight, and why before normalisation

The edge of a range and the start of a breakout are the same observation; the efficiency ratio is what
separates them. As `er → 1` (a clean directional move) the weight goes to zero — the sensor refuses to
stand in front of a trend, which is the single most expensive way to trade mean reversion (Kaufman,
*Trading Systems and Methods*; the counter-trend/range literature is uniform on this). As `er → 0` (a
name oscillating with no net progress) the fade gets full weight.

It is applied **before** normalisation for the reason ADR-0066 already established: applied after, it
would be a blanket haircut shrinking every forecast toward zero (E[1 − ER] < 1) and quietly changing
what the ADR-0059 conviction floor means. Applied before, it is a **discriminator** — a stretch in
genuinely dead water outranks the same stretch in a name that is going somewhere — while the book's
overall scale stays the house convention.

### The scale estimator is warmed before it is trusted

Inherited verbatim from the ADR-0066 correction, because that defect was paid for once already: the
scale is the denominator of every reading, and an EWMA seeded from a single observation is an
arbitrary anchor, not a calibration — a name whose first reading lands in a quiet patch would report
every ordinary stretch afterwards as extreme and pin at the forecast cap, i.e. **maximum conviction at
the moment the sensor knows least**. So the first `Nn/2` readings accumulate into a running mean of
`|q|` and the sensor publishes **no view** until it has them.

### Dials

`range-span` and `normalisation-span` are **shape** dials — lookback lengths in evaluation cycles, not
money, risk or exposure numbers. Neither sizes anything: the output is normalised to the same expected
magnitude whatever they are, so changing them changes *which* stretches are faded, never how big the
book is. Book size stays governed by `unit-notional-usd`, the conviction floor, the edge gate and the
guardrail. Provenance, stated plainly:

- `range-span = 120` at a 10s cadence = a **20-minute range**, roughly one third of
  `jethro.signals.horizon-seconds` (1h) — the stretch should have room to revert *inside* the window
  its expectancy is measured over. **That ratio is mine and arbitrary**; tune it on measured edge.
- `normalisation-span = 240` (2× range-span, 40 min) mirrors the trend sensor's proportion at the
  shorter base: long enough that "typical reading" is a property of the stream, not of the stretch
  being measured. **Mine.**

## Consequences

**What this can do.** Give the fusion layer a calibrated view in the regime the desk is actually in,
and — if reversion genuinely pays here — accumulate the measured, cost-beating expectancy that is the
*only* legitimate way the ADR-0064 gate reopens. At ~one observation per name per horizon it reaches
the gate's minimum sample within roughly an hour of warm running, so the hypothesis gets tested fast
and cheaply.

**What this cannot do.** It cannot place an order, cannot relax a gate, and cannot increase exposure
while the gate is reduce-only — in that state it can only change how an already-held position is worked
down. The ADR-0064 edge gate, ADR-0059 conviction floor, ADR-0049 backtest-support veto and the entire
deterministic floor (pre-trade guardrail, firm drawdown breaker) remain upstream of every fill and are
untouched by this change.

**The risk, stated honestly.** Mean reversion's loss distribution is the mirror of trend's: frequent
small wins and rare large losses, and the large losses arrive exactly when a "range" turns out to have
been the start of a trend. The `(1 − er)` weight is the mitigation and it is not a guarantee. If the
sensor measures negative, the same gate that is currently holding the desk flat will keep it flat, and
the improvement-loop ledger (ADR-0063) will score this and revert it. That is the intended failure
mode: the source is falsifiable by construction.

**Feed-agnostic (invariant 9).** Every input is the instrument's own realised behaviour — its range,
its steps, its typical reading. No price level, no bps constant, no asset-class assumption, no sim
special-case. Exact decimal on prices (invariant 1); only dimensionless ratios become `double`. The
output is a conviction, never a size, price or PnL number (invariant 7 / ADR-0016).

## Alternatives considered

- **Invert the `trend` source** (weight it negatively on measured negative expectancy). Rejected — the
  textbook overfit, and it would make one model's biases the desk's whole thesis. A losing source is
  quietened toward the ADR-0067 floor, never inverted.
- **Lower the mean-reversion algo's sigma threshold** so the existing detector fires. Rejected as the
  primary fix: it trades *more* of an unmeasured signal rather than producing a continuous, measurable
  one, and threshold tuning is exactly the dial-chasing that produces overfit backtests.
- **Relax the edge gate** so something can trade. Rejected — the gate is the control that stopped the
  desk paying for edge it could not measure, and an earlier attempt to size on the *absence* of
  evidence was scored BAD and reverted. Earn the evidence; do not lower the bar.
- **A Bollinger/z-score band on the level** instead of the Donchian range. Reasonable and close; the
  range position was preferred because it is bounded by construction (the current price is inside the
  window that produced its own high and low), so a single outlier tick cannot run the reading away.

# ADR-0080: The desk's holding period is derived from the horizon its edge is measured over

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, cost, risk

## Context

Two numbers in this system describe the same trade and have never been reconciled.

**The measurement.** `jethro.signals.horizon-seconds = 3600`: every source's directional call is scored
by the realised return **one hour** later. That figure is what `SignalScoring` averages into
`avgReturnBps`, what ADR-0077's cohort standard error is taken around, and what the ADR-0064/0072/0075
edge gate tests when it asks whether a source's expectancy beats the desk's measured **round-trip**
execution cost.

**The policy.** `jethro.fusion.adjustment-rate = 0.5` at `jethro.fusion.interval-seconds = 30`: under
Gârleanu-Pedersen partial adjustment the desk closes half the gap to its target every 30 seconds, so
exposure e-folds toward target with time constant

```
tau = -interval / ln(1 - a) = -30 / ln 0.5 = 43.3 s
```

The desk holds a view for **43 seconds** while being graded on **3600 seconds** of it.

That is not a tuning disagreement, it is a dimensional error in the gate, and it runs one way only.
The gate credits one horizon of return and charges **one** round trip. A desk whose positions turn over
every 43 seconds pays roughly `3600 / 43.3 ≈ 83` round trips over the hour it is credited for. So a
source that clears the gate by a factor of ten is still losing money by an order of magnitude once the
costs the desk actually incurs are counted. The gate is not permissive because its hurdle is too low —
it is permissive because it is pricing a trade the desk does not make.

The live tape says exactly this. The last window in which the desk traded shows MSFT going
BUY 51 → BUY 66 → BUY 77 → SELL 26 → SELL 50 → SELL 78 → SELL 49 → BUY 35 → BUY 26 → BUY 2 → BUY 4,
eleven legs in thirteen minutes — one round trip roughly every 70 seconds, as the 43-second time
constant predicts — with buys and sells netting to **exactly zero**. The position ended where it
started; the only thing that changed hands was the spread and the fee, on every leg. The strategy book
is at `-$895.94` realised with `$140.73` of fees paid, on a book that is now flat.

The same mismatch also explains why the evidence accrues so slowly, though that is a symptom rather
than the disease: `SignalTelemetry.record` holds one open call per (source, instrument) and the horizon
is an hour, so each source contributes at most one independent emission cohort per hour — `reversion`
has 69 resolved observations in **3** cohorts, `trend` 138 in 6.

## Decision

The trading rate stops being a dial and becomes a **derived identity**: the desk's holding period is
set equal to the horizon its edge is measured over.

```
a = 1 - exp(-cycleSeconds / horizonSeconds)
```

so that `tau = -cycleSeconds / ln(1 - a) = horizonSeconds` exactly, by construction. At the shipped
configuration (30 s cycle, 3600 s horizon) `a = 0.008298707…` and `tau = 3600 s`. `FusionConfig` reads
`jethro.signals.horizon-seconds` — the same property the telemetry is configured by, so the two cannot
drift apart — and `jethro.fusion.adjustment-rate` becomes `0`, meaning *derive*; a positive value there
still overrides, as an escape hatch.

The economic content is one sentence: **the desk must hold a position for one measurement horizon per
round trip it pays**, because that is the trade the edge gate priced. This is the standard result, not
a view — the optimal trading rate falls as transaction costs rise relative to the rate at which the
signal decays (Gârleanu & Pedersen, "Dynamic Trading with Predictable Returns and Transaction Costs",
*JF* 68(6), 2013).

**The rate applies only to the risk-increasing part of a delta.** `TargetPlanner.orderDelta` splits the
gap at flat: the part that runs against the position and is contained within it — the walk to flat — is
traded **in full this cycle**; only the part that opens or grows exposure is multiplied by `a`.

- flat → long 150 at `a = 0.01`: nothing to reduce, whole gap rated ⇒ trade `0.01 × 150 = 1.5`
- long 100 → target 10: gap `-90` is entirely a reduction ⇒ trade `-90` in full
- long 100 → short 200: `-100` to flat in full, plus `0.01 × (-200)` of new short ⇒ `-102`
- short 100 → target -150: gap `-50` grows the short ⇒ `0.01 × (-50) = -0.5`

## Consequences

**Intended.** Turnover on the alpha book falls by roughly the ratio of the two time constants, and the
cost side of every round trip falls with it. The gate's arithmetic becomes sound: expectancy measured
over one horizon is compared against the cost of the one round trip that horizon now costs. Peak gross
exposure builds gradually rather than in two cycles, so a forecast that reverses before the position is
built is never fully paid for — which is precisely the noise the no-trade band was reaching for and
could not catch, because the band is proportional to `|target|` and collapses exactly when the target
crosses zero.

**Risk posture improves rather than degrades.** The obvious objection to a slow trading rate — that it
also slows the exit, against an owner thesis built on cutting fast — is why the rate is not applied
symmetrically. Under the old rate a full unwind was an asymptotic grind of about seven cycles, paying a
round trip on each step down and leaving a one-unit residual; it is now a single cycle to exactly flat.
Cutting is strictly faster than before, and adding is strictly slower. That is the asymmetry the desk
is supposed to have, and it is the same asymmetry the ADR-0065 gates already apply to exits.

**Accepted cost.** A genuinely fast-decaying edge — one that is real over a minute and gone in ten —
becomes untradeable by this desk. That is the correct answer given the measurement: nothing currently
grades such an edge, so trading it would be trading on a number nobody has. If a shorter horizon is
later shown to carry edge, the lever is `jethro.signals.horizon-seconds`, and the trading rate follows
it automatically. That coupling is the point of deriving rather than dialling.

**Not measurable immediately.** The edge gate is presently reduce-only (no source's measured expectancy
clears its measured round-trip cost with significance) and the book is flat, so nothing trades and this
change will score as no material move. It is shipped now deliberately: the gate re-opens by itself the
moment a source earns its cost, and the 43-second holding period must not be in place when it does.

**Untouched.** The deterministic floor — pre-trade guardrail, firm drawdown breaker, ADR-0049
backtest-support veto, invariant-7/ADR-0016 gates — is unchanged. This ADR moves a sizing fraction
above that floor. Nothing here reads a model output, and every quantity stays exact decimal; `a` is a
dimensionless fraction of a gap, and only the risk-increasing leg is multiplied by it, with an explicit
scale and rounding mode.

## Alternatives considered

- **Shorten the telemetry horizon to match the 43-second policy instead.** Symmetric on paper and it
  would accrue evidence far faster (one cohort per horizon rather than one per hour). Rejected because
  it fixes the units by making the measurement worse: at 43 seconds the forward return is
  microstructure noise against a fixed round-trip cost, so the gate would be permanently and
  uninformatively shut, and the existing rolling window would silently blend hour-horizon and
  minute-horizon observations into one mean. The horizon is the thing worth measuring carefully; the
  trading rate is free to follow it.
- **Raise the edge-gate hurdle by the turnover multiple.** Charging `83 ×` the round trip would restore
  the arithmetic without touching the policy. Rejected: it leaves the desk churning and merely refuses
  to let it, and the multiple is an artefact of a configuration rather than anything measured — it
  would have to be re-derived every time either dial moved. Better to remove the mismatch than to
  correct for it.
- **Widen the no-trade band.** The band is already deliberately wide (0.5, OLEG-SET) and did not
  prevent the MSFT churn, because it scales with `|target|` and therefore vanishes at exactly the
  moment the forecast crosses zero and the desk is most likely to be chasing noise. Orthogonal problem;
  worth revisiting on its own evidence.

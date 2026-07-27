# ADR-0111: A source measured LOSING is stood down, not floored — "unproven" and "disconfirmed" are different findings

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, signals, risk

## Context

ADR-0097 gave the combination weights an admission rule: a source that has **not demonstrated a
directional edge** at the desk's own significance hurdle (`EdgeGate.demonstratesEdge` — the gate's own
test at zero cost) is held at the floor weight `min`, so it cannot out-vote a source that has. It is
still counted: `min > 0`, so `ForecastCombiner` keeps it in the vote and it keeps contributing to the
ADR-0076 diversification multiplier. The stated reason was that a demoted source should be
*down-weighted, not dropped*.

That rule collapses **two different findings** into one verdict:

- **UNPROVEN** — the sample cannot tell whether the source has an edge. The floor is exactly right:
  the desk does not know, so it defers without silencing.
- **CONTRADICTED** — the sample says, at the desk's own hurdle, that the source **loses money**. The
  floor is exactly wrong: the desk is handing conviction to a view its own measurements have
  disconfirmed.

The desk is in the second state on two sources and has been paying for it. At the ADR-0082 rung the
evidence selected (225 s), read off the live telemetry endpoint:

| source | resolved | cohorts | avg (bps) | SE (bps) | t | verdict | weight |
|---|---|---|---|---|---|---|---|
| reversion | 5582 | 471 | +2.0087 | 0.4271 | **+4.70** | demonstrated | **2.899** |
| trend | 5670 | 451 | −1.5033 | 0.3860 | **−3.89** | *contradicted* | 0.25 |
| momentum | 245 | 197 | −2.9177 | 0.9652 | **−3.02** | *contradicted* | 0.25 |
| social | 103 | 68 | −14.6323 | 8.0643 | −1.81 | unproven | 0.25 |
| mean-reversion | 18 | 18 | +3.2031 | 3.0862 | +1.04 | unproven | 0.25 |

`trend` and `momentum` are not sources the desk has failed to evaluate. They are sources it has
evaluated, with large samples, and measured as losing money — and the readings are consistent across
every rung of the ladder (`trend` t = −3.89 / −2.76 / +0.52; `momentum` t = −3.02 / −2.71 / −2.20), so
this is not one window's noise.

### What the floor costs

`ForecastCombiner` normalises by `Σweights`, so a floored source subtracts conviction from every name it
has a fresh view on, in proportion to its share of the weight. With the weights spread to 2.899 : 0.25
that share is 7.9% — but the *forecasts* point opposite ways, so the effect on the combined value is
roughly double that. On GOOG this cycle (reversion +15.134, trend −13.974):

```
average  = (15.134·2.899 + (−13.974)·0.25) / (2.899 + 0.25) = 40.379966/3.149 = 12.823139…
Σw²ₙ     = (2.899² + 0.25²)/3.149²        = 8.466701/9.916201 = 0.8538178…
DM       = 1/√(0.8538178… + 0.5·(1 − 0.8538178…)) = 1/√0.9269089… = 1.0386636…
combined = 12.823139… × 1.0386636…        = 13.319070…
```

against `15.134` with `trend` stood down (one active source ⇒ DM = 1). **The disconfirmed source is
costing 13.6% of the desk's conviction on that name**, and the same on every other name it calls.

That 13.6% is not cosmetic, because the desk is currently priced right at its own margin:

- The ADR-0075 per-name cost gate admits a name only when the passing source's edge survives *that
  name's own* measured round trip with significance: `cost ≤ 2.0087 − t_crit·0.4271 = 0.969 bps`. Of the
  eight names in the target book, exactly **one** — AAPL, at 0.897 bps — clears it. (JPM 1.075, JNJ
  1.204, MSFT 1.338, GOOG 1.854, GOOGL 20.106, and every unmeasured name at the 1.327 bps blend, do not.)
- AAPL then fails its ADR-0101 no-trade band by roughly **8%**: the endpoint publishes aim `−122.704078`
  against a target of `−134.528168` at a combined forecast of `−9.007`, and `widthFor`/`band` applied to
  those published inputs at AAPL's own measured round trip put the half-width just above the gap. (That
  last figure is *derived here from the endpoint's own readings* to explain the observed
  `deltaQty: 0`; it is not itself a measurement, and nothing sizes off it.)

So the desk's whole tradable book is one name, that name is 8% short of its own buffer, and a source the
desk has measured at t = −3.89 is taking 13.6% off the conviction that would clear it. Live consequence,
this cycle: **eleven names with live targets, `deltaQty` zero on every one, gross exposure `$0.00`**, and
a firm PnL that has not moved off its stale flag.

## Decision

**Classify a source into three states, not two, and give the third one weight zero.**

Add `EdgeGate.contradictsEdge(stats, params)` — **identical** to `demonstratesEdge` with the measured
sign reversed: the same expectancy, the same Fama–MacBeth standard error (ADR-0077), the same Student's
t on `cohorts − 1` (ADR-0081), the same α at the same selected rung (ADR-0082). No second statistic, no
new dial, nothing chosen. In `TelemetryWeights.compute(stats, params, admission)`:

| state | test | weight |
|---|---|---|
| DEMONSTRATED | `demonstratesEdge` | as measured (ADR-0067/0074) |
| UNPROVEN | neither | `min` — floored, still voting (ADR-0097, unchanged) |
| CONTRADICTED | `contradictsEdge` | **0** — stood down, leaves the vote |

The three states are **total and mutually exclusive** for any α < ½: a mean cannot be significantly above
and significantly below zero on the same sample. A stood-down source has weight ≤ 0, so
`ForecastCombiner` skips it — it stops contributing to the combined forecast **and** stops counting
toward the ADR-0076 diversification multiplier, which is the honest reading: a view the desk will not act
on is not breadth.

Unchanged: the rule fires only when some source **is** admitted (ADR-0097's guard). With nothing admitted
the desk is reduce-only anyway and the weights are left exactly as measured. And because no source can be
both admitted and contradicted, at least one non-zero weight always survives — the weight vector can
never be zeroed out from under the combiner.

## Consequences

**Good.**

- The desk stops surrendering conviction to views it has measured as losing money. On the live
  cross-section that is +13.6% of combined forecast on every name `trend` calls — and since the ADR-0101
  band is forecast-independent while the aim is linear in the forecast, that is the difference between a
  book at `$0.00` gross and a book holding the one position its own cost gate permits.
- The classification is measurement, not decree, and it self-calibrates: a source that recovers stops
  being contradicted on its own telemetry and returns to the floor, then to its measured weight. Nothing
  here is a dial and no number is chosen.
- The diversification multiplier now reflects the sources actually steering the book. Counting a
  disconfirmed source as breadth was leverage no measurement supported.

**Bad / risks, stated plainly.**

- **This raises exposure.** A stronger combined forecast is a larger target, and larger targets are the
  point — but the objective is PnL *per unit of exposure*, so if the reversion edge does not survive
  contact, this makes the loss bigger, not smaller. It is scored on the firm total either way.
- **Concentration.** The desk becomes, in practice, a single-source (reversion) desk on the names where
  trend and momentum were the only other voices. That is what the evidence says, but it removes the
  accidental hedge that a wrong-signed source at floor weight was providing, and it makes the book's
  fortunes depend on one measurement holding up.
- **A name whose only fresh forecasts are all contradicted now combines to zero**, which is a FLAT target
  — and a flat target is an exit worked in full and unbuffered (ADR-0090/0107). This is not a new
  mechanism (it is exactly the ADR-0065 "no view, no position" path a name already takes when its sources
  go stale) and it cannot happen while any admitted source has a fresh view on the name, but it means a
  source being disconfirmed can trigger an immediate unwind rather than a rated one.
- **It does not fix the binding constraint.** Seven of eight names are blocked by the ADR-0075 per-name
  cost gate, and that is a genuine, honest reading: the desk's measured execution costs (0.90–1.85 bps
  per name) are close to what its measured edge (2.0087 bps gross at 225 s, lower bound 0.969 bps) can
  pay. This change unlocks the one name that clears; **reducing measured round-trip cost is the next
  lever**, and no amount of re-weighting substitutes for it.

**Neutral.**

- Strictly one-way in conviction: `min(w, 0)` can only lower a weight, and the weight floors at zero — a
  losing source is never inverted into a contrarian bet, which is the canonical overfit (Harvey, Liu &
  Zhu, *RFS* 2016).
- Nothing here prices, sizes or gates money. Weights are dimensionless conviction ratios; the money
  boundary is `TargetPlanner`, downstream and in exact decimal (invariant 1 / invariant 7 / ADR-0016).
- The deterministic floor is untouched: the pre-trade guardrail, the ADR-0027 breaker and the ADR-0086
  risk cut sit below this and are unaffected.

## Alternatives considered

- **Leave it at the floor (status quo).** Defensible if the floor is read as "the desk never fully trusts
  a single measurement". But the floor's own justification in ADR-0097 is that a demoted source keeps
  contributing *breadth* — and a source pointing the wrong way is not breadth, it is drag.
- **Invert a contradicted source into a contrarian bet.** Rejected, and deliberately: trading the
  negation of a losing signal is the canonical overfit, and `Φ`'s boundedness was chosen in ADR-0067
  precisely to make it impossible. Zero is the floor, not a sign flip.
- **Widen the hurdle so trend/momentum fail more slowly.** Rejected — that is a dial where a measurement
  will do.
- **Attack the ADR-0101 band instead** (AAPL misses it by 8%). Rejected: the band's multiplicand is the
  *average* position precisely so the half-width is forecast-independent, which is what Grinold–Kahn's
  `2C/(λσ²)` actually is. The band is correct; the conviction feeding it was being taxed.

## References

- ADR-0097 (the admission rule this narrows), ADR-0067 / ADR-0074 (the weighting statistic and its
  credibility shrinkage), ADR-0076 (the diversification multiplier from the weights actually used).
- ADR-0064 / ADR-0075 / ADR-0077 / ADR-0081 / ADR-0082 (the significance test, its standard error, its
  reference distribution and its rung — all reused verbatim here).
- Harvey, Liu & Zhu, "…and the Cross-Section of Expected Returns", *RFS* 29(1), 2016 — on why inverting a
  disconfirmed signal is not a free edge.

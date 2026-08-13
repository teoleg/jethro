# ADR-0141 — The average position is priced at the forecast the combiner produces, not the one sources are normalised to

**Status:** Implemented
**Date:** 2026-08-06
**Supersedes:** none. Amends the *scale* of ADR-0094's no-trade band. Changes no number in ADR-0080
(the adjustment rate), ADR-0101 (the band's WIDTH), ADR-0102 (the within-target clamp), ADR-0124 (the
agreement scalar), ADR-0076 (the diversification multiplier), ADR-0132/0137 (the gross budget and cap)
or any part of the deterministic floor.

## Context

The desk is dormant and underwater. This cycle's live reading: firm gross exposure **$0.00** against a
firm cap of **$1,500,000** — **0.0%** deployed, **$1,500,000** of headroom — total PnL
**-628.06833967**, and `fusion_targets.insideBuffer` **8** of **8** planned instruments. The planner
produced targets throughout (`targetQty` **-0.017759** for NQ, **-4.909292** for NVDA) and routed
nothing against any of them. ADR-0132 says undeployed capital under the budget is a failure to attack.

ADR-0140 made the aim durable so the ADR-0080 transient could complete. It did — the live `aims` map
carries **-0.003064** for NQ, a genuine partially-adjusted intent that survived a restart. The desk
still routed nothing. So the aim reaching the band was never the whole story, and the reason is
arithmetic rather than state.

### The dead zone

ADR-0094's band is `width × |target| × TARGET_ABS / |forecast|` — Carver's buffer at a fraction of the
name's *average position*, the position it would carry at a typical-strength forecast (*Systematic
Trading*, Harriman House 2015). Against a flat book the gap is the aim itself, and ADR-0102 confines
the aim to the closed interval between flat and the target. So the first order in a name releases when

```
|aim| > band   ⟺   |aim| / |target|  >  width × TARGET_ABS / |forecast|
```

with the left-hand side bounded above by **1** — by ADR-0102, not by any dial. The condition is
therefore **unsatisfiable for every name whose combined forecast is weaker than `width × TARGET_ABS`**,
no matter how long the aim path runs, how durable the aim is, or how many cycles the name is planned.
At the shipped `width` (0.10, Carver's convention, ADR-0094/0101) and `TARGET_ABS` (10.0, ADR-0055)
that dead zone is every `|f| < 1.0` on a ±20 forecast scale.

Both names carrying a view this cycle sit at or inside it. Read off the live plan:

| name | combined `f` | required `\|aim\|/\|target\|` | reachable? |
| --- | --- | --- | --- |
| NQ | -1.2137982837547245 | 0.8239 | only at 82% of target, on a path with a whole-horizon time constant |
| NVDA | -0.31439455218834894 | **3.1807** | **never** — the requirement exceeds the ADR-0102 bound of 1 |

NVDA's band is **15.615067** against a target of **4.909292**: the no-trade region is three times the
entire position the desk wants. The other six planned names are single-source, so ADR-0124 correctly
returns agreement 0 and they are planned flat; they are not affected by this ADR either way.

### Why the desk's forecasts sit there — and why that is not a bug

`TARGET_ABS` is the constant each **source** is normalised to: `forecastScalars` scales every source so
a typical reading is 10 (live `meanAbsClaim` **8.933184091982607** reversion, **6.672769378384960**
trend, **9.238246464313300** xsreversion — all near it, as designed). But the band is applied to the
**combined** forecast, which is the sources' weighted average multiplied by the ADR-0076
diversification multiplier and the ADR-0124 agreement scalar. Averaging opposing views shrinks the
mean; agreement then attenuates by the sources' dispersion about it. On the live plan NQ's two sources
(+18.529717715250550 reversion, -20.0 trend) average to -5.427, and agreement **0.1954775485324326**
takes it to -1.214.

That attenuation is correct and is the point of both ADRs. What is wrong is that they were each
specified as reductions in **size** — ADR-0124 states the scalar "can only ever SHRINK the combined
value", ADR-0076 that re-weighting "can never grow the book" — while the band went on being priced at
the **unattenuated** constant. Passing a shrunken forecast into a threshold calibrated for an
unshrunken one does not shrink the position; it deletes it. An 80% haircut to conviction became a 100%
haircut to the position, permanently. Neither ADR intended a tradability gate, and neither is where a
tradability gate belongs — the desk already has one (ADR-0064's edge gate), and it is off (ADR-0122).

## Decision

**Price the average position at the forecast strength the combiner actually produces, measured on the
desk's own cross-section, capped at the nominal constant.**

```
E|f|   = mean |combined forecast| over the names planned a non-zero view THIS cycle,  n ≥ 2
Ê      = min(TARGET_ABS, E|f|)          — and TARGET_ABS whenever n < 2
scale  = |target| × Ê / |forecast|      — the name's position at a TYPICAL combined forecast
band   = scale × width                  — unchanged in form (ADR-0094), unchanged in width (ADR-0101)
```

The release condition becomes `|aim|/|target| > width × E|f| / |f|`, which for a name at typical
strength is exactly `width` — Carver's rule as stated, restored. A name at a tenth of typical strength
still needs ten times the fraction of its target, so weak views remain proportionately harder to
trade; what disappears is only the region where the requirement exceeds 1 and the name is frozen
regardless of evidence.

**Measured, not dialled, and no number is introduced.** `E|f|` is the mean of forecasts the planner
computed this cycle; no constant, threshold or multiplier is added anywhere (invariant 7 / ADR-0016).
Read off the live plan, `E|f|` = **0.7640964179715367**, which turns NQ's band from **0.014631** to
**0.001118** against a gap of **0.003064** — the name routes to the near edge, order **-0.001946** —
and NVDA's from **15.615067** to **1.193142**, reachable at 24.3% of target.

**Cross-sectional, so it needs no estimator, no warm-up and no persistence.** It is read off this
cycle's own arithmetic, exactly as the existing `|target| × TARGET_ABS / |f|` scale is, so it survives
a restart intact — the failure mode ADR-0138 and ADR-0140 were both spent repairing — and it
self-calibrates to any feed's forecast distribution rather than assuming one (no hardcoded level, per
the loop's feed-agnostic requirement and invariant 9).

**n ≥ 2, because one name is not a cross-section.** At n = 1 the mean *is* the datum, so `E|f|/|f|` is
identically 1 and the band would collapse to `width × |target|` for every forecast strength — a
statistic measuring nothing but itself. That is the same degeneracy ADR-0124 rejected when the ADR-0119
sign ratio returned 1 at one effective source. Below two names the desk has not measured its own
forecast distribution, so it makes no claim and the nominal constant stands.

## Consequences

**It can only release, never freeze.** `Ê ≤ TARGET_ABS` by the cap, so the band is never *wider* than
the pre-ADR-0141 one. Every trade the desk makes today it still makes; the change is one-directional
toward acting on intent the desk had already formed.

**Intended risk is unchanged quantity for quantity.** The aim path, the target, the ADR-0102 clamp and
the ADR-0101 width are all untouched. Only the threshold at which an existing intent becomes an order
moves — the desk's decision about how much risk to want was never the thing that was broken.

**Turnover rises, and that is the trade being made.** A narrower no-trade region trades more often. The
band is now `width` of the *realised* average position instead of `width` of a notional one the
combiner does not produce, which is the calibration ADR-0094 intended; the desk was not paying less
turnover than that design, it was paying none because it held nothing. ADR-0101's measured width
(`2C/μ`, floored at the convention) is the control that answers cost, and it is untouched and still
applies on top — when the edge gate is rewired it will widen the band for expensive names exactly as
before. If the ledger scores this change negative on turnover the revert is a one-line restoration of
the constant.

**Safety is not relaxed anywhere.** This raises a *size/tradability* dial and touches no gate: the
ADR-0064 edge gate, the ADR-0126 σ-cold veto, the ADR-0083 volatility budget, the ADR-0079 portfolio
normaliser, the ADR-0137 gross cap, the ADR-0086 chandelier cut, the pre-trade guardrail and the firm
drawdown breaker all still have the last word on every released order. An exit is still never buffered
(ADR-0090): a flat target snaps the aim to zero and is worked in full, and that branch returns before
the band is consulted at all.

**What would falsify it.** If the desk still holds nothing with `insideBuffer` at the full plan, the
band was not the binding constraint and the diagnosis is wrong. If it builds exposure and the ledger
scores the vector negative, the released trades are not worth their cost and the correct response is
ADR-0101's width, not this scale.

## Alternatives rejected

- **Lower `width` below 0.10.** It is a cited convention (Carver) and ADR-0101 already derives a
  measured width from the desk's own cost and edge. The defect is not that the fraction is too big; it
  is that the quantity the fraction is taken *of* is the wrong one. Cutting the fraction to compensate
  would hide a units error behind a dial and would break the cost logic the moment the edge gate rewires.
- **Weaken ADR-0124's agreement scalar so combined forecasts land nearer 10.** The scalar is measuring
  something real — the desk's sources genuinely disagree — and this cycle's six flat names are flat for
  the right reason. Inflating a conviction number to clear an unrelated threshold corrupts the
  measurement to fix the consumer.
- **Drop the ADR-0102 within-target clamp so the aim can exceed the target and reach the band.** The
  clamp is what stops the desk intending past, or against, its own current view. Removing a correctness
  bound to satisfy a mis-scaled threshold trades a real safety property for an arithmetic error.
- **An EWMA of `E|f|` over cycles instead of the cross-section.** More stable, but it reintroduces
  warm-up and reset-on-restart — precisely the failure ADR-0138 and ADR-0140 were spent removing — for a
  band that is already re-derived from scratch every cycle.

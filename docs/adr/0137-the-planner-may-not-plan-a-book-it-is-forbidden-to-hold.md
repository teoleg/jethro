# ADR-0137 — The planner may not plan a book it is forbidden to hold

**Status:** Implemented
**Date:** 2026-08-04
**Author:** continuous-improvement loop (ADR-0063)
**Supersedes:** nothing. **Related:** ADR-0079, ADR-0080, ADR-0083, ADR-0094, ADR-0104, ADR-0132

## Context

Every sizing control in the fusion pipeline is **σ-relative**:

| control | what it decides |
| --- | --- |
| ADR-0083 `VolatilityBudget` | how the per-name cash budget is **shared out**, by measured σ |
| ADR-0079 `PortfolioRiskNormaliser` | how much of the book is **one bet**, by measured correlation |
| ADR-0104 `BookVolatilityBrake` | the book's ex-ante **σ level**, against the median of its own series |

All three answer *how risk is distributed and how much σ the book carries*. **None of them states a
notional.** On a calm tape a measured σ is small, so on the live book none of them binds — read from
`/api/fusion/targets` at 2026-08-04T16:29Z: `volBudgetLeverCap 1.0`, `bookVolBrake 1.0`,
`portfolioRiskMultiplier 0.8726…`.

With nothing constraining notional, the planned target book was **$1,356,452.14 gross** (Σ |targetQty ×
price| over the 22 planned names, computed from that endpoint) against:

- **$500,000** — `jethro.risk.max-gross-exposure`, the cap the deterministic pre-trade guardrail enforces
  on the book these orders route to → the planned book was **2.71×** the gross it is permitted to hold;
- **$91,999.52** — what the desk actually held → **14.74×**.

### Why an unreachable target costs money, even though no risk was put on

The guardrail was never at risk of being breached; it still refuses the order. The damage is that the
whole ADR-0080 / ADR-0094 trading path is a **function of the distance to the target**, and that distance
was inflated by a factor the desk never chose:

1. **The aim never converges.** Under ADR-0080 the aim e-folds toward the target at
   `a = 1 − exp(−30/3600) = 0.0082987…`. Against an unreachable target the held book settles at a small,
   roughly constant fraction of it and stays there. Live: PFE `targetQty 3319.769516`, `aim 530.737491`,
   `currentQty −267.0` — the desk was short a name whose own target was long twelve times the size, and
   trading toward it at 43 shares per 30-second cycle.
2. **Turnover scales with the inflation.** The per-cycle step is `a × gap`. Inflating the target inflates
   `gap` by the same factor, so the desk pays that multiple in turnover **every cycle** — and turnover is
   the desk's dominant loss term (firm total `-$524.66210431` against `totalFees 369.021411`; pre-fee
   trading is `-$155.64…`, so fees are the majority of the loss).
3. **It over-trades the few and freezes the many.** The ADR-0094 band is `|target| × TARGET_ABS /
   |forecast| × fraction` — also proportional to the target. An inflated book widens the band past the gap
   for most names while a handful chase: live `insideBuffer 19` of 22.
4. **It never holds for its horizon.** The edge is measured at 3600s. A desk permanently in transit toward
   a target it cannot reach never holds a position through the horizon its expectancy was measured over,
   so it pays round-trip cost against credit it never collects — exactly the dimensional argument
   ADR-0080 was written to fix, defeated at a different point in the pipeline.

## Decision

Add **`GrossNotionalCap`**: after the σ controls have decided the book's *shape and risk level*, scale the
whole planned book pro-rata so its **gross notional** does not exceed the gross the routing book is
permitted to hold.

Over the names whose USD notional can be asserted, with signed quantities `qᵢ`, prices `pᵢ` and contract
multipliers `mᵢ` (ADR-0078):

```
plannedGross = Σᵢ |qᵢ · pᵢ · mᵢ|
GNM          = min(1, capUsd / plannedGross)
qᵢ'          = qᵢ · GNM
```

`capUsd` is wired from **`jethro.risk.max-gross-exposure`** — the guardrail's own cap. **No money number
is introduced by this ADR** (invariant 7 / ADR-0016). The decision is an identity the desk was violating:
*do not plan a book you are forbidden to hold.*

Placed after ADR-0104 and before the ADR-0064 edge gate, the ADR-0086 risk cut and the ADR-0094 buffer, so
the deltas the operator sees on `/api/fusion/targets` are the ones that will route.

### Worked example (by hand, exact decimal)

```
A: target +100 @ 50.00 × 1  →  |notional| = 5,000.00
B: target −200 @ 25.00 × 1  →  |notional| = 5,000.00
plannedGross = 10,000.00 ;  cap = 4,000.00 ;  GNM = 4,000/10,000 = 0.4 exactly

A' = +100 × 0.4 = +40.000000  →  40 × 50.00 = 2,000.00
B' = −200 × 0.4 = −80.000000  →  80 × 25.00 = 2,000.00
gross' = 4,000.00 = the cap, to the cent; the 1:1 notional ratio between A and B is unchanged
```

Encoded as `GrossNotionalCapTest.scalesTheBookToExactlyTheCapAndPreservesItsShape`.

### Properties

- **Under the cap ⇒ GNM = 1**, and the book is returned byte-identical. The control is silent until it
  binds — a desk whose plan already fits is untouched.
- **Never grows the book.** The `min` with 1 discards a ratio above one, so a small book is never levered
  up to the cap. Same one-way safety property as ADR-0079 and ADR-0104.
- **Sign- and shape-preserving.** GNM > 0 and uniform, so no name flips side and the cross-sectional shape
  the σ controls chose is untouched. This is a *level* control — not a view, not a re-weighting.
- **Exact at the boundary.** The multiplier is computed in exact decimal and rounded **DOWN**
  (`RoundingMode.DOWN`, scale 12), so the scaled book's gross is ≤ the cap by construction, never a
  rounding step above it.
- **Coverage.** A name with no price or no contract multiplier has no assertable USD notional, so it
  enters neither the sum nor the scaling — the same fall-back-rather-than-guess rule ADR-0079 applies to
  an uncovered covariance.

## Consequences

**This is not a de-risking change and must not be read as one (ADR-0132).** The desk holds $91,999.52 of
equity gross; the cap it will now plan against is $500,000. The change *shrinks the target*, not the
position — it makes the target **reachable**, so the aim can actually converge and the desk can hold a
position through its measurement horizon instead of permanently chasing. Held gross is expected to rise
toward a book the desk can defend, not fall.

Turnover per cycle should fall by roughly the inflation factor, since the step is `a × gap` and `gap`
shrinks with the target. `insideBuffer` should fall too, as the band stops being inflated past the gap.

**Risk accepted.** If the σ controls were ever the binding constraint on a genuinely correlated book, this
cap sits above them and cannot loosen them — it only ever shrinks further. The failure mode in the other
direction (a book whose planned gross is legitimately near the cap gets trimmed slightly) costs nothing:
the guardrail would have refused the excess anyway.

**Nothing on the deterministic floor changed.** The pre-trade guardrail, the firm drawdown breaker, the
conviction floor, the edge gate and the instrument cap all still stand, unmodified. This control makes the
planner *agree in advance* with a limit that was already being enforced downstream.

**VERIFY-BY (next cycle).** From `/api/fusion/targets`: `Σ |targetQty × price|` over the planned book must
be **≤ 500,000** (it was `1,356,452.14`). Secondary, same endpoint: `insideBuffer` should fall from `19`
of 22, and the per-cycle Σ|deltaQty × price| should fall materially.

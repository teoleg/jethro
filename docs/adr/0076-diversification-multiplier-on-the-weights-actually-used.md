# ADR-0076: Size the diversification multiplier from the weights actually used, not from the source count

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** trading, fusion, sizing, risk

## Context

`ForecastCombiner` turns a name's per-source forecasts into one conviction: a weighted average, then a
**diversification multiplier** (DM) that restores the scale averaging removed (Carver, *Systematic
Trading*, 2015). The average is the desk's view; the DM is how much size that view is allowed to carry.
It therefore sits directly on the exposure the desk puts on — it is a sizing number, not an analytic.

The DM was computed as `1/√(1/n + ρ(n−1)/n)` from `n`, the **count** of contributing sources, with its
own javadoc stating the precondition: "for `n` *equally-important* forecasts". That precondition has not
held since ADR-0067 made source trust evidence-driven. Weights are bounded on `[0.25, 3.0]` — a 12× span
— and the telemetry pushes a measured-bad source to the floor while a measured-good one climbs. The live
reading this cycle was `reversion = 2.523`, `trend = 0.250`: normalised, one source carries 91% of the
vote. The count rule nonetheless awarded the full two-equal-source multiplier, 1.1547.

The error is systematically one-directional and worst exactly when the evidence is worst. The more
convincingly the desk measures a source to be bad, the more concentrated the weight vector becomes, and
the more the count over-states breadth — so a source held at the floor *for losing money* still bought a
full extra unit of leverage. ADR-0074's note that keeping `MIN > 0` leaves "the active-source count, and
with it the diversification multiplier, unchanged" recorded the mechanism without recognising it as a
cost. Doing nothing leaves the target book over-sized by ~10% at the current weights, and by more as the
telemetry sharpens — leverage no measurement supports, on a book already down on the day.

## Decision

**We will compute the diversification multiplier from the concentration of the weight vector actually
used, not from the number of sources.** For normalised weights `w` under the assumed equicorrelation `ρ`,
the variance of the average the desk actually formed is `Σwᵢ² + ρ(1 − Σwᵢ²)` (the `Σᵢ≠ⱼ wᵢwⱼ` cross terms
sum to `1 − Σwᵢ²`), so

```
DM = 1 / √( Σwᵢ² + ρ·(1 − Σwᵢ²) )        capped at 2.5 as before
```

`1/Σwᵢ²` is the inverse-Herfindahl **effective** number of sources, so this is the same formula evaluated
at the breadth the weights deliver rather than at the breadth of the roster. Three properties are
required and tested: it reduces **identically** to the count rule at equal weights (`Σwᵢ² = 1/n`) — the
cold start and `weights.mode=equal`, so the null case is byte-unchanged; by Cauchy–Schwarz `Σwᵢ² ≥ 1/n`
and the denominator increases in `Σwᵢ²` for `ρ < 1`, so the new DM is **never larger** than the old; and
`DM ≥ 1` always, so averaging can never make the desk less confident than a single view. No new dial and
no new number: same `ρ`, same cap, same weights.

This replaces the ADR-0067 safety property "re-weighting rotates conviction but cannot scale the book"
with the strictly safer "re-weighting rotates conviction and can only **shrink** the book".

## Alternatives considered

- **Leave it and lower `unit-notional-usd` instead.** Rejected: it treats a modelling error as a taste
  for less risk. The over-sizing is proportional to weight concentration, which moves every cycle with
  the telemetry, so a fixed notional cut is right at one moment and wrong at every other — and it would
  shrink the book equally when the weights *are* equal and the count rule is correct.
- **Drop floor-weighted sources from the combine entirely (weight `0` ⇒ not "active").** Rejected: it
  fixes the breadth count by silencing a source, discarding the information in a down-weighted view and
  putting a cliff at the floor. ADR-0074 removed one such cliff deliberately; this would add another.
  The continuous form gets the same breadth correction with no discontinuity.
- **Estimate the true forecast correlation matrix and use `1/√(w'Σw)`.** Deferred, not rejected — that
  is the honest end state and this decision is its equicorrelation special case. Reviving trigger: enough
  co-observed per-source forecast history to estimate pairwise correlations with a usable standard error;
  until then a measured `Σ` would be noisier than the `ρ` placeholder it replaced.
- **Correct the DM only when a source sits at the MIN floor.** Rejected: a special case for the symptom.
  The defect is present at every unequal weight vector, floor or not.

## Consequences

- **Positive:** the desk stops claiming diversification it does not have; target notional falls precisely
  when trust is concentrated, which is when the claim was emptiest. Total exposure down for an unchanged
  forecast is a direct improvement in PnL per unit of exposure — the loop's objective. The count rule
  survives as a special case, so cold start and `weights.mode=equal` are unaffected.
- **Negative:** the book is now smaller whenever the telemetry has an opinion, so if the concentrated
  source is genuinely the good one the desk holds *less* of a winning view — the change is deliberately
  asymmetric against leverage. It also couples sizing to the weights, which ADR-0067 had kept separate;
  a weight bug can now move exposure, not just its composition. `ρ` remains a placeholder, so the DM is
  still only as good as that assumption.
- **Follow-ups:** ADR-0067's stated invariant is amended by this ADR (weights may now shrink the book);
  the deferred alternative above is the natural successor once forecast-correlation history exists.
  Neither the pre-trade guardrail, the firm drawdown breaker, nor the edge gate is touched.

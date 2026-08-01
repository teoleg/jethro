# ADR-0119: A combined forecast is sized by the AGREEMENT behind it, not by its mean alone

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** trading, fusion, sizing, risk

## Context

`ForecastCombiner` fuses a name's per-source forecasts into one conviction: a weighted average
(ADR-0055), scaled up by a diversification multiplier measured on the weights actually used
(ADR-0076). `TargetPlanner` then turns that single number into a target position. **The mean is the
only thing that reaches sizing.**

That loses the information the desk most needs. Two sensors quietly agreeing on +1.5 and two sensors
fighting each other to a net +1.5 produce the *same* mean and therefore the *same* position — despite
carrying completely different confidence about the sign. In Bayesian terms the combination step
shrinks the posterior mean when views conflict, which the average does correctly, but it also
*widens* the posterior variance, which nothing here was doing. Sizing on the mean while ignoring the
dispersion is over-sizing, and it over-sizes worst exactly where the desk has least information.

The diversification multiplier then makes it strictly worse. The DM exists to restore the scale that
averaging *correlated* forecasts removed. Under disagreement the averaging did not merely rescale —
it revealed a contradiction — and the DM multiplies the surviving residual back up on a
diversification assumption the disagreement itself contradicts.

This is not hypothetical. On the live book of 2026-07-28 the desk's whole equity risk was one name,
AAPL, and it was the single name in the cross-section whose sensors most flatly contradicted each
other: trend `+11.222357223010645` against reversion `−10.408272759827817`, netting to a combined
`−1.5229724354072096` after a DM of `1.1497986707349463`. The desk sized `−14.915227` shares off that
residual — and then paid gross exposure on an ES hedge against it. Its aim on that name had flipped
from `+6.031064` (long) to `−14.915227` (short) between two consecutive thirty-minute cycles, because
the sign of a difference of two near-equal large numbers is noise. Every other name in that
cross-section had sources pointing the same way. **The one position the desk held was the one it
understood least, sized as if it understood it best.**

## Decision

We will scale a name's combined forecast by the **agreement** of the sources behind it — the fraction
of their gross conviction that survives as a net view rather than cancelling:

```
  agreement = |Σ wᵢ fᵢ| / Σ wᵢ |fᵢ|          ∈ [0, 1]
  combined  = clamp( (Σ wᵢ fᵢ / Σ wᵢ) × DM × agreement )
```

The Σw normalisation is common to both sums and cancels, so this is formed from the running sums the
average is already built from — no weight vector is materialised and no new input is read.

This is the efficiency ratio the desk already uses over TIME — for a trend sensor (ADR-0113) and for
the hedge target's own path (ADR-0100) — read here ACROSS sources. Net displacement over distance
travelled; round trips earn nothing. Same statistic, new axis.

**No number is introduced.** There is no dial, no threshold and nothing to calibrate: the scalar is a
ratio of quantities the combiner already holds, so there is no risk/money parameter needing
provenance (invariant 7 / ADR-0016). The forecast layer is dimensionless throughout — a conviction,
never a size or a price — and the money boundary is unmoved: `TargetPlanner` still converts to a
`BigDecimal` quantity in exact decimal (invariant 1). Strictly above the deterministic floor; the
pre-trade guardrail and the drawdown breaker are untouched.

**Strictly one-way, by the triangle inequality.** `|Σwᵢfᵢ| ≤ Σwᵢ|fᵢ|` always, so `agreement ≤ 1` and
the combined value can only ever SHRINK — the book can never be grown by this rule. Equality holds
EXACTLY when every contributing forecast shares a sign, so **a name whose sources agree, and every
single-source name, is byte-identical to the pre-ADR-0119 desk.** The sign is never flipped
(`agreement ≥ 0`), and the all-zero case is the only 0/0, where the net view is already zero and the
scalar is irrelevant. This is the third rule in the same family as ADR-0076 ("re-weighting cannot
grow the book") and ADR-0098 ("churn shrinkage is one-way"): every estimate we add to the sizing path
may only ever make the desk smaller.

The scalar is surfaced on `FusionPlanner.Target.agreement` and therefore on `/api/fusion/targets`. It
was always *derivable* from the published `contributions`, and for two consecutive cycles neither the
improvement loop nor the owner derived it — which is why the AAPL diagnosis was wrong twice. A
control that decides position size should be readable, not reconstructable.

## Alternatives considered

**Estimate the forecast correlation ρ and let the existing DM handle it.** The DM's ρ is documented in
the code as a placeholder awaiting measurement, so this looks like the tidy fix. It is not:
`DM = 1/√(h + (1−h)ρ)` is *increasing* as ρ falls, so feeding it a measured anti-correlation makes the
multiplier LARGER — it would amplify precisely the residual we want to shrink. The DM answers "how
much scale did averaging remove?", a question about breadth. Disagreement is a question about
confidence. They need different terms.

**Compute the DM per-observation from the realised dispersion** (`|s|/|m|`). Mechanically this is the
reciprocal of the agreement ratio and therefore exactly backwards: every cancellation would be
levered up to a full-size position, capped at 2.5. Carver's FDM is a constant estimated over history
across the whole book, and applying it per-observation is a misreading of it.

**Veto the name when the sources disagree** (a hard threshold: below some agreement, target flat). It
would work on this book, but it needs an invented cut-off with no provenance — exactly the class of
number CLAUDE.md forbids — and it makes sizing discontinuous at the threshold, so a name would flip
between full size and flat on estimation noise. The continuous scalar has no such edge and needs no
number.

**Widen the no-trade band under disagreement instead.** That suppresses *trading* while leaving the
*target* wrong, so an already-held position stays wrong-sized and merely becomes harder to correct —
the ADR-0118 freeze in a new costume. Size the view correctly first.

**Leave it and shrink at the portfolio layer** (`PortfolioRiskNormaliser` / `VolatilityBudget`). Those
control total risk, not per-name conviction; they would scale the confident names down alongside the
contradicted one. The error is in one name's conviction and belongs where the conviction is formed.

## Consequences

- **Positive.** A name is sized by how much its sensors actually agree, so the desk stops taking
  full-conviction positions on the difference of two large opposing estimates. Names with unanimous
  sources are unaffected — this only ever removes exposure the desk had no evidence for, which is
  gross exposure removed from the denominator of the objective.
- **Positive.** Aim stability. A residual whose sign flips cycle to cycle is now sized near zero, so
  it stops generating churn and stops dragging a hedge leg behind it.
- **Positive.** It composes with ADR-0118 rather than duplicating it: as sources converge on
  cancellation the aim goes to flat, and ADR-0118's flat-aim-under-a-shut-gate branch then exits the
  position instead of freezing it. ADR-0118 could not fire on 2026-07-28 because the aim was still
  large; this is the rule that makes it reachable.
- **Negative — honest cost.** Trend and reversion are *structurally* opposed at different horizons, so
  a genuine trend fighting a genuine reversion signal will be sized down even when one of them was
  right. This trades expected return for a lower variance of being wrong about the sign; on a desk
  whose measured LIVE expectancy is currently negative that is the correct side of the trade, but it
  is a real cost and it should be revisited if the edge gate opens and the book is systematically
  under-sized.
- **Negative.** The scalar is computed from one cycle's forecasts, so it is itself a noisy estimate. It
  is bounded in [0,1] and one-way, so the worst case is a position smaller than intended — never
  larger, never a different sign.
- **Negative.** `FusionPlanner.Target` gained a component, so its six reconstruction sites and their
  test fixtures changed mechanically.
- **Follow-ups.** If the agreement scalar proves stable and informative, the natural next step is to
  measure the source correlation ρ properly and retire the DM's placeholder — a separate ADR, and one
  this change does not prejudge.

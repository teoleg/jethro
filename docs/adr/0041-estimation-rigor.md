# ADR-0041: Estimation rigor — covariance burn-in, par-curve bootstrap, honest VaR windows

- **Status:** Accepted (directed by Oleg from the 2026-07-18 math review)
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** risk, quant, rates, hedging

## Context

The 2026-07-18 fin-math review verified the formulas against their documented references but
flagged three estimation-quality defects. (1) The EWMA covariance seeds with the FIRST day's
outer product and gates at 20 observations — at day 20 the rank-1, one-day seed still carries
λ¹⁹ ≈ 31% of the estimate (RiskMetrics puts λ=0.94's effective memory at ≈74 days), so hedge
β̂/ρ² are noisy exactly where tier selection reads them. (2) `CurveService` loads quoted tenor
rates directly as ZERO rates; real SOFR quotes are PAR rates — par ≠ zero on a sloped curve
(tens of bp at the long end), a sim=prod parity trap, and even in sim the published quote does
not equal the curve's own par rate. (3) Historical VaR uses a 60-day window (min 20) against
the ≥250-day documented standard, and for K < 100 the reported VaR99 is literally the sample's
worst day presented like a calibrated quantile.

## Decision

We will fix all three:

1. **Covariance burn-in + a stricter hedge gate.** `CovMath.ewmaCovariance` seeds Σ with the
   equal-weight (zero-mean) sample covariance of the first `min(20, n/2)` day vectors, then
   runs the EWMA recursion over the remainder — the seed becomes a proper multi-day estimate
   instead of one day's noise. The STATISTICAL hedge tier additionally requires
   `jethro.hedge.min-covariance-days` observations (default **40** = a 20-day seed plus 20
   EWMA steps; the structural tier keeps the book hedged below it, so the stricter gate costs
   nothing). VaR keeps its 20-observation gate with its existing disclosure.
2. **Par → zero bootstrap on the live curve.** `CurveService` calibrates zero nodes so the
   Strata-priced PAR rate of each tenor's OIS reproduces the quote exactly (fixed-point
   iteration on the node vector, `z ← z + (quote − par)`, tol 1e-10; Strata does all pricing —
   the solver is the only loop we own, per ADR-0020). Scenario shifts move the PAR quotes and
   re-calibrate, so ±100bp scenarios are par-space shifts. Non-convergence falls back to
   quote-as-zero with a logged warning — the risk path never dies on a solver. The no-arg
   constructor keeps quote-as-zero as an explicit TEST-FIXTURE mode (pricer tests supply a
   known zero curve; a new calibration test owns the round-trip property).
3. **250-day VaR window + honest VaR99 labeling.** `VarService.WINDOW_DAYS` 60 → **250**
   (Basel/FRTB convention), fed by the real-history seed; below 100 observations the result
   note states that VaR99 is the worst observed day, not a calibrated quantile.

## Alternatives considered

**Raise min-observations only (no seed change).** Cheaper, but the rank-1 seed still dominates
early estimates and the gate would need ~74 days to compensate — a long unhedged-statistics
window for no reason. Rejected; the seed fix attacks the cause.

**Full Strata curve-group calibration (`RatesCurveCalibrator`).** The heavyweight-correct
answer (multi-curve, Jacobians). Deferred — one USD OIS curve with 5 nodes doesn't need it;
trigger: a second curve (funding/projection split) or cross-currency discounting.

**GARCH(1,1) instead of EWMA.** Better vol dynamics, but fitted parameters need far more data
and break the "reproducible from stated constants" property. Rejected for now; EWMA λ=0.94 is
the documented standard the platform cites.

**Keep 60-day VaR and just relabel.** Honest but weak — the real-history seed makes 250+ days
available, so the window should use them. Rejected.

## Consequences

- Positive: hedge β̂/ρ² noise drops materially at the gate; the quoted curve and its own par
  rates become self-consistent, and live par quotes will price correctly the day they arrive
  (sim=prod parity); VaR quantiles move toward their documented estimation standard; scenario
  shifts become par-space (what a desk quotes).
- Negative: swap PV/DV01 shift slightly versus the quote-as-zero era (the OLD numbers were the
  biased ones); curve builds cost a few dozen Strata pricings (memoized per quote-set); the
  statistical hedge tier engages ~20 days later than before (covered by the structural tier);
  250-day VaR needs the history seed to be useful sooner than accrual.
- Follow-ups: fixing archives for real SOFR resets (replaces the flat-fixings approximation);
  Strata curve-group calibration behind the stated trigger; register rows for the review's
  remaining P2 items ride with this change.

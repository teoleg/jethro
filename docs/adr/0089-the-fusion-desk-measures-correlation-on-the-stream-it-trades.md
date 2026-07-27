# ADR-0089: The fusion desk measures correlation on the stream it trades

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, risk, fusion, sizing

## Context

The fusion sizer builds the book one name at a time. `jethro.fusion.unit-notional-usd` is the
cash-at-risk **per name** at a typical forecast, and `TargetPlanner.targetQuantity` applies it to each
instrument in isolation. Sizing each name alone *is* the independence assumption: a cross-section of N
names sized that way carries N independent budgets, and that is the right answer only if the names are
mutually uncorrelated. Two controls exist to correct it, and they are the only two that look at the
book rather than at a name:

- **ADR-0079** (`PortfolioRiskNormaliser`) scales the whole book by `PDM = min(1, σ_indep/σ_actual)` —
  N perfectly correlated names collapse to one budget of risk.
- **ADR-0083** (`VolatilityBudget`) splits the same per-name cash by each name's own σ so every name
  contributes equal standalone risk.

Both read a `ReturnCovarianceSource` built from the `daily_close` series. Under ADR-0073 that series is
scoped by feed mode, and an instrument enters the estimate only after enough admissible **consecutive
sessions in the running mode**. One session is one point; two are one return. So on a stream that has
been running a couple of sessions the estimate covers *nothing*: the parametric VaR built on the very
same covariance currently reports its entire exposure as **skipped**, and both controls fall through to
their documented "no measurement, no claim" branch — which is to leave the book exactly as planned.

That is not a conservative failure. It is the failure the controls were written to prevent, and it is
live right now: the planner's top-of-book is short nearly every name it covers at once, the combined
forecasts point overwhelmingly one way, and the desk is grinding toward that plan at the ADR-0080 rate
with gross exposure rising cycle over cycle while PnL falls. A book that is short 20 names of one
correlated equity cross-section is arithmetically **one position**, and the only control that would say
so is looking at a series that has not had time to exist.

This is the same coverage gap ADR-0086 hit for σ, one layer over: *"a risk sensor silent exactly where
risk sits is not a risk sensor — before keying a control on a measurement, check its coverage on the
live book, not its correctness."* ADR-0086 answered it by measuring σ on the mark stream. The pairs were
left behind.

## Decision

We will measure the covariance the two fusion sizing controls consume **on the mark stream**, and size
each cycle with whichever of the two estimates actually covers more of the book being planned.

- **`StreamCovariance`** — EWMA of return cross-products over SYNCHRONISED samples: one snapshot of
  every planned name's mark per fusion cycle, `cᵢⱼ ← (1−α)cᵢⱼ + α·rᵢrⱼ`, `α = 2/(span+1)`, running mean
  until a pair has absorbed a full span and silent before that (ADR-0066's lesson, as in
  `StreamVolatility`). It is fed **the same prices the plan was just made from** — one read of the mark
  per cycle — so the sizes and the correlation that scales them cannot come from two different instants.
- **Seeded synchronised** (`SensorWarmup.jointSeedSamples`): the durable mark history replayed as
  snapshots on ONE bucket grid of the store's own provider clock, because a covariance of returns taken
  at different instants measures the misalignment. Each name is read at a grid point from its most
  recent print at or before it — exactly the live semantics, where a name that has not printed reads its
  last mark from the cache. The walk truncates at a hole on the same `GAP_TOLERANCE_SAMPLES` the
  per-name seed already uses. Without a seed the estimator's hour-long warm-up would exceed the process
  lifetime and this control would be dead code (ADR-0071).
- **One estimator per cycle, chosen by coverage, ties to the incumbent.** The mark-stream estimate is
  used only when it covers strictly more of the planned book than the daily-close one. A matrix stitched
  from both is not a covariance of anything — its off-diagonals would be measured over a different period
  than its diagonals. So this can only ever ADD coverage; on a desk whose daily series has matured, the
  behaviour is exactly what it was. `covarianceBasis` on the target book discloses which one sized it.

**Units, and why they cannot move a size.** The stream estimate is a per-sampling-interval variance
where the daily-close one is a per-day variance. It is deliberately *not* rescaled: rescaling requires
asserting how many intervals make a trading day, a convention this layer has no business inventing
(a continuous synthetic stream and a cash-equity session disagree, and the choice would silently move a
risk number). It is safe to leave because **both consumers are homogeneous of degree zero in Σ**:

```
PDM = √(Σᵢ eᵢ²Σᵢᵢ) / √(Σᵢ Σⱼ eᵢeⱼΣᵢⱼ)      Σ → kΣ  ⇒  both roots × √k  ⇒  PDM unchanged
kᵢ  = σ_ref / σ̃ᵢ ,  σ_ref = harmonic mean(σ̃)  σ → √k σ ⇒ σ_ref × √k ⇒ kᵢ unchanged
```

and the winsorisation preceding `kᵢ` is rank-based. Nothing downstream consumes an absolute σ from this
class. Both properties are pinned as tests.

**No new money number.** Two dials, both conventions: `stream-covariance.enabled` (which measurement,
not how much) and `stream-covariance.span` (EWMA memory in samples, doubling as the warm-up; 120 at the
30s cadence is an hour, the same span the ADR-0086 σ sensor uses). `unit-notional-usd` is untouched.

Worked example, pinned as a test. Span 2; two names printing 100 → 110 → 99 together. `rA = rB` at every
step, so `var(A) = var(B) = cov(A,B) = (ln1.1² + ln0.9²)/2 = 0.01009244…` and `ρ = 1`. At equal notionals
`e`: `σ_indep² = 2e²v`, `σ_actual² = 4e²v`, so `PDM = √(2/4) = 1/√2 = 0.7071…` — exactly the `1/√N`
ADR-0079 promises for N names that are one bet. Reverse the second name (100 → 90 → 99) and `σ_actual`
collapses below `σ_indep`, the ratio exceeds 1, and the cap discards it: `PDM = 1`.

## Consequences

- **One-way, structurally.** PDM is capped at 1 and the vol budget carries a gross cap of 1, so an error
  in this estimate can only make the desk carry **less** exposure than it planned, never more. That is
  what makes measuring a fresh statistic here safe without an owner sign-off.
- **The expected first effect is a smaller book**, because the current cross-section is close to one
  bet. That is the intended correction, not a side effect: the same forecast, sized for the risk it
  actually carries. PnL per unit of exposure is the target; gross falling on an unchanged view moves it.
- A carried-forward mark contributes a zero return, biasing a covariance toward zero — i.e. toward NOT
  shrinking the book. The conservative direction for a one-way control, and stated rather than hidden.
- Two live estimates of the same statistic now exist (stream and daily-close), which is the same latent
  inconsistency ADR-0086 already booked for σ. Deferred-register row extended to cover the pairs.
- Sample size: an EWMA span of 120 joint samples against a couple of dozen names estimates a covariance
  that is full rank but noisy (error ~ O(N/T)). Nothing here inverts Σ — both consumers are quadratic
  forms — so the noise moves the size of the haircut, never its sign. A shrinkage estimator
  (Ledoit-Wolf) is the obvious refinement; deferred rather than assumed.
- The deterministic floor — pre-trade guardrail, firm drawdown breaker, invariant-7 gates — is untouched.
  This changes only how much of a planned book survives to the executor.

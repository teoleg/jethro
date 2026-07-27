# ADR-0088: The conviction the desk sizes on is averaged over the horizon it is graded on

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, execution, cost, risk, turnover

## Context

ADR-0080 reconciled two numbers that described the same trade: the desk's **holding period** and the
**horizon its edge is measured over**. It made the position's exposure e-fold toward its target with a
time constant equal to the measured horizon, so that one round trip is paid per horizon of credited
return. That identity is right, and it is only half of the problem.

It constrained the **position**. It said nothing about the **signal**.

A forecast that reverses far faster than the horizon it is graded over drags the position after it
anyway. The mechanism is entirely inside controls the desk already owns, and the ADR-0080 asymmetry —
correct on its own terms — is what makes it fast:

1. the fused forecast flips sign;
2. the target flips from `+2×` to `−2×` the unit notional;
3. `TargetPlanner.orderDelta` splits the gap at flat and trades the **risk-reducing leg in full, this
   cycle** (deliberately: "a cut that waits is not a cut");
4. the far side then rebuilds at the slow derived rate — until the forecast flips back, and the full-speed
   leg fires again in the other direction.

A sinusoidal view becomes a sawtooth of trades. Adding is slow; *reversing* is not, because a reversal
begins with a reduction. The no-trade band cannot catch it either: the band is `|target| × bufferFraction`
and is tested against the whole gap, so on a sign flip the gap is `|target| + |current|` and clears a
50% band trivially. The band protects against small corrections and is silent exactly when the target
moves most.

**The measured evidence on this book.** In the observed window the desk traded one name, MSFT — the only
equity whose measured round trip currently clears the ADR-0075 per-name cost test — **74 fills**, running
`… BUY 8 → BUY 4 → BUY 5 → BUY 6 → BUY 2 → BUY 3 → BUY 1 → SELL 16 → SELL 6 → SELL 9 → SELL 2 → SELL 1 →
BUY 16 → SELL 4 …`, for a net position that moved from flat to 21 shares. Roughly a sign flip every three
to five minutes against a **900 s** selected rung: the desk held each view for a fraction of the period it
was being graded on and paid a round trip for each one. MSFT's fee alone is ~1.0 bp per leg, and its
realised PnL over the window is negative while the name's own reversion reading sits pinned at the `+20`
cap.

Both of the desk's live sensors were **at the cap** on that name — `reversion +20`, `trend −20`. A
saturated forecast carries no magnitude information at all: it says only "maximum size, this way", and
when it flips, it flips between the two largest positions the sizer can express. That is the worst
possible input to a control system whose reducing leg is deliberately instantaneous.

This is not a market view and not a parameter to tune. It is the same dimensional error ADR-0080
identified, on the other input.

## Decision

**The desk sizes on its conviction averaged over the horizon that conviction is graded on.**

One first-order (EWMA) filter per instrument on the **combined** forecast, with time constant equal to
the same measured horizon `h` the gate selected (ADR-0082) and the position is held for (ADR-0080):

```
alpha = 1 - exp(-dt / h)          # dt = seconds since this name was last planned
fbar <- fbar + alpha * (f - fbar) # seeded with the first reading
```

`alpha` is **the identity `TargetPlanner.adjustmentRateFor` already derives**, evaluated on the signal
instead of on the trade. A step response e-folds in exactly `h`. There is no new dial and no new number:
`h` is the rung the evidence picked, and `dt` is **measured elapsed time**, not the nominal cadence — so
an irregular tick, a missed cycle, or a name that drops out of the cross-section and returns all behave
correctly (a gap long relative to `h` drives `alpha → 1`, which re-seeds on the current reading rather
than resuming a stale average).

The filter sits between "what do the sources say" and "how big is that position": after the weighted
average and the diversification multiplier, before `TargetPlanner.targetQuantity`. Its output is the value
carried on `Target.combinedForecast`, so the ADR-0059 conviction floor, the operator's target book and the
routed size all read the **same** number the desk actually traded on. `Target.contributions` keeps each
source's **raw** reading, so the difference between the view and its average is visible rather than hidden.

### What this does and does not change

- **A persistent view is untouched.** A constant forecast is its own average. A name in a sustained move
  sizes exactly as it does today — this is not a de-risking of trends, it is a de-risking of noise.
- **An oscillating view is cut toward its mean.** A square wave of amplitude `A` and half-period `T`
  settles at `A·tanh(T/2h)`. At the live pathology (`A = 20`, `T = 240 s`, `h = 900 s`) that is **2.65** —
  below the `min-forecast-to-route = 5.0` conviction floor, so a name whose view reverses far faster than
  the desk can be graded on it stops being routed at all. That floor has existed since ADR-0059 and has
  never bitten, because a saturated forecast never falls below it.
- **It can only ever ask for a smaller book.** `|fbar| ≤ max|f|` over the window by construction (a convex
  combination of capped values), so the filter can never manufacture conviction the sources did not
  produce. It is not, however, pointwise monotone: if the raw reading collapses in one cycle the average
  lags above it, which is the ordinary cost of a filter and is bounded by the cap.
- **No risk control is weakened.** The ADR-0086 trailing cut runs *after* everything here and can still
  set a target flat; the ADR-0064/0072/0075 gate still clamps to reduce-only; the pre-trade guardrail and
  the firm drawdown breaker are untouched. The filter changes how much risk the desk **wants**, never
  whether it may take risk **off**.
- **Feed-agnostic.** Nothing is calibrated to a price level or to this simulator: the only inputs are a
  dimensionless forecast and a measured horizon (invariant 9).

### Dimensionality (invariant 1 / invariant 7)

A forecast is a **conviction** — never a size, a price, or a number entering PnL or risk. It is carried as
a `double` exactly as `combinedForecast`, the diversification multiplier and the ADR-0080 adjustment rate
already are. The money boundary is downstream in `TargetPlanner.targetQuantity`, which is exact decimal
and unchanged by this ADR. No model output enters (ADR-0016): the arithmetic is fixed and the horizon is
measured.

## Consequences

**Positive.** Turnover on a chopping name falls by roughly the ratio of its reversal frequency to the
measured horizon; the cost the ADR-0075 gate charges is then a cost the desk actually incurs once per
horizon, as the gate assumes. Cross-sectional selection improves as a side effect: with the raw sensors
saturating on many names, the combined forecasts were nearly all the same magnitude, and the average
separates a name whose view is *sustained* from one whose view merely *spikes*. Realised holding period
converges on the horizon the expectancy was measured over, which is the precondition for that measured
expectancy to be earnable at all.

**Negative / honest limits.**

- **Lag.** A genuine reversal is acted on later, by construction. For a mean-reversion source at a 900 s
  rung this is the trade being made deliberately: the desk gives up the first part of a reversal in
  exchange for not paying for the false ones. If the ledger shows the desk consistently late on real
  turns, the answer is a **shorter selected rung** (which the ladder will pick on its own if the fast
  edge is real), not a hand-tuned filter.
- **The signal telemetry still grades the raw sensor call, not the average.** The gate therefore measures
  a source's expectancy on a series the desk no longer trades verbatim. This is the same relationship
  every other portfolio-construction step already has with the telemetry (the volatility budget, the
  diversification multipliers, the vol-target sizing are none of them reflected in source scoring) — but
  it is a real gap and it is filed on the deferred register.
- **Cold start.** The average is seeded at the first reading rather than at zero, so the desk behaves as
  it does today on the cycle after a restart. A filter starting from zero would leave a freshly-deployed
  desk unable to hold a view for a whole horizon; on a box that redeploys every ~30 minutes that is a
  control that does not exist (the ADR-0071 lesson).

**Reversibility.** `jethro.fusion.forecast-smoothing.enabled=false` restores the previous planned book
byte-for-byte (`ForecastSmoother.NONE` is the identity, and the 7-argument `FusionPlanner.plan` overload
is retained and delegates to it).

## Alternatives considered

- **Smooth each source rather than the combination.** Closer to Carver's own practice and it would keep
  the telemetry honest. Rejected for now because the ladder selects **one** horizon desk-wide, so both
  filters would have the same time constant, and smoothing after the weighted average additionally damps
  a second churn source the per-source form would not: weights that move between cycles. Worth revisiting
  if the ladder ever selects per source.
- **Widen the no-trade band instead.** The band is already 50% of target and does not bind, because a sign
  flip produces a gap larger than the target itself. Widening it far enough to catch a flip would also
  block legitimate first entries, and it treats the symptom (the trade) rather than the cause (a signal
  that changes faster than it can be graded).
- **Make the ADR-0080 adjustment rate symmetric** so reversals are slow too. Rejected: that is exactly the
  change ADR-0080 argued against — it would make the desk slow to cut, which is a worse risk than churn,
  and it would blunt the ADR-0086 exit.
- **Cap turnover directly** (a trades-per-name-per-horizon budget). Simple, but it is an arbitrary number
  gating money with no provenance, and it silently drops whichever trade happens to arrive last rather
  than the one with the least evidence behind it.
- **Fix the saturation instead** — recalibrate the sensors so the cap is rare. Plausible and possibly
  additive, but it is a change to two sensors' normalisation with no measurement yet saying the scalars
  are wrong (a range-position reversion signal is *supposed* to peak at a range extreme). Averaging is the
  smaller, better-understood, and strictly more conservative move.

## References

- ADR-0080 (the position half of this identity), ADR-0082 (the measured horizon), ADR-0059 (the conviction
  floor this makes effective), ADR-0055 (the fusion layer), ADR-0086 (the exit, unaffected).
- Gârleanu & Pedersen, "Dynamic Trading with Predictable Returns and Transaction Costs", *JF* 68(6), 2013 —
  the optimal policy trades toward a **weighted average of current and future expected signals**, and
  weights each signal by its own persistence. Averaging a fast signal down is that result's direct
  implication for a signal whose decay rate is short relative to the trading cost.
- Carver, *Systematic Trading* (2015) — forecasts are EWMA-smoothed, and a forecast pinned at the cap is a
  forecast carrying no size information.

*(ADR numbers 0085 and 0087 are retired and unused: both were written by the improvement loop and then
reverted by its scorer.)*

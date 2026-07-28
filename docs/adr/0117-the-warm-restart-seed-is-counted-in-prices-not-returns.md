# ADR-0117: The warm-restart seed is counted in PRICES; the estimator counts RETURNS

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** trading, fusion, risk, warm-start

## Context

ADR-0071 exists because a sensor whose warm-up exceeds the process lifetime never speaks at all. This
desk redeploys roughly every thirty minutes, so the two mark-stream risk estimators — `StreamVolatility`
(the per-name σ that ADR-0086's trailing risk cut measures its stop distance in) and `StreamCovariance`
(the joint estimator ADR-0089's concentration control scales the book with) — depend entirely on that
seed to be useful at all.

Both estimators are denominated in **returns**. Both say so in their own `update`: the first price a
series hands them "is a price, not yet a return". Both expose the requirement as
`warmupSamples() == span`, and both are gated on it — `sigmaPerSample` is empty while
`returns < span`, `covariance` is empty while `samples < span`.

`FusionLifecycle` seeds both by replaying that many **points**:

```java
SensorWarmup.warm(markHistory, instrument, anchor, step, streamVol.warmupSamples(), …)
SensorWarmup.jointSeedSamples(markHistory, instruments, anchor, step, streamCov.warmupSamples())
```

N points produce N−1 returns. So the replay lands **exactly one return short — every time, for every
name, at any depth of history.** This is arithmetic, not a data shortage: no amount of stored history
fixes it, because `seedPrices` caps the walk at the number of samples it was asked for.

It is visible in this run's log, in the two places where the store actually supplied everything asked
for:

```
risk-cut σ sensor still cold for NQ after seeding 120 of 120 stored prices
fusion covariance still cold after seeding 120 synchronised snapshots of 4 name(s)
```

"120 of 120, still cold" is the defect printing its own diagnosis. The live endpoint agreed:
`streamVolMeasuredNames: 1` against `covarianceCoveredNames: 6`, and `riskCuts: []`.

**Why one sample matters here, when normally it would not.** Before ADR-0113/0116 the missing return
arrived on the next planning cycle — 30 s, invisible. Since those ADRs both estimators advance on the
*tape's* clock, so the missing return arrives on the next genuine **print**. Against ADR-0114's measured
inter-print gaps — ~90 s on NQ, ~13 min on GBPUSD, ~20 min on ES — the warm restart therefore hands over
a sensor that is still mute for a large fraction of a thirty-minute process lifetime, on names the book
holds. The covariance is worse: it needs one more snapshot in which the *pair* both print, so its delay
is the slower member's, and with no covered name the concentration control makes no claim about the
whole book. That is precisely the failure ADR-0071 was written to eliminate, re-entered one sample wide.

The direction of the harm is the expensive one in both cases. A cold σ means ADR-0086 makes no claim, so
a losing position is **not** cut. A cold covariance means ADR-0089 does not shrink a concentrated book.

## Decision

**Each estimator states how long a replay it needs, in the unit the replay is counted in.**

- `StreamVolatility.warmupPrices()` and `StreamCovariance.warmupSnapshots()` return
  `warmupSamples() + 1`, documented as what they are: a return needs two prices, so completing a
  warm-up of N returns requires N+1 points.
- `FusionLifecycle`'s two seed call sites ask for that number, and the "still cold" WARN quotes it — so
  `n of n, still cold` can from now on only ever mean a genuine cold start, never this bug.

The requirement stays derived from the estimator's own `span` rather than restated by the caller, which
is the same single-source-of-truth rule `warmupSamples()` already served; the `+1` lives with the class
that knows why it is there.

`warmupSamples()` itself is unchanged, keeps its meaning (returns), and keeps every existing caller and
test byte-identical. Nothing about either estimator's arithmetic, decay, or gate moves.

**No dial and no number needing provenance** (invariant 7 / ADR-0016): `+1` is the count of returns
derivable from a price series, not a calibration. Nothing here sizes, prices, or gates money — the
estimators publish dimensionless statistics, and every control between them and a fill (ADR-0075 edge
gate, ADR-0059 conviction floor, the pre-trade guardrail, the ADR-0027 firm breaker) is untouched.
Prices stay exact decimal end to end and only their ratio becomes a `double`, exactly as before
(invariant 1). It reads a feed, a delayed feed, a replay and a simulated clock identically (invariant 9).

## Consequences

**What improves.** Both estimators can now actually complete their warm-up from durable history at boot,
which is what ADR-0071 promised. The risk cut is armed on a seeded name from the first planning cycle
instead of after that name's next print, and the concentration control has covered pairs at boot.

**Honest costs.**

- This is not free protection — it is *earlier* protection, and an armed risk cut can cut. A name whose
  σ is now measured at boot may be stopped out in a window where it previously would have been left
  alone. That is the control working as ADR-0086 intends, but it is a real behavioural change and it can
  lose money in a whipsaw.
- The seed reads one extra point per name, and the lookback (`step × samples × 2`) widens by the same
  one step. Negligible, but it is one more point of history the walk must find; a name whose stored
  series is exactly `span` long stays cold until it prints once more, as it does today.
- It does not repair a name that is short of history for real (AAPL at 99 of 120 in this run) or one
  whose price never moves (the Treasury curve republishes a daily par yield, so its variance is exactly
  zero and it is correctly silent). Those are different problems and are left alone.

**Rejected alternatives**, recorded so they are not re-attempted:

- *Redefine `warmupSamples()` to mean prices.* One number, one meaning, no `+1` at the call site — but
  the estimators' internal gates are in returns, so the redefinition would have to be undone inside each
  class, moving the off-by-one rather than removing it, and silently changing what every existing caller
  and test of `warmupSamples()` asserts.
- *Have `SensorWarmup` add the `+1` itself.* It has no idea whether its consumer counts prices or
  returns; a trend forecaster's warm-up is already correctly expressed in prices (`slowSpan + 1 + …`, the
  `+1` being this same first-price term, accounted for). Putting the correction in the shared helper
  would break the sensors that already have it right.
- *Lower the spans so the estimators warm sooner.* The span defines what the statistic is. Shortening it
  to fit a counting bug changes the risk measurement to work around arithmetic.

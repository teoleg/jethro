# ADR-0067: Weight fusion sources by measured expectancy, not by hit rate

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** backend, trading, signals, fusion

## Context

ADR-0055 item 6 replaced equal source weights with evidence-based ones: each forecast source enters
the combine with a weight proportional to its measured directional skill, `advantage = max(0, 2·hitRate − 1)`,
shrunk toward the pooled prior by Bühlmann credibility and bounded around the 1.0 null. The intent —
"down-weighted by measurement, not decree" — is right. The statistic is not.

Two defects, both visible on the live desk. First, the floor at zero makes the statistic **blind
exactly when it matters most**. Every source at or below a coin flip maps to advantage 0; when the
whole desk is losing, the pooled mean is 0 too, and the method takes its "no measured edge anywhere"
branch and returns 1.0 for everyone. On the current SIM session all three routed sources sit below a
coin flip — including one with 22 decisive observations and a mean return significantly below zero —
and the fusion endpoint duly reports `{trend: 1.0, social: 1.0, momentum: 1.0}`. A source the desk has
measured to be losing money carries exactly the conviction of one it has not measured at all. The
period when discrimination is worth the most is the one period the method cannot discriminate in.

Second, hit rate is the wrong quantity for money. It is the sign-based proxy for Grinold–Kahn's
information coefficient, and that proxy is only faithful when payoffs are symmetric. A trend follower
is *designed* to be right well under half the time and to be paid by asymmetry; ranking it on hit rate
reads its intended shape as failure, while a source that is right often and loses on the few times it
is wrong reads as skilled. What determines whether a source is worth listening to is its expectancy
per call relative to the noise in it — the quantity the telemetry already records as `avgReturnBps`
and `stdReturnBps`, and which the ADR-0064 gate already uses, but which the weights ignore entirely.

Doing nothing leaves the sole order origin equal-weighting a measured loser against its peers for as
long as the desk is underwater, which is precisely when its conviction should be moving.

## Decision

We will weight each fusion source by **Φ of its own sample t-statistic of expectancy**, replacing the
floored hit-rate advantage, and keep every surrounding mechanic (credibility shrinkage, the pooled
prior, the min-sample floor, the MIN/MAX bound) unchanged:

```
t_s      = avgReturnBps_s / stdErrorBps_s      // 0 when there is no standard error yet
e_s      = Φ(t_s) ∈ (0,1)                      // evidence the true expectancy is positive; Φ(0) = ½
shrunk_s = c_s·e_s + (1 − c_s)·pool,  c_s = n_s/(n_s + K),  pool = mean_s(e_s)
w_s      = clamp(shrunk_s / mean_s(shrunk_s), MIN, MAX)
```

`e_s` folds magnitude, dispersion and sample size into one bounded number and has no flat region, so
two losing sources still rank against each other. Φ is a statistical convention, not a money number,
and introduces no new dial: `K`, `MIN`, `MAX` and `min-sample` keep their configured values and their
meaning. Expectancy is measured **gross** of execution cost on purpose — cost decides whether the desk
should pay to trade at all, which is ADR-0064's job; these weights decide only whose view counts more
among sources that all face the same cost.

Two properties hold by construction and are locked in by tests. With no evidence, every `e_s` is ½,
so every weight is exactly 1.0 — equal by symmetry rather than by a fallback branch. And because
`ForecastCombiner` normalises by Σweights and `MIN > 0` keeps every source active, re-weighting can
**rotate conviction between sources but can never scale the target book**: the same forecasts under
any weights produce the same combined value, the same active-source count, the same diversification
multiplier, and therefore the same position.

## Alternatives considered

**Keep hit rate, drop only the zero floor (`advantage = hitRate`).** The minimal edit, and it does
restore discrimination among losing sources. Rejected because it inherits the deeper defect: it still
cannot tell a trend follower's designed low hit rate from genuine failure, and on the live cross-section
it actually *up-weights* the worst-measured source, because a second source's 0-for-2 record drags the
pooled prior below it. A statistic that gets the current desk backwards is not worth keeping.

**Invert measured-negative sources (trade them contrarian).** The telemetry says the trend source's
mean return is significantly below zero, so flipping its sign is superficially free money. Rejected:
this is the canonical backtest overfit (Harvey, Liu & Zhu, *RFS* 2016) — a negative window is evidence
against an edge, not evidence for the mirror edge — and it converts a measurement error into a
position. Φ is bounded in (0,1) precisely so a bad source is quietened toward MIN, never inverted.

**Weight on expectancy net of measured execution cost.** Tempting, since net-of-cost is what the desk
keeps. Rejected as double-counting: ADR-0064 already charges the cost hurdle once, at the gate, and
the cost is common to all sources, so subtracting it inside a Σ-normalised relative weighting adds
noise without adding information. Revisit if per-instrument costs land and sources come to trade
materially different name sets — the trigger is in the deferred register.

**Drop a measured-bad source entirely (weight 0).** Rejected: the combiner skips weight ≤ 0, which
would change the active-source count and hence the diversification multiplier, so "silence one source"
would silently resize the book — the one thing this design is built not to do.

## Consequences

- **Positive:** the desk stops giving a measured loser the same conviction as an unmeasured peer. On
  the live cross-section the one well-sampled source, which is significantly below zero, drops toward
  the MIN bound while thin sources stay neutral. Sources with asymmetric payoffs are finally judged on
  the money they make rather than on how often they are right. The mechanism is two-sided, so a source
  that starts earning is up-weighted by the same arithmetic that quietened it.
- **Negative:** expectancy is a noisier statistic than hit rate at a given sample size — it is
  sensitive to a single large observation, where hit rate is not — so weights will move around more
  run to run. The min-sample floor and the credibility shrinkage bound that, but they do not remove
  it. Φ also assumes a normal reference distribution for the sample mean; per-observation returns are
  fat-tailed, so `e_s` overstates confidence in the tails. Both are arguments about how *fast* trust
  should move, not about which direction it should move in, and the MIN/MAX bound caps the damage.
- **Neutral but worth stating:** this changes no exposure. It cannot open a position, cannot enlarge
  one, and leaves the deterministic floor (conviction floor, backtest veto, pre-trade guardrail, firm
  breaker) and the ADR-0064 gate exactly where they were.
- **Follow-ups:** the weights now consume `stdReturnBps`, so a source that reports dispersion badly
  will mis-weight itself — worth an assertion in the telemetry layer. If per-instrument execution cost
  lands (deferred register), revisit whether weighting should move to net-of-cost expectancy.

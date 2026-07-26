# ADR-0077: Measure a source's expectancy across emission cohorts, not across observations

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** trading, signals, statistics, risk, edge-gate

## Context

Every decision the fusion desk makes about risk turns on one number per source: `stdErrorBps`, the
standard error of that source's measured expectancy. The edge gate (ADR-0064/0072/0075) asks whether
`(avgReturnBps − cost)/stdErrorBps ≥ 2.0` before it will let the desk put risk on at all, and
`TelemetryWeights` (ADR-0067/0074) maps `Φ(avgReturnBps/stdErrorBps)` onto how much each source's view
counts. It is the single statistic standing between a measured edge and the money.

It was computed as `stdReturnBps / √resolved` — the i.i.d. estimator, which assumes every resolved
observation is an independent draw. On this desk that assumption is false, and not marginally so. The
forecast sources emit their **whole cross-section in one burst**: `SignalTelemetry.record` is called once
per name in a single sweep, so all 23 names are stamped within a few hundred milliseconds and resolve
together one horizon later. The database shows the shape unambiguously — `trend`'s 115 resolved
observations are **five** hourly bursts of 23, and `reversion`'s 23 are **one** burst, recorded
2026-07-26 18:08:17 and resolved 19:08.

Those 23 numbers are 23 views of the *same hour of market*, not 23 independent draws. Dividing their
dispersion by `√23` understates the standard error by `√(1 + (n−1)ρ̄)`. Measured on the live `trend`
cohorts the understatement is **2.23×**:

| estimator | mean (bps) | std error (bps) | t vs the cheapest round trip (0.291 bps) |
|---|---|---|---|
| i.i.d. over 115 observations | −13.3083 | 27.7797/√115 = 2.5905 | **−5.25** |
| across the 5 cohorts that varied | −13.3083 | 12.8985/√5 = 5.7684 | **−2.31** |

`reversion` is the acute case. Its `+4.03 bps` reading — the one the gate is waiting on — comes from a
single cross-section. There is no standard error to form from one draw, yet `σ/√23 = 2.429` manufactured
one, and with it a t-statistic of 1.54. On the next resolution the raw count crosses `minSample = 30`
and the naive t reaches ≈2.17, at which point the gate opens and the desk levers into the full
23-name target book **on the strength of two hourly snapshots**. The ledger already records that exact
failure once: ADR-0067's continuous risk appetite took gross exposure from $0 to $43,624 with no PnL
gain, scored ❌ BAD, and was auto-reverted. The pending book is several times larger.

Note this cuts both ways, and that is the point. The same inflation makes `trend` look decisively
anti-predictive (t = −5.25) when the honest reading is a much weaker −2.31, and it will just as readily
certify a *good* source on evidence that is not there. An overstated t-statistic is not conservative in
either direction; it is simply wrong, and it is wrong on the number that gates exposure.

## Decision

**We will estimate each source's expectancy and its standard error across emission cohorts rather than
across observations** — the standard treatment of a cross-sectional signal (Fama & MacBeth, *JPE* 1973):
reduce each burst to one number, then do the statistics over bursts.

For a source's resolved observations grouped into cohorts `b = 1..B` by entry time:

```
r̄_b            = mean directional return of cohort b
avgReturnBps   = mean_b(r̄_b) · 1e4                       // each draw counts once
stdCohortMean  = sample sd of r̄_b (Bessel) · 1e4
stdErrorBps    = stdCohortMean / √B,  and 0 when B < 2    // one draw supports no standard error
```

Two observations belong to the same cohort when their entry times are within
`jethro.signals.cohort-window-seconds` (default 60s) of one another.

Consequences that follow from the formula, not from a special case:

- **A staggered emitter is untouched.** `momentum`, `social` and `mean-reversion` call one name at a
  time, so every cohort has one member, `B = resolved`, and the estimator is *byte-identical* to the
  i.i.d. one. There is no discontinuity at the boundary and no branch.
- **Equal-sized cohorts leave the point estimate unchanged.** `mean_b(r̄_b)` equals the pooled mean
  exactly when every burst spans the same names — which a full-cross-section emitter's always do. Only
  the standard error moves. Where cohort sizes differ, equal-weighting the time periods is the correct
  reading of a time-series expectancy, not an incidental effect.
- **One cross-section reads as no evidence.** `B = 1 ⇒ stdErrorBps = 0`, which the edge gate already
  treats as non-passing (`se > 0` is a precondition) and `TelemetryWeights` already reads as `t = 0 ⇒
  Φ(0) = ½ ⇒ neutral weight`. Both consumers get the honest answer through paths that already exist.
- **`resolved` keeps its meaning** — raw observation volume — so `minSample` continues to guard volume
  while `tHurdle` guards significance, now on an honest standard error. Neither dial changes value.
- **Direction of the change: strictly more conservative on today's data.** Merging observations into a
  cohort can only lower `B` and widen the standard error. The gate stays shut this cycle; what it buys
  is that when the gate does open, it opens on evidence that exists.

`cohort-window-seconds = 60` is **mine**, not a market convention: chosen two orders of magnitude above a
burst's observed width (~300ms) and two orders below the 1h emission interval, so the grouping is
insensitive to the exact value. Its error direction is safe — merging too eagerly understates `B` and
widens the standard error (stricter gate), whereas splitting a true burst would overstate significance.

## Alternatives considered

- **Cluster-robust standard errors on the pooled mean.** Equivalent in intent, but the unequal-cluster
  form is fiddly and its degenerate case (singleton clusters) is not obviously the i.i.d. estimator.
  Fama–MacBeth reduces to it visibly, which matters for a statistic nobody reviews by hand.
- **Estimate ρ̄ and apply a `√(1+(n−1)ρ̄)` inflation factor.** Requires estimating a correlation from
  the same thin sample, adds a dial, and assumes equicorrelation. The cohort estimator needs neither.
- **Leave it and raise `tHurdle` instead.** Rejected: it would paper over an incorrect statistic with an
  arbitrary number, and it would not fix the weights, which consume the same t.
- **Do nothing this cycle and let the gate open.** Rejected: it walks knowingly into the ADR-0067
  failure mode at several times the size, on a book already underwater.

## Consequences

- The edge gate and the fusion weights now consume a standard error that reflects the sample that
  actually varied. The desk stays reduce-only until a source shows a repeatable edge across *hours*,
  not across names within one hour.
- Evidence now accrues at one independent observation per source per horizon (1/hour), which is the
  honest rate — and it is slow. The natural follow-up is to raise the **emission rate** (overlapping
  cross-sections at, say, a 10-minute cadence against the same 1h horizon), which multiplies independent
  draws per hour; that requires a Newey–West/Hansen–Hodrick correction for the induced overlap and is
  deliberately left to its own change. It must come *after* this one: raising the rate on the i.i.d.
  estimator would have inflated significance faster still.
- `SignalScoring.Stats` gains `cohorts` and `stdCohortMeanBps`, surfaced on `/api/signals/telemetry`, so
  any verdict can be recomputed from the record. The legacy 9-argument constructor is retained and means
  "every observation is its own cohort" — the i.i.d. reading, stated explicitly rather than assumed.
- The deterministic floor (pre-trade guardrail, firm drawdown breaker) is untouched. This sits strictly
  above it, and only ever makes the gate *harder* to open.

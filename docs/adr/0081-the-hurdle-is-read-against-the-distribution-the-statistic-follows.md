# ADR-0081: The edge gate's hurdle is read against the distribution its statistic actually follows

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** backend, fusion, risk, statistics, edge-gate

## Context

The ADR-0064 edge gate is the single most valuable control this desk has. It is the only change in the
improvement ledger ever scored ✅ GOOD, and it earned that verdict by *stopping* the desk from trading.
Every dollar of the strategy book's realised loss is transaction cost — spread and fees on round trips
that netted to flat. So the question "may the desk put risk on?" is, empirically, the question that
decides whether this desk makes or loses money, and the standard of evidence it applies is the whole
mechanism.

That standard has a hole in it, and ADR-0077 is what opened it.

**What ADR-0077 established.** A forecast source emits its entire cross-section in one burst — 23 names
called in the same 200 ms, resolved together one horizon later. Those 23 numbers are 23 views of the
same hour of market, not 23 independent draws. So the expectancy is estimated Fama–MacBeth style: reduce
each burst to one number, then do the statistics over bursts. The standard error is
`sd(cohort means) / √B`, where `B` is the number of independent emission cohorts.

**What that changed about the statistic.** The gate's test is

```
t = (avgReturnBps − roundTripBps) / stdErrorBps        clears when  t ≥ tHurdle  (= 2.0)
```

Before ADR-0077 the denominator was formed from dozens or hundreds of observations, and treating `t` as
approximately standard normal was defensible. After ADR-0077 the denominator is estimated from `B`
cohorts — and `B` is *small*, structurally and for a long time. `SignalTelemetry.record` holds one open
observation per (source, instrument) and the horizon is 3600 s, so a cross-sectional source produces
**one cohort per hour**. The live telemetry shows this plainly: `reversion` has 69 resolved observations
in **3 cohorts**; `trend` has 138 in 6.

A ratio whose denominator is an estimate from `B` draws does not follow a normal distribution. It follows
Student's t on `B − 1` degrees of freedom — the original result, and the original motivation for it
(Student, "The Probable Error of a Mean", *Biometrika* 1908). At `B = 3` that distinction is not a
refinement:

| degrees of freedom | true 97.7% one-sided point | hurdle actually applied |
|---|---|---|
| 2 (3 cohorts) | ≈ 4.5 | 2.0 |
| 9 (10 cohorts) | ≈ 2.4 | 2.0 |
| 99 (100 cohorts) | ≈ 2.07 | 2.0 |
| ∞ | 2.000 | 2.0 |

The dial is documented, correctly, as "a STATISTICAL CONVENTION (~95% two-sided)". At three cohorts it
was delivering roughly a 91% one-sided test while wearing a 97.7% label. The gate was not lying about its
intent; it was applying that intent through the wrong reference distribution, and the error is worst
exactly where the desk lives.

The consequence is not hypothetical. The desk is currently flat and reduce-only with `reversion` reading
+13.5 bps against a 0.35 bps cheapest round trip — a t-statistic just under the fixed 2.0, on three
cohorts. One favourable cohort flips that switch and releases the fusion layer's full planned book. Under
the correct distribution, three draws of the market do not clear a 97.7% bar and cannot be made to; the
gate would open on *accumulated* evidence instead of on a lucky third burst. Given that ADR-0067 — the
one ❌ BAD change in the ledger — opened sizing on a `Φ(t)` reading and was auto-reverted for adding
exposure with no PnL gain (see the scored row for `fb9273505`), the asymmetry is stark: opening wrongly
costs measured money, waiting costs only opportunity.

Note also that `min-sample = 30` does not cover this. It counts *observations*, and 69 observations in 3
cohorts passes it comfortably. It is a floor on the wrong index — the same class of mistake ADR-0079
recorded about the diversification multiplier, one index down.

## Decision

**The gate compares in probability space, against the distribution the statistic actually follows.**

1. `EdgeGate.Params.tHurdle` is reinterpreted — without changing its value or its calibration — as a
   statement of **confidence**, converted once to a one-sided tail probability:
   `α = 1 − Φ(tHurdle)`. At the configured 2.0 this is α = 0.02275, exactly the level the dial always
   claimed.

2. `SourceEdge.clears(cost, params)` forms the same surplus t-statistic and then evaluates its tail
   probability under **Student's t on `cohorts − 1` degrees of freedom**, clearing when `p ≤ α`.
   `cohorts` is the ADR-0077 independent sample and is carried onto `SourceEdge` alongside the resulting
   `pValue`, so every verdict is recomputable from the record.

3. A source with fewer than two cohorts never clears. This is not a new floor — ADR-0077 already sets
   `stdErrorBps` to zero there, and one cohort has zero degrees of freedom, so both routes agree.

4. The evidence list is ordered by `pValue` ascending rather than by t-statistic descending. Across
   sources with different cohort counts those orderings disagree, and only the p-value is comparable:
   t = 2.1 on 100 cohorts is stronger evidence than t = 2.5 on 4.

5. A new pure class `Significance` supplies the tail probabilities: Student's t via the regularized
   incomplete beta identity `P(T > t) = ½·I_{ν/(ν+t²)}(ν/2, ½)`, evaluated by the modified Lentz
   continued fraction with Lanczos log-Γ. It reuses the single existing Φ implementation
   (`TelemetryWeights.standardNormalCdf`) rather than adding a second normal CDF to the codebase.

Nothing else moves. No sizing changes, no cost model changes, no new dial, no new data dependency. The
gate can still only ever *subtract* trades the planner already wanted, and it sits, as before, strictly
above the deterministic floor — the pre-trade guardrail and the firm breaker are untouched.

### Worked example (encoded as a test)

Two sources, each measured at +13.50 bps with an identical standard error of 5.20 against a 0.50 bps
round trip. Both produce the *identical* t-statistic of 2.50. They differ only in how many independent
draws that standard error came from:

```
4 cohorts    se = 10.40/√4   = 5.20   t = 13.00/5.20 = 2.50   df = 3    p ≈ 0.0438  >  α = 0.02275  ✗
100 cohorts  se = 52.00/√100 = 5.20   t = 13.00/5.20 = 2.50   df = 99   p ≈ 0.0071  <  α = 0.02275  ✓
```

The previous rule compared 2.50 against 2.00 and opened the gate in **both** cases.

## Consequences

**Good.** The gate delivers the confidence it advertises at every sample size instead of only
asymptotically. The correction is *exactly* a no-op in the large-sample limit — Student's t converges to
the normal as df → ∞ — so it can only bite where the normal approximation was invalid, and it bites only
in the conservative direction. The dial keeps its meaning and its number, so nothing is recalibrated. The
p-value is a more honest operator-facing statistic than a t-stat, which is uninterpretable without its df.

**Bad / accepted.** The desk stays flat longer. A real edge measured on few cohorts is now refused until
the cohorts accumulate, and at one cohort per measurement horizon that is hours. This is a deliberate
trade: the ledger says this desk loses money by trading and made money by stopping, so the expected cost
of a false open exceeds the expected cost of a late one. It also means this change will very likely score
as no material move — the book is flat and the gate was already shut — and that is expected, not an
accident.

**The lever it makes visible.** The binding constraint on how fast this desk can *earn* permission is now
unambiguously the cohort arrival rate, which is `1 / horizon`. That is a measurement-horizon question,
and it must be settled on evidence about how fast the edge decays — not by shortening the horizon because
a shorter one yields more samples. A shorter horizon shrinks per-observation expectancy against a fixed
round-trip cost, so it is only an improvement if the edge is genuinely fast. Deliberately out of scope
here.

**Risk.** The numerics are new code on a decision path. They are mitigated by testing every value against
something that exists independently of the implementation: published Student-t table points at 1, 2, 3,
4, 10, 30 and 99 df; the closed forms at df = 1 (standard Cauchy) and df = 2; symmetry, monotonicity in
both arguments and the normal limit; log-Γ against exact factorials and Γ(½) = √π. Degenerate inputs
(df < 1, NaN, zero dispersion) read as p = 1 — no evidence — which fails safe.

## Alternatives considered

- **Raise `t-hurdle` to a fixed higher number (e.g. 3.0, per Harvey/Liu/Zhu).** Rejected: it is the same
  error with a different constant. It over-penalises a well-evidenced source at 500 cohorts and still
  under-penalises one at 3, because the shape of the miscalibration is a function of df, not a scalar.
- **A hard minimum cohort count.** Rejected: a discontinuous floor next to a continuous statistic is the
  exact pattern ADR-0074 removed from the weighting path, for the same reason — the t-distribution
  already penalises thin samples continuously and correctly, and a second mechanism only lets the two
  disagree.
- **Bootstrap the cohort-mean distribution.** Rejected: at B = 3 a bootstrap resamples the same three
  numbers and asserts precision it does not have. Student's t is the correct small-sample answer here and
  needs no resampling.
- **Leave it and let the desk trade.** Rejected on the evidence: the one ✅ GOOD change in the ledger was
  the gate shutting the desk down, and the one ❌ BAD change was a relaxation that added exposure for no
  gain.

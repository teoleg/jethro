# ADR-0074: Source trust is shrunk over the sample its estimate was made from — flats included, no hard floor

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** fusion, signals, telemetry, risk

## Context

`TelemetryWeights` (ADR-0055 item 6, statistic per ADR-0067) turns each source's measured track record
into a conviction weight. A source is trusted in proportion to Φ of its own expectancy t-statistic,
shrunk toward the pooled prior by Bühlmann credibility so a thin sample cannot dominate, then centred
on 1.0 and bounded. The weights are ratios that `ForecastCombiner` normalises by their sum, so they can
rotate conviction *between* sources but can never scale the target book — which is exactly why this is
a safe place to be strict.

Two defects were found in that method while reading the live weights against the live telemetry.

### 1. The credibility term counted a different sample than the estimate it was guarding

The evidence statistic is

```
t_s = avgReturnBps_s / stdErrorBps_s ,   stdErrorBps_s = stdReturnBps_s / √resolved_s
```

and both `avgReturnBps` and `stdReturnBps` are computed by `SignalScoring.aggregate` over **every
resolved observation — FLATs included**. That is correct: a FLAT is a call that earned nothing, and it
belongs in an expectancy exactly as it belongs in the P&L.

The credibility term, however, was `c_s = n_s / (n_s + K)` with `n_s = wins_s + losses_s` — the
**decisive** count, FLATs excluded. So the confidence in a statistic was being measured with the sample
size of a *different* statistic. (Hit rate legitimately excludes FLATs: there, "no bet" is the right
reading. Expectancy does not.)

This is not a rounding-level discrepancy, because a source's flat rate is a property of its **horizon
and dead-band threshold**, not of how much evidence it has accumulated. In this feed mode:

| source | resolved | wins | losses | flats | flat rate | measured avg return (bps) |
|---|---|---|---|---|---|---|
| trend | 92 | 18 | 53 | 21 | 23% | −15.9582 |
| momentum | 18 | 1 | 6 | 11 | 61% | −11.2968 |
| social | 12 | 2 | 2 | 8 | 67% | −8.1676 |
| mean-reversion | 2 | 0 | 0 | 2 | 100% | −0.4846 |
| reversion | 0 | 0 | 0 | 0 | — | — |

`mean-reversion` has never produced a single decisive observation and, being a threshold detector,
structurally may never produce one — it could run forever and never be permitted to differentiate.

### 2. The hard min-sample floor was a redundant, asymmetric second copy of the shrinkage

On top of the shrinkage sat a floor: a source with fewer than `weights.min-sample` (20) decisive
observations was pinned at exactly **1.0**, the neutral weight. Its stated purpose — "a thin lucky run
must not up-weight a noise source" — is already served, continuously and in both directions, by the
credibility term: at K = 20 a source with three resolved calls sits ~87% on the pooled prior however
spectacular its reading (worked below: it lands at 1.0056, not 1.0, and that difference is the point —
it is a curve, not a cliff).

Worse, clamping to 1.0 is **not neutral**. For any source whose shrunk evidence sits below the desk
average, 1.0 is strictly *more* trusting than its own measurement. The floor therefore read
measured-negative evidence as *no* evidence, and it did so for precisely the sources whose flat rate
kept them under the threshold.

### What the two defects did together, in the live weights

The desk's published weights this cycle:

```
reversion 1.0   mean-reversion 1.0   momentum 1.0   social 1.0   trend 0.2732572
```

Every source except `trend` sat at full trust. Three of them — `momentum` (18 resolved, −11.2968 bps),
`social` (12 resolved, −8.1676 bps) and `mean-reversion` — carry *measured negative* expectancy and
were nonetheless weighted at the neutral maximum, because their flat rates (61%, 67%, 100%) kept their
decisive counts at 7, 4 and 0. The only source with enough decisive observations to speak was the one
that got down-weighted.

The money consequence is not hypothetical and it is imminent. The ADR-0064 gate is currently shut, so
the book is flat and these weights move nothing. But `reversion` (ADR-0070) began publishing this cycle
with 23 open observations and resolves one horizon out. The moment any source clears the gate, the
planner sizes from the combined forecast — and roughly three quarters of that book's conviction would
have come from views the desk has already measured losing money, at maximum trust.

## Decision

Make the credibility term count the observations its own estimate was made from, and let it be the
whole thin-sample defence.

1. **`n_s = resolved_s`** — every resolved observation, FLATs included, the same sample
   `avgReturnBps_s` and `stdErrorBps_s` average over. Hit rate is unchanged and still excludes FLATs.
2. **Remove the hard min-sample floor** and the `jethro.fusion.weights.min-sample` dial with it. Every
   source receives `clamp(shrunk_s / mean(shrunk), min, max)`. `shrinkage-k` is now the single,
   documented, continuous thin-sample dial.

Nothing else changes: the statistic (Φ of the t-stat), the pooled prior, the [0.25, 3.0] bounds, and
the guarantee that Φ > 0 so a measured-bad source is down-weighted toward the floor and never inverted
into a contrarian bet, all stand as ADR-0067 set them.

### Worked example — the regression, by hand

Two sources resolve 40 calls each with **identical** measured expectancy and dispersion (mean −12 bps,
σ 30 bps), differing only in how their outcomes bucketed: `flat-heavy` landed 3 wins / 7 losses / 30
flats, `decisive` landed 12 wins / 28 losses / 0 flats. A third, `reference`, is their mirror at +12 bps.

```
SE          = 30 / √40            = 4.743416
t           = ∓12 / 4.743416      = ∓2.529822
Φ(−2.529822) = 0.00570604 ,  Φ(+2.529822) = 0.99429396      ← identical evidence for the first two
```

**Before** (`n` = wins + losses): `flat-heavy` has 10 decisive, under the floor of 20 → pinned at
**1.0**; `decisive` has 40 → shrunk to **0.310741**. Identical measurements, a **3.2× conviction gap**,
and the gap points the wrong way — the flat-heavy source is the *more* trusted of the two.

**After** (`n` = resolved = 40 for both, `c` = 40/60 = ⅔):

```
pool              = (0.00570604 + 0.00570604 + 0.99429396) / 3 = 0.33523535
shrunk(flat-heavy)= ⅔·0.00570604 + ⅓·0.33523535 = 0.11554914
shrunk(decisive)  = ⅔·0.00570604 + ⅓·0.33523535 = 0.11554914      ← identical, as it must be
shrunk(reference) = ⅔·0.99429396 + ⅓·0.33523535 = 0.77460776
mean(shrunk)      = 0.33523535
w(flat-heavy) = w(decisive) = 0.11554914 / 0.33523535 = 0.3446807
w(reference)                = 0.77460776 / 0.33523535 = 2.3106387
```

### Worked example — a thin lucky run still buys almost nothing

`social` reports +80 bps over 3 calls (σ 20) against `momentum`'s +10 bps over 100 (σ 50):

```
t(social)   = 80 / (20/√3)  = +6.9282  → Φ = 1.0          c = 3/(3+20)   = 0.130435
t(momentum) = 10 / (50/√100)= +2.0     → Φ = 0.97724994   c = 100/120    = 0.833333
pool = 0.98862497
shrunk(social)   = 0.130435·1.0        + 0.869565·0.98862497 = 0.99010867
shrunk(momentum) = 0.833333·0.97724994 + 0.166667·0.98862497 = 0.97914578
mean = 0.98462722  →  w(social) = 1.005567 ,  w(momentum) = 0.994433
```

A t-statistic of +6.93 on three observations moves the weight by **0.56%**. The floor's job is done by
the shrinkage, without the cliff and without the asymmetry.

### Effect on the live weights

Recomputing the published telemetry above under the new method:

| source | before | after |
|---|---|---|
| reversion (0 resolved) | 1.0 | 1.3248252 |
| mean-reversion | 1.0 | 1.2875445 |
| social | 1.0 | 1.2256224 |
| momentum | 1.0 | 0.9254313 |
| trend | 0.2732572 | 0.2500000 (min bound) |

The ordering is now monotone in measured evidence, and the two sources the desk has measured losing
most (`trend`, `momentum`) are the two that lose conviction. `social` and `mean-reversion` rise only
because the weights are **ratios**: with the desk-wide mean dragged down by the sources that got worse,
being less bad is worth relatively more. That is the intended behaviour of a purely relative weighting
scheme — whether the desk should be paying to trade *at all* is not this method's question, it is
`EdgeGate`'s (ADR-0064), and that separation is deliberate.

## Consequences

**Good.**
- Confidence and estimate are computed from the same sample; the method is internally consistent.
- A source whose calls mostly land in the dead-band can now be judged at all. `mean-reversion` was
  permanently unjudgeable before.
- Measured-negative evidence can no longer be laundered into neutral by a low decisive count.
- One fewer dial (`weights.min-sample` is gone), and the remaining one (`shrinkage-k`) is documented as
  the thin-sample control it always was.
- The weight function is continuous in sample size — no discontinuity at the threshold for a source to
  oscillate across.

**Bad / risks.**
- Weights now move on smaller samples than before. Bounded: credibility keeps a 3-call source within
  0.6% of neutral, and the [0.25, 3.0] clamp holds the extremes. The scheme still cannot invert a
  source or scale the book.
- Weights will now change more often, which will show up as more movement in the fusion telemetry. That
  is measurement being visible, not the book becoming less stable.

**Explicitly not changed.** The deterministic floor is untouched: the pre-trade guardrail, the firm
drawdown breaker, the ADR-0064/0072 edge gate and its hurdles, the conviction floor, and the invariant-7
/ ADR-0016 gates all behave exactly as before. No risk formula, sizing rule, cost model or money
parameter is altered. `ForecastCombiner` normalises by Σweights, so this change cannot alter gross or
net exposure for any given set of forecasts — a property already asserted by
`reWeightingRotatesConvictionButCannotScaleTheBook`.

## Alternatives considered

- **Fix the counter, keep the floor.** Nearly inert: on `min-sample` = 20 measured against *resolved*,
  only `trend` (92) clears it, so four of five sources would still be pinned at 1.0 and
  `mean-reversion` would still be unjudgeable at 2 resolved. It preserves the asymmetry — 1.0 is more
  trusting than the shrunk value for every below-average source — for no benefit the shrinkage does
  not already provide.
- **Raise `shrinkage-k` instead.** Addresses neither defect: it would shrink everything harder while
  leaving the sample inconsistent and the floor's asymmetry in place.
- **Estimate the pooled prior as a credibility-weighted mean**, so a zero-observation source does not
  contribute Φ(0) = ½ to the prior every other source shrinks toward. A real second-order issue — the
  prior currently rises as unmeasured sources are added — but it moves ratios only, and mixing it in
  here would make this change's effect unattributable in the ledger. Left for its own change.

## Deferred

None. `weights.min-sample` is removed rather than deprecated, so nothing is left dangling.

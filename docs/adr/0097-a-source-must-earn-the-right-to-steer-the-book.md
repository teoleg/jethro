# ADR-0097: A source must EARN the right to steer the book — a view that fails the desk's own significance test is held at the minimum weight

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, fusion, signals, risk

## Context

The fusion desk runs two measurements over the same telemetry, at the same selected rung of the
ADR-0082 horizon ladder, with the same two dials (`edge-gate.min-sample`, `edge-gate.t-hurdle`):

1. **The edge gate** (ADR-0064/0075/0077/0081) asks *may the desk put risk on at all?* — a source's
   measured expectancy against its Fama–MacBeth standard error, net of the round trip, read against
   Student's t on `cohorts − 1` degrees of freedom.
2. **The combination weights** (`TelemetryWeights`, ADR-0067/0074) ask *whose view counts more?* —
   `Φ(t)` of the same t-statistic, shrunk toward the pooled prior by Bühlmann credibility, bounded to
   `[min, max]`.

The two disagree, and the disagreement is expensive. `Φ` is a **probability**, and essentially its whole
dynamic range lies in `t ∈ [−2.5, 2.5]`. Past the desk's hurdle the curve is flat: `Φ(2.0) = 0.9772`,
`Φ(10) = 1.0000`. So the weighting statistic **cannot distinguish a source that barely clears from one
that clears by a factor of five**, and it cannot distinguish a source that *fails* the desk's own test by
a hair from one that passes it comfortably. That is the correct behaviour for the question `Φ` answers —
"how sure are we the sign is positive?" — and the wrong behaviour for the question the combiner asks,
which is which view should steer the book.

The live desk shows exactly that failure. On the reported cycle the edge gate passed **one** source:

| source | resolved | cohorts | avg (bps) | t | p | gate |
|---|---|---|---|---|---|---|
| reversion | 500 | 39 | +9.90 | 10.65 | 2.9e-13 | **passes** |
| social | 51 | 31 | +3.26 | 1.82 | 0.039 | fails |
| mean-reversion | 11 | 11 | −1.89 | −0.26 | 0.60 | fails |
| momentum | 109 | 86 | −5.54 | −2.80 | 0.997 | fails |
| trend | 500 | 23 | −6.73 | −4.96 | ~1 | fails |

…and the combination weights that same cycle were `reversion 2.106`, **`social 1.757`**,
`mean-reversion 0.940`, `trend 0.250`, `momentum 0.250`. A source the desk's own test says has **not**
demonstrated an edge carried **83% of the weight** of the only source that has.

That is not an abstraction. `JPM` was the desk's **largest position** (`$13,288` gross, a planned
`−162.58` shares). Its fused view decomposed as

```
social      −16.00 × 1.757  = −28.11        (fails the gate)
momentum     −9.42 × 0.250  =  −2.36        (fails, measured negative)
trend        −0.94 × 0.250  =  −0.23        (fails, measured negative)
reversion    +1.29 × 2.106  =  +2.72        (the ONLY source that passes)
                              ───────
                              −27.97 / 4.363 = −6.41  → ×DM 1.194 = −7.66
```

The one source with a demonstrated edge said **buy** `JPM`; it was out-voted roughly ten to one by a
source that failed the desk's own significance test, and the desk went short `$33k` of it. The desk was
grinding toward that target at the derived ADR-0080 rate, so the position — and the exposure it carries,
and the fees it pays — accumulated every cycle. This is also the shape of the loop's standing problem:
gross exposure rising run over run against a total PnL that moves in tens of dollars.

Doing nothing leaves the desk in the position of having built a rigorous significance test, trusted it to
decide whether risk may be taken, and then ignored it when deciding what risk to take.

## Decision

**A source that has not demonstrated a directional edge at the desk's own significance hurdle is held at
the MIN combination weight.** It still contributes — the combiner drops only weights ≤ 0, so the active
source count and with it the ADR-0076 diversification multiplier are unchanged — but it may not out-vote
a source that has.

The admission test is `EdgeGate.demonstratesEdge`: the desk's existing test, at **zero cost**.

```
SE     = stdCohortMeanBps / √cohorts                      (ADR-0077, unchanged)
t      = avgReturnBps / SE                                 (cost NOT subtracted — see below)
admit  ⟺ resolved ≥ minSample ∧ cohorts ≥ 2 ∧ SE > 0
         ∧ StudentT_upper(t, cohorts − 1) ≤ α             (ADR-0081/0082 α, unchanged)
```

**Zero cost, deliberately.** `TelemetryWeights` states the separation and it is right: execution cost
decides *whether the desk should pay to trade at all*, which is the gate's job, while the weights decide
only *whose view counts more* among sources all facing the same cost. Subtracting cost here would charge
one measurement twice. Testing against zero asks the pure directional question — "is there an edge at
all?" — which is the right admission criterion for a vote.

**No new dial and no new statistic.** `minSample` and `α` are the gate's own, already configured
(`edge-gate.min-sample=30`, `edge-gate.t-hurdle=2.0`), and the arithmetic is factored out of
`EdgeGate.SourceEdge.clears` so the two callers cannot drift apart. Nothing here is a money, risk or
exposure number (invariant 7 / ADR-0016) — a weight is dimensionless and the combiner normalises by
`Σw`, so this rotates conviction and can never scale the book.

### Properties

- **Strictly one-way.** `min` is the lower clamp bound `compute` already applies, so a demotion can only
  ever *lower* a weight. No weight is raised, and a measured-negative source is never inverted into a
  contrarian bet — the ADR-0067 property, kept.
- **Inert with nothing to defer to.** The rule applies only when at least one source *is* admitted. If
  none is, the weights are returned exactly as measured. This is safe as well as simple: the admission
  test is the gate's test at cost 0 and the gate's is the same test at cost ≥ 0, so *nothing can clear
  the gate that fails admission* — with no source admitted the gate is reduce-only and the desk is adding
  no risk anyway. It also avoids the one case where demoting everyone would matter: a uniformly floored
  weight vector is the *most* diversified one, and would raise the ADR-0076 multiplier.
- **Book-shrinking where it bites.** Concentrating weight on the admitted sources raises `Σw̄²` and so
  lowers `DM = 1/√(Σw̄² + ρ(1−Σw̄²))`. On the live cycle `JPM` goes from `DM = 1.1944` to `1.1299` and its
  fused view from `−7.66` to roughly `−1.5` — below `min-forecast-to-route = 5.0`, so the desk stops
  adding to a `$33k` short it took on evidence it had already judged insufficient.
- **Not ADR-0087 and not ADR-0093.** ADR-0087 proposed *removing* the MIN floor and was reverted by the
  scorer; this ADR uses the floor that decision restored. ADR-0093 proposed replacing `Φ(t)` with a
  shrunk effect-size estimate for every source and was reverted; this ADR leaves `Φ(t)`, the credibility
  shrinkage and the bounding **exactly** as they are for admitted sources, and adds a binary admission
  test on top. Unlike both, it is one-way: it cannot raise a weight.

### Worked example (pinned as a test)

Two sources, round numbers in the live shape:

- **STRONG** — 500 resolved over 39 cohorts, `+10.0` bps, cohort sd `6.0`.
  `SE = 6.0/√39 = 0.960769…`, `t = 10.4083…`, upper tail on 38 df ≈ `4e-13` ≤ `α = 0.0227501…`
  ⇒ **admitted**.
- **WEAK** — 51 resolved over 31 cohorts, `+3.25` bps, cohort sd `10.7`.
  `SE = 10.7/√31 = 1.921797…`, `t = 1.69112…`, upper tail on 30 df ≈ `0.0505` > `α` ⇒ **not admitted**.

`Φ(1.691) = 0.9546` against `Φ(10.408) = 1.0000` — on evidence alone WEAK carries ~95% of STRONG's
trust. After the rule WEAK is exactly `Params.min()` and STRONG is bit-for-bit unchanged.

## Consequences

**Good.** The desk stops taking positions directed by sources it has itself judged to have no
demonstrated edge, and stops cancelling out the one source that has. The largest single position in the
book was of exactly that kind. Because the demotion is one-way and concentrates the weight vector, the
diversification multiplier falls and the planned book shrinks — the vector the improvement loop is scored
on (PnL up, exposure not up) improves in both coordinates where it bites.

**Bad / risk.** The desk becomes close to a single-source desk whenever only one source clears. If that
source's measured edge is an artefact of the running stream, the concentration makes the desk more
exposed to it, not less — the ADR-0076 multiplier will correctly shrink the book for that, but
concentration risk is real and this ADR accepts it. Two mitigations are already structural: admission is
re-evaluated every cycle from live telemetry, so a source that earns an edge is readmitted automatically
and one that loses it is demoted automatically; and a demoted source is *held at MIN, not dropped*, so it
keeps contributing and the desk never becomes literally single-view.

**Also accepted.** A source can sit at MIN for a long time while accruing the sample that would admit it.
That is the intended reading of "not yet demonstrated" and is symmetric with how the gate treats the same
source.

**Reversible.** `jethro.fusion.weights.mode=equal` bypasses telemetry weights entirely; passing a null
admission to `TelemetryWeights.compute` restores the pre-ADR-0097 weights byte for byte, and the
three-argument call site is the single line in `FusionConfig`.

## Alternatives considered

- **Weight by effect size instead of `Φ(t)`.** This is ADR-0093, which the scorer reverted. It also
  removes the bounded, never-negative property `Φ` provides.
- **Drop a failing source entirely (weight 0).** The combiner skips weight ≤ 0, which would change the
  active-source count and therefore the diversification multiplier — a size effect smuggled into a
  conviction decision, and the thing ADR-0076 exists to keep honest.
- **Raise `t-hurdle` so the gate closes for everyone.** That answers a different question (whether to
  trade at all) with a dial Oleg owns, and would stop the desk trading the one edge it has.
- **Do nothing and widen the buffer.** The cost is real but secondary: the desk's problem on `JPM` was
  not that it traded too often, it was that it was trading the wrong way.

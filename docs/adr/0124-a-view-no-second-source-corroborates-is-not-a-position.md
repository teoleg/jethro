# ADR-0124: A view no second source corroborates is not a position — size on the sources' DISPERSION, not on their signs

- **Status:** Proposed
- **Date:** 2026-07-29
- **Deciders:** Oleg
- **Tags:** trading, fusion, sizing, risk

## Context

ADR-0119 made a name's combined forecast pay for the agreement behind it: `ForecastCombiner.combine`
multiplies the fused conviction by

```
agreement = |Σ wᵢ fᵢ| / Σ wᵢ |fᵢ|     ∈ [0, 1]
```

the share of the sources' *gross* conviction that survives as a *net* view. That fixed a real defect —
two sensors fighting to a small residual no longer size like two quiet sensors agreeing on it.

**It has a degenerate case, and it is the wrong way round.** The ratio is `1` by construction whenever
only one source contributes: there is nothing for the net view to cancel against. ADR-0119 recorded
that as a *safety* property — "every single-source name is byte-identical to the pre-ADR-0119 desk" —
but the scalar is read downstream as *confidence*, and an untested view is not a confident one. The
consequence is an inversion: an uncorroborated view earns the **maximum** scalar in the cross-section
while every genuinely corroborated name is discounted below it.

On the live book of 2026-07-29 that inversion was the shape of the whole target list. From
`/api/fusion/targets` in the same cycle:

| instrument | sources | agreement | combined forecast |
|---|---|---|---|
| META | **1** | **1.000** | **−15.41** |
| TSLA | **1** | **1.000** | +2.98 |
| NVDA | 3 | 0.812 | −8.44 |
| MSFT | 3 | 0.972 | −7.87 |
| JPM | 3 | 0.871 | +6.82 |
| GOOG | 3 | 0.816 | −6.60 |
| JNJ | 3 | 0.674 | +6.50 |
| AMZN | 3 | 0.863 | −6.13 |
| AAPL | 3 | 0.246 | +0.37 |

META — one source, `xsreversion`, nothing corroborating it — carried the largest conviction in the
book, 1.8× the best-corroborated name, and a target of `−78.3` shares against a holding of `0`. The
ADR-0076 diversification multiplier is pushing the other way (1.000 for one source against 1.155 for
three) and is far too small to offset a scalar difference of 1.000 against 0.674. Breadth was being
rewarded by the DM and punished, harder, by the agreement scalar.

The same run's order book shows what it costs at the execution layer rather than in theory: the
single-source names are the ones whose oversized targets were re-planned and cancelled on the 30-second
cycle without ever filling — fourteen consecutive `fusion re-plan — passive order superseded by a fresh
target (ADR-0084)` cancellations on TSLA in `recent_orders`, against a held position of one share.

Doing nothing means the desk keeps handing its largest positions to whichever names happen to have the
fewest opinions about them — which, because `UniversePromotionService` promotes names with no history,
is systematically the names it knows least about.

## Decision

**We will size a name's conviction on the DISPERSION of its sources about their own average, not on
whether their signs cancel.** `ForecastCombiner.combine` keeps its `agreement` scalar and its position
in the chain (`average × DM × agreement`, clamped), and redefines it as

```
s²        = Σ ŵᵢ (fᵢ − μ̂)² / (1 − Σ ŵᵢ²)                 // unbiased weighted variance
agreement = |μ̂| / √(μ̂² + s²)  =  1/√(1 + (s/μ̂)²)   ∈ [0, 1]
```

where `μ̂ = Σwᵢfᵢ/Σwᵢ` is the weighted average already formed and `ŵ` are the weights normalised to sum
to 1. This is a monotone-decreasing function of the sources' coefficient of variation — the amplitude
form of the reliability ratio that attenuates a coefficient measured with error — stated in amplitude
units because it multiplies a forecast, which is an amplitude, not a variance.

The denominator `1 − Σŵᵢ²` is the **residual degrees of freedom** of a weighted variance (the
Bessel/Kish correction for reliability weights). At equal weights it is `(n−1)/n`, so `s²` reduces to
`Σ(fᵢ − μ̂)²/(n−1)` exactly; its reciprocal `1/Σŵᵢ²` is Kish's effective number of sources, which is the
same effective breadth the ADR-0076 DM is already stated in. **At one effective source it is zero** —
the dispersion is *unestimable*, which is not the same as zero — and the scalar is `0`. That is the
whole repair: a view no second source corroborates is not a position.

Three degenerate cases, each answered conservatively:

- **No net view** (`μ̂ = 0`) → `0`, rather than a 0/0 NaN. The combined value is already zero.
- **One effective source** (`Σŵᵢ² ≥ 1`, i.e. all the weight on one forecast) → `0`. Self-healing, not a
  ban: the name sizes again the moment a second sensor warms. Five sources exist.
- **Identical forecasts** (`s² = 0` with two or more effective sources) → `1`, byte-identical to the
  pre-ADR-0124 desk.

**No number is introduced and nothing needs calibrating** (invariant 7 / ADR-0016): the scalar is a
function of the running sums the average is already formed from plus one bounded second pass over the
same ≤5 forecasts, no new input is read, and no risk/money dial acquires a value needing provenance.
The forecast layer stays dimensionless; the `TargetPlanner` money boundary is unmoved and still in exact
decimal (invariant 1). It sits strictly above the deterministic floor — the pre-trade guardrail and the
drawdown breaker are untouched.

**Strictly one-way, as its two predecessors are.** `s² ≥ 0` ⇒ `agreement ≤ 1` ⇒ the combined value can
only ever SHRINK, and the sign is never flipped. This is the same family as ADR-0076 (re-weighting
cannot grow the book) and ADR-0098 (churn shrinkage is one-way).

**Strictly smoother than what it replaces**, which matters because ADR-0119 explicitly rejected a hard
agreement threshold for being a discontinuity "that would flip a name between full size and flat on
estimation noise". The sign ratio *is* such a discontinuity in disguise: it moves only when a sign
flips, so a source crossing zero by a hair moves the scalar by a step. The dispersion form responds to
the *magnitude* of the disagreement continuously — +10 against +20 scores 0.9045, +14 against +16 scores
0.9956, and the two shade into each other.

## Alternatives considered

**Chance-correct the existing sign ratio (Cohen's-κ form).** Credit only the agreement that exceeds
what independent random signs would produce at the same breadth:
`κ = (A_obs − A_null)/(1 − A_null)` with `A_null = √(Σmᵢ²)/Σmᵢ` for `mᵢ = wᵢ|fᵢ|`. Parameter-free,
one-way, exactly 1 for unanimous multi-source names, and 0 at n=1 — it fixes the inversion. It loses on
the n=2 case: `E|Σ sᵢ mᵢ| = m_max` there, so *any* disagreement between two sources is mathematically
below chance and the rule collapses to bang-bang — unanimous ⇒ 1, one dissenting tick ⇒ 0, however small
the dissenter. That is precisely the discontinuity ADR-0119 rejected, reintroduced through the back door.

**A diffuse prior on the missing dispersion at n=1** — assume an unseen second source is a draw from the
same scale, `s² ≈ μ̂²`, giving one source `1/√2 ≈ 0.707` rather than 0. Attractively parameter-free-
looking, but `s² = μ̂²` is an invented number wearing a formula, and it does not even fix the observed
problem: at 0.707 META's forecast is still 10.90, still the largest in the cross-section. A remedy that
leaves the inversion in place is not a remedy.

**Pool the cross-section for an empirical-Bayes prior on `s²`** — estimate the typical inter-source
dispersion from the names where it *is* estimable and shrink single-source names toward it. This is the
statistically richest option and is measured rather than assumed. It loses *for now* on scope: it turns
`ForecastCombiner.combine` from a pure per-name function into one carrying cross-sectional state, which
is a larger change than one attributable cycle should make. Recorded as a follow-up, not a rejection.

**Require ≥2 sources as a hard tradability gate instead.** Same effect on today's book, but as a veto it
is a discontinuity with an invented cut-off, it lives in the wrong layer (the error is in one name's
*conviction*, and belongs where the conviction is formed — the same reasoning ADR-0119 used to reject
shrinking at the portfolio layer), and it cannot express the n=3-but-all-weight-on-one case that the
`Σŵᵢ² ≥ 1` test handles for free.

**Fix it in the DM instead** — make the breadth multiplier punish n=1 hard enough to offset. Rejected
for the reason ADR-0119 already recorded: the DM answers a *breadth* question and is bounded by
Carver's cap; disagreement is a *confidence* question. Overloading one dial with both makes neither
readable.

## Consequences

- **Positive:** the inversion is gone — corroboration, not the absence of a second opinion, is what
  earns size. On the cycle above this stands META and TSLA down to zero and shrinks the corroborated
  names by their own measured dispersion, which removes exactly the oversized targets that were
  churning through the 30-second re-plan without filling.
- **Positive:** one-way and sign-preserving, so the worst case is a position smaller than intended,
  never larger and never the wrong way round.
- **Positive:** the scalar now reads magnitudes, so a name cannot step between full size and flat on one
  sensor crossing zero.
- **Negative — and this is the honest cost.** It **narrows ADR-0111's remedy**. ADR-0111 stands a
  measured-losing source down to zero weight to return the conviction it was subtracting; where that
  leaves a single surviving view, ADR-0124 now sizes the name at zero instead of at the un-diversified
  view ADR-0111 restored. The conviction is no longer surrendered to a measured loser, but it is not
  handed over uncorroborated either. `standingDownADisconfirmedSourceStopsItDraggingTheAverage` pins the
  new behaviour and says so in the test. The perverse-incentive risk is real and bounded: better source
  selection means fewer surviving sources means fewer tradable names, and if the edge gate ever stands
  down three of five sources this rule will quiet the book considerably.
- **Negative:** every multi-source name shrinks somewhat, not only the contradicted ones, because
  sources that share a sign but not a magnitude are no longer scored as fully corroborated. That is the
  intended correction, but it means the whole book sizes down on the first cycle after this ships, and
  a smaller book is a slower measurement.
- **Negative:** `s²` is one cycle's estimate from at most five points and is therefore noisy. Bounded
  and one-way, so the noise costs size, never safety.
- **Follow-ups:** (1) the empirical-Bayes pooled prior above, which would let a single-source name size
  at a *measured* discount instead of zero — the right long-run answer, and worth revisiting once the
  cross-section is wide enough to estimate it; (2) revisit the whole family if the edge gate opens and
  the book is systematically under-sized, since ADR-0119, ADR-0124 and ADR-0076 all shrink and none
  grows.

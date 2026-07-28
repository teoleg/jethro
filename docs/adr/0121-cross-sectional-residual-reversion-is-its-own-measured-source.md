# ADR-0121: Fade the residual against the peer group, not the raw move — as its own measured source

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** trading, signals, fusion, forecast-source

## Context

The desk has not put risk on for hours. The ADR-0064 edge gate reads `mayIncrease: false` because no
source's measured expectancy beats measured cost at the demanded confidence, and the improvement
ledger is a wall of `⚠️ INCONCLUSIVE` — every recent change tuned the *combiner*, the *gate*, or the
*sensor mechanics*, and none of them moved the number, because **re-weighting sources that have no
edge cannot create edge.** The honest problem is upstream of all of it: nothing in this universe has
demonstrated out-of-sample predictive power.

But the live telemetry is not uniformly null, and the shape of what it says points somewhere specific.
Of the four sources, exactly one is positive-signed at every rung of the ADR-0082 horizon ladder —
`reversion`, the ADR-0070 own-price range sensor: `avgReturnBps` `0.6500729504188325` at 225 s over
77 cohorts, `1.2532288851764135` at 900 s over 29, `6.940456315814394` at 3600 s over 8. `trend` is
negative on both its best-sampled rungs (`-0.21794567180606306` at 225 s over 87 cohorts,
`-8.299129091035356` at 3600 s). None is significant — `reversion`@3600 s reads `tStat`
`0.6344345168075892` against a `0.6278285714285714` bps blended round trip — so this is a *sign*
across three overlapping measurements of one signal, not three confirmations. It is still the only
structure the desk has.

The reversal literature says exactly why a raw own-price reversal signal measures like this. The
documented effect is in the **idiosyncratic** component: fading a name that is down *because the whole
cross-section is down* is a bet on the market factor, which carries roughly zero expectancy over an
hour and the reversal trade's full turnover cost, and it dilutes the residual bet it is pooled with
(Lehmann, *QJE* 1990; Lo & MacKinlay, *RFS* 1990; Khandani & Lo, *JIM* 2007). The desk currently has
no source that separates the two, and it hedges net equity toward flat under ADR-0019 anyway — so the
factor component is exposure it deliberately does not keep, measured as if it were alpha.

## Decision

**We will add `xsreversion` — cross-sectional residual reversion — as a fifth forecast source, on the
identical contract as ADR-0066/0070: it publishes a conviction, records every reading in the phase-1
telemetry, and must earn its own measured expectancy through the edge gate before it sizes anything.**

Per sweep, in the feed's own clock (ADR-0071): a name is admitted when it printed in **both halves** of
the lookback window, so its return actually spans the window; its move is `ln(P_last/P_first)/√span`,
vol-time normalised so fast and slow printers are comparable; peers are the name's **asset class from
the instrument master** (invariant 9); within a group of at least `min-peers` the score is
`−clamp((r − median)/(1.4826·MAD), ±max-abs-z)`. Median/MAD rather than mean/σ because peer groups here
are single-digit and one bad print would otherwise reverse everyone else's sign. The sensor emits the
whole cross-section at once, which is exactly one ADR-0120 cohort, so the gate's standard error and
degrees of freedom count it correctly with no special case. Dials are shape dials with provenance in
`application.properties`; no money, risk or exposure number is introduced (invariant 7 / ADR-0016).

## Alternatives considered

- **Tune the fusion weights / edge gate again.** Rejected. It is what produced the INCONCLUSIVE wall.
  The gate is shut because the measurements are null, and no admission bound, weight or hurdle edit
  changes a measurement (see the 2026-07-28 finding that retracted exactly such a change).
- **Beta-adjusted residuals against a factor proxy (`r_i − β_i·r_market`).** Deferred, not rejected —
  it is the better statistic. It needs a per-name β estimated on the stream, and a `β = 1.0` placeholder
  is a mistake this repo has already paid for. `StreamCovariance` covers 8 names today; revive this once
  it covers the traded cross-section and `xsreversion` has shown a sign worth refining.
- **Make ADR-0070's sensor cross-sectional in place.** Rejected: it would destroy the one positive-signed
  track record the desk has, and leave no way to tell which of the two constructions earned it.
- **Rank-based cross-sectional reversal (decile sort).** Rejected for these group sizes: with 4–8 names a
  rank carries almost no magnitude information, and the MAD scale already bounds the outlier problem.

## Consequences

- **Positive.** A genuinely new predictor rather than another combiner edit; it disagrees with ADR-0070
  by construction whenever the cross-section moves together, so the two are separable in telemetry. It
  cannot add exposure while the gate is shut, so the cost of being wrong is measurement time.
- **Negative.** It is a fifth source searched over three rungs, and the gate's Bonferroni correction
  divides α by **rungs only** (`hypotheses: 3`), not by sources — so the un-corrected source multiplicity
  gets worse, and a passing `xsreversion` reading deserves more scepticism than the gate expresses.
  Peer groups on this universe are small (18 measured names, 8 tradable), so several groups will fall
  below `min-peers` and stay silent. Being a new source it enters at the neutral 1.0 fusion weight while
  measured sources sit below it, which shifts combined forecasts before it has earned anything —
  harmless only because the gate is reduce-only.
- **Follow-ups.** Correct the gate's α for source multiplicity as well as horizon multiplicity. Revive
  the β-adjusted residual once stream covariance covers the traded cross-section. If `xsreversion`
  measures null on real cohorts too, that is strong evidence this universe has no short-horizon
  reversal to capture, and the honest next step is a different feed, not a sixth source.

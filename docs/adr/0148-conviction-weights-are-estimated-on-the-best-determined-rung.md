# ADR-0148: Conviction weights are estimated on the best-DETERMINED rung, not the rung the cost gate selected

- **Status:** Implemented
- **Date:** 2026-08-11
- **Deciders:** Oleg
- **Tags:** backend, signals, fusion, risk

## Context

ADR-0082 gave the desk a measurement ladder — the same sources scored at 3600 s, 900 s and 225 s — and
one selection rule: evaluate the edge gate at every rung, take the rung whose gate opens with the
smallest p-value, and when no rung opens let the base rung stand. That one rung then drives three
things: the gate's verdict, the desk's holding period (ADR-0080 derives the acquisition rate from it),
and the per-source combination weights (ADR-0055/0067/0074/0097/0111, `TelemetryWeights`). The stated
principle was that the desk must never grade a source over one period, weight it over a second and hold
it for a third.

That principle is right about grading and holding. It is wrong about weighting, and on 2026-08-11 the
cost of being wrong about it was visible in the book. `jethro.fusion.edge-gate.enabled=false` and no
rung's gate cleared in any case, so rule 3 fired and the **base** rung stood — the 3600 s one, which in
a rolling window is by construction the rung with the FEWEST independent cohorts in the whole ladder.
The live `fusion_targets` weight vector reproduces from those 3600 s statistics to five decimal places:

| source | 3600 s: mean bps / cohorts → t | 225 s: mean bps / cohorts → t | live weight |
|---|---|---|---|
| momentum | +8.9075 / 6 → +1.376 | +0.7658 / 12 → +0.399 | 1.2438 |
| reversion | +1.8534 / 15 → +0.933 | **−0.4166 / 161 → −1.666** | 1.2026 |
| social | +11.6991 / 5 → +1.334 | +7.7661 / 5 → +1.224 | 1.1769 |
| xsreversion | +1.8218 / 11 → +0.737 | **−0.2475 / 173 → −0.957** | 1.1279 |
| trend | **−3.1970 / 17 → −1.157** | **+0.5626 / 173 → +2.318** | **0.2500** (the floor) |

Not one of the five 3600 s readings is significant at the desk's own hurdle — the whole vector is a
ranking of noise. And it is very nearly the INVERSE of the ranking the same sources produce on the rung
where the desk has ten times the draws. Live consequence, read off that run's `fusion_targets`
`contributions`: on MCD, trend said **+10.97** at weight 0.25 while reversion (−20.0) and xsreversion
(−13.86) at ~1.15 carried the vote to a combined **−13.25** and a short of 260 shares; on NVDA, trend
said **−10.60** and the book went **long 183**. The desk was systematically trading against its only
large-sample positive source, over 2,813 fills and $5.7 m of turnover, with a firm total of −$1,014.62.

Doing nothing means continuing to allocate conviction from whichever rung the cost test happens to fall
back on, which — because the gate's comparison subtracts a round trip that does not shrink with the
horizon — is structurally the longest and therefore the thinnest rung available.

## Decision

We will estimate the per-source combination weights over the **best-determined** rung of the ladder —
the one with the most independent cohorts (ADR-0077) summed across sources, ties breaking toward the
longer horizon — while the gate's verdict and the desk's holding period stay on the rung ADR-0082
selects, unchanged. `HorizonLadder.weightingStats` is a pure function of the telemetry map; when no rung
reports more than one cohort it returns the selected rung's statistics, so the pre-change behaviour
stands byte for byte on a cold start.

**The gate's question and the weights' question are not the same question, and the code already said
so.** `TelemetryWeights` computes expectancy *gross* of execution cost on purpose, because "cost decides
whether the desk should pay to trade at all, which is `EdgeGate`'s job, while these weights decide only
whose view counts more among sources that all face the same cost." This ADR carries that same
separation one axis further. The gate's rung is a **cost** choice — over what period does expectancy
beat a round trip — and it rightly sets the holding period. Whose view counts is a purely **directional**
question, and the honest rung for a directional question is the one the desk has the most independent
draws of.

**This is not the "buy significance by shortening the horizon" error ADR-0082 warns against.** That
warning is exactly right for the gate, whose test subtracts a fixed round-trip cost from an expectancy
that shrinks with the horizon — so a short rung flatters nothing there, and the warning stands unaltered.
It does not transfer to the weighting statistic, which is `Φ(avgReturnBps / stdErrorBps)` and is
**dimensionless**: shortening the horizon shrinks the numerator and its standard error together, so `t`
is not mechanically inflated. What a shorter rung supplies is more independent cohorts and therefore a
better-determined `t` — strictly more evidence about the same question, not an easier test of a
different one.

**And the criterion is outcome-blind, so no multiplicity haircut is owed.** Cohort count is fixed by
measurement geometry — how many independent cross-sections a rung resolved in the rolling window — and
cannot be moved by the sign or the magnitude of what was measured. ADR-0082's Bonferroni haircut exists
because the gate searches over p-values; this rule peeks at no result and so cannot manufacture
significance. It stays in `EdgeGate.Params.alpha()` untouched.

No new dial and no new number: `shrinkage-k`, `weights.min`, `weights.max`, `edge-gate.min-sample` and
`edge-gate.t-hurdle` are all unchanged, and the statistic is the one already in `TelemetryWeights`.

## Alternatives considered

**Pool the rungs into one statistic per source (Stouffer, or a credibility-weighted mean of `Φ(t)`).**
More evidence still, and superficially the obvious move. It loses because the rungs are *nested
measurements of the same stream* — ADR-0082 says so itself when justifying Bonferroni over Šidák — so
they are strongly positively dependent, and every standard combination rule assumes independence.
Pooling them would overstate the desk's confidence by an unknown factor. Choosing one rung by sample
size asserts nothing about dependence.

**Choose the weighting rung per source rather than one rung for all.** Slightly more evidence per source
(each takes its own richest rung), but it makes the sources incommensurable — the vote would blend a
225 s directional accuracy against a 3600 s one — and it opens a per-source search the α does not pay
for. On this ladder it changes nothing anyway: cohort counts move together across rungs, so one rung
wins for every source simultaneously.

**Just re-tune the weight floor, or hand `trend` a bigger weight.** This is the tuning the standing loop
priority forbids and the INCONCLUSIVE wall was built from. It would also be a money-adjacent number
without provenance (invariant 7 / ADR-0016). The defect is not the floor's value; it is that the
estimate feeding it is made on the ladder's thinnest sample.

**Enable the edge gate so a rung is selected on evidence rather than falling back to the base.** Worth
doing on its own merits, but it is a different change and it does not fix this: when the gate is shut —
its normal state on this book — the base rung still stands, and the base rung is still the thinnest.
Left for a later cycle, one change at a time (ADR-0146).

## Consequences

- **Positive.** The desk stops voting against its own best measurement. On the live 2026-08-11
  telemetry the weight vector moves from `trend 0.25 / reversion 1.20 / xsreversion 1.13` to
  `trend 1.94 / reversion 0.25 / xsreversion 0.34` — an inversion driven entirely by using 173 cohorts
  instead of 17. The one source with a positive large-sample reading (`+0.5626` bps, cluster-robust
  `t = +2.32` on 173 cohorts, hit rate 0.542 over 3,202 resolved) gets to size, which is exactly what
  the standing priority asks for when a signal does measure edge.
- **Positive.** Conviction is now estimated on a statistic that can actually distinguish. The 3600 s rung
  accrues one cohort per hour per source; it cannot reach significance inside a working day, so weighting
  on it was never going to be anything but noise.
- **Negative, and the honest risk.** Grading/holding and weighting are now denominated over different
  periods, which is precisely what ADR-0082 set out to prevent. If a source's directional accuracy at
  225 s genuinely reverses by 3600 s, trusting it while holding for an hour is wrong. Two things bound
  that risk: the desk's *realised* holding period is minutes, not an hour (2,813 fills across 21 names in
  a session), so 225 s is the closer match to what it actually does; and the alternative being displaced
  is not a competing measurement but an insignificant one.
- **Negative.** Exposure is not expected to fall. Because `ForecastCombiner` normalises by Σweights, this
  can only ROTATE conviction between sources, never scale the target book — and every source keeps a
  strictly positive weight, so the ADR-0076 diversification multiplier is unchanged. What changes is the
  SIGN and composition of positions, not their size. The deterministic floor — pre-trade guardrail, firm
  drawdown breaker, gross/net/instrument caps, ADR-0059 conviction floor, ADR-0086 trailing cut — is
  untouched.
- **Follow-ups.** (1) The edge gate is disabled in `application.properties`; with weights now estimated
  where the evidence is, re-enabling it is the natural next candidate. (2) If `trend`'s 225 s reading
  strengthens past the ADR-0097 admission hurdle (it is at `p ≈ 0.011` against an α of `0.0076`), the
  demotion and ADR-0111 stand-down rules become live and the separation sharpens further on its own.

## Verification

Graded next cycle from live telemetry, not from prose:

- **VERIFY-BY (primary):** `/api/fusion/targets` `weights.trend` must exceed `weights.reversion` and
  `weights.xsreversion`, and the `contributions` blocks must show `trend`'s forecast agreeing in sign
  with `combinedForecast` on a majority of names — the inversion documented above must be gone.
- **VERIFY-BY (secondary):** the `signal_observations` LIVE 225 s hit rates must keep separating
  (`trend` above 0.50, `reversion`/`xsreversion` below) on a growing sample; if they converge to 0.50
  the premise of this change has expired and it should be reverted regardless of PnL.
- The scorer owns the PnL/exposure verdict over its ADR-0146 window; every figure in this ADR was read
  from that run's `logs/report.md` and reproduced in `HorizonLadderTest.theLiveInversionTheChangeFixes`.

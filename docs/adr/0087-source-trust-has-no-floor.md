# ADR-0087: Source trust has no floor — the worst-measured source is weighted by its own evidence

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** fusion, signals, telemetry, risk

## Context

`TelemetryWeights` (ADR-0055 item 6, statistic per ADR-0067, sample per ADR-0074) turns each source's
measured track record into a conviction weight: Φ of its own expectancy t-statistic, shrunk toward the
pooled prior by Bühlmann credibility over the observations that statistic averaged, centred on 1.0 and
clamped to `[min, max]`. `ForecastCombiner` normalises by Σweights, so the weights rotate conviction
*between* sources and can never scale the book — which is why this is a safe place to be strict.

The floor is now the binding constraint on exactly one source, and it is the one the desk has the most
evidence against. On the live 900 s rung — the horizon ADR-0082's ladder selected — the desk reads:

| source | resolved | cohorts | avg return (bps) | std error (bps) | t | Φ(t) | shrunk / mean |
|---|---|---|---|---|---|---|---|
| reversion | 230 | 10 | +7.8627 | 1.5513 | **+5.069** | 0.999999799 | 2.041457 |
| social | 14 | 8 | +2.8117 | 3.2768 | +0.858 | 0.804573 | 1.339774 |
| mean-reversion | 2 | 2 | +14.2836 | 23.6769 | +0.603 | 0.726836 | 1.122973 |
| momentum | 35 | 26 | −9.3732 | 4.0869 | −2.293 | 0.010910 | 0.407778 |
| **trend** | **230** | **10** | **−4.6110** | **1.4601** | **−3.158** | **0.000794** | **0.088018** |

`trend` is measured significantly NEGATIVE at *every* rung of the ladder — t = −3.16 at 900 s, −3.86 at
3600 s, −2.43 at 225 s, on 230–500 resolved calls. That is not a thin sample and it is not noise; it is
the richest sample on the desk delivering the most adverse reading on the desk. Its evidence-implied
weight is **0.088018**. `weights.min = 0.25` lifts it to **0.25 — 2.84× what its own measurement
supports** — and no other source is anywhere near the floor, so the floor's entire live effect is to
over-trust the single worst source. Because `trend` and `reversion` are mirror readings of the same
mark stream (hit rates 0.290 vs 0.785 at 900 s), that over-trust lands as a direct drag on the only
view the desk has evidence for: on every one of the 12 largest planned names, `trend` opposes
`reversion`, and on SAP it takes 13.9% off the conviction (16.183 against 18.437).

The floor's stated justification in the code is that `MIN > 0` keeps a decayed source *contributing* —
the combiner drops `weight ≤ 0` — so the active-source count, and with it the diversification
multiplier, is unchanged. **ADR-0076 retired that**: the DM is now sized from the concentration of the
weights actually used, not from a source count. And the premise is false anyway: `shrunk_s ≥
(1 − c_s)·pool` with `c_s = n/(n+K) < 1` for every finite sample, so the ratio is strictly positive
however bad the reading, and the source contributes without any floor.

## Decision

We will **default `jethro.fusion.weights.min` to 0** and let the shrunk ratio stand on its own at the
bottom of the range: Bühlmann credibility is the single, continuous thin-sample defence, exactly as
ADR-0074 established when it removed this floor's twin, the `weights.min-sample` hard floor. `max`
is unchanged at 3.0 — capping how far one source may dominate is a different question, and the
estimation-error argument for it survives. A non-positive `min` now reads as "no floor" rather than
snapping back to 0.25; a positive value restores a floor for anyone who wants one.

Nothing else moves: the statistic, the shrinkage constant, the pooled prior, the edge gate, the
per-name cost test and the deterministic floor are all untouched. The change is one clamp bound.

## Alternatives considered

- **Leave the floor and re-specify `trend` instead** (retune its spans, or gate it by regime). Rejected
  as the wrong order of operations: the desk's own measurement already says this source loses money on
  this stream, and the weighting method exists precisely so that reading is acted on without anyone
  hand-choosing which source to believe. Re-tuning a source until its measured expectancy turns
  positive is the canonical in-sample search (Harvey, Liu & Zhu, *RFS* 2016).
- **Invert `trend`** — its t of −3.16 is "significant", so trade it backwards. Rejected, and the code
  already says why: Φ is bounded in (0,1) specifically so a measured-bad source is down-weighted toward
  zero and never flipped into a contrarian bet. Inverting a losing signal is the textbook overfit.
- **Lower the floor to something smaller (say 0.05) rather than removing it.** Rejected as arbitrary:
  any positive value is a number without provenance doing a job shrinkage already does, and it would
  reintroduce the same discontinuity one source lower down. Removal is the position ADR-0074 already
  took on the identical construct.
- **Drop `trend` from the fusion set entirely.** Deferred, not rejected. It would also remove the
  source from the DM's inputs and from the telemetry that is currently the evidence for this decision.
  Revive it if `trend` stays significantly negative across a full sim↔live epoch change (invariant 8),
  i.e. once the reading is not a property of one stream.

## Consequences

- **Positive:** the desk stops diluting the only view it has evidence for (t = +5.07) with one it has
  evidence against (t = −3.16). On the live cross-section conviction rises ~14% on names where the two
  oppose, and the ADR-0076 diversification multiplier *falls* (1.0525 → 1.0204) because the weights are
  now honestly more concentrated — so part of the increase is paid back as less scale-up.
- **Negative:** conviction magnitude rises, and conviction is what `TargetPlanner` sizes from, so
  **gross exposure will rise** on the names that clear the ADR-0075 per-name cost test (today ES and
  MSFT). That is the intended direction — more of the measured-positive view — but it is an exposure
  increase, and it will be scored as one. If the next verdict is ❌ BAD, what has been refuted is
  `reversion`'s live edge, not this floor's redundancy.
- **Negative:** a source with a genuinely bad *early* run can now fall further before credibility has
  grown, which makes the weight vector more volatile run-to-run than a floored one. At K = 20 the
  effect is bounded — three calls sit ~87% on the pooled prior — but it is real.
- **Follow-ups:** `trend` is now weighted near zero while still consuming a sensor, a warm-up and a
  telemetry slot; if it stays there, replacing it is a better use of the slot than keeping it. Tracked
  in `docs/deferred-register.md`.

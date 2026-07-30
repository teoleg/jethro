# ADR-0130: Market-index trend as a fusion forecast source (slow market-factor overlay)

- **Status:** Implemented
- **Date:** 2026-07-30
- **Deciders:** Oleg
- **Tags:** fusion, signal, forecast, index, market-factor

## Context

ADR-0129 added the world indices as marked reference data but deliberately fed **no signal** — a
display-only first cut. Oleg asked to wire the index as a real trading signal: *"wire index signal — it
may be slow but real."* It is exactly that. The market factor (broad-index direction) is the single
biggest driver of a single name's return, and an index trend measured over hours is inherently slow and
low-turnover — a genuine, unhurried overlay on top of the fast per-name sensors.

The fusion layer already blends any number of forecast **sources** per name (`ForecastRegistry` →
`ForecastCombiner`, ADR-0055/0076/0119): each source pushes a self-normalised claim, weights come from
each source's **measured** expectancy (telemetry), and the sources' agreement scales the result. A new
source drops into that machinery with no special casing — it just has to publish claims and record its
calls so it can be measured.

Two conventions constrain the design:

- **Signals earn their edge (ADR-0049/0059/0064).** A source cannot size a position on assertion; it
  publishes a forecast, the telemetry measures its realised expectancy, and the edge gate decides whether
  it may put risk on. So the index-trend source is wired as a **forecast**, never a direct order or a gate
  relaxation.
- **No invented money dials.** A per-name market **beta** would scale the overlay, but a `β` default is a
  money dial we do not set silently — a `β=1.0` placeholder is precisely the mistake the conventions call
  out. So v1 applies **unit market beta**: every equity carries the same market-trend score, and the
  source earns its *magnitude* from its measured expectancy, not a hand-set beta.

## Decision

Add `IndexTrendForecastLifecycle`, a forecast source under the identical contract as the trend / reversion
sensors:

- **Same estimator, run on the index.** It uses the same `EwmacTrendForecaster` the per-name trend sensor
  uses, fed the broad **market index** (`jethro.fusion.index-trend.market-index`, default `SPX` — an INDEX
  row in the master per ADR-0129, not a hardcoded symbol). That produces one self-normalised market-trend
  score.
- **Carried to every equity.** Each equity's `indextrend` claim = the market-trend score (unit beta). The
  claim maps to the house convention exactly as the per-name trend does (`SourceForecasts.indexTrendClaim`),
  so the sizing dials keep their meaning; `ForecastRegistry.submitIndexTrend` publishes it and the combiner
  blends it with the per-name sources, weighted by its own measured expectancy and attenuated by agreement.
- **Measured like every source.** Each published call is recorded in the phase-1 signal telemetry under
  `indextrend`, so the overlay is judged on its own realised edge and earns (or loses) its fusion weight. A
  new source arrives with no evidence.
- **Prints, not cycles (ADR-0113).** The index EWMAC steps only when the index tape prints, and a name is
  published only when ITS tape prints, so a republished last-value mark never fabricates a zero-return step
  or a phantom telemetry call. Warmed from durable mark history on first sight (ADR-0071) so the market
  read boots calibrated.
- **Slow by construction.** A 15s cadence (vs the per-name trend's 5s) on a delayed index feed — the market
  read is deliberately low-turnover.
- **Places no orders, relaxes no gate.** Whether any of it trades is decided downstream by the edge gate,
  the conviction floor, the backtest-support veto, and the deterministic floor (guardrail, firm breaker).
  Adding the source weakens none of them. Gated on `jethro.fusion.index-trend.enabled` (default true).

## Consequences

- **Intended:** a slow, real market-factor overlay — when the broad market trends, the whole equity book
  tilts with it, sized by the overlay's *measured* edge, blended with (and disciplined by) the fast
  per-name sensors and the same risk floor as everything else.
- **Net market exposure:** an aligned overlay adds directional (beta) exposure to the book. That is the
  point of a market-trend signal, and it is bounded by vol-targeting, the per-book/firm guardrails, and
  offset by the beta-hedge book where configured (ADR-0038/0039) — the deterministic floor is unchanged.
- **Honest scope — unit beta:** every equity gets the same score; a name's true market sensitivity (beta)
  is not applied, so a high-beta name is under-tilted and a low-beta name over-tilted relative to a
  beta-aware overlay. Deliberate: a `β` dial is not invented here. **Per-name / per-region beta** (and
  mapping EU/Asia equities to their own regional index rather than all equities to SPX) is a tracked
  follow-up in the deferred register.
- **Measured before it matters:** with no evidence the source starts at neutral weight and must earn edge
  through telemetry before the gate lets it size anything — so a wrong market read cannot quietly lever the
  book.
- Validated: full app test suite green; the source registers and blends through the existing combiner with
  no combiner change.

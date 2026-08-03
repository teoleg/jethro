# ADR-0135: An unestimable view holds the position; it does not liquidate it

- **Status:** Implemented
- **Date:** 2026-08-03
- **Deciders:** Oleg
- **Tags:** fusion, risk, execution, cost

## Context

ADR-0124 made the agreement scalar a dispersion statistic: `agreement = |μ̂|/√(μ̂² + s²)`, where `s²`
is the sources' unbiased weighted variance, divided by the residual degrees of freedom `1 − Σŵᵢ²`. At ONE
effective source that denominator is zero, the dispersion is unestimable, and the scalar is defined to
return 0 — deliberately, to repair ADR-0119's inverted breadth, where an uncorroborated view earned full
conviction. That reasoning is sound and is not what is being revisited.

What was not anticipated is where the 0 lands. It multiplies the combined forecast, so the name's
combined value becomes exactly 0; `TargetPlanner.targetQuantity` maps 0 to a target of flat; and ADR-0090
works a FLAT target **in full** rather than at the partial-adjustment rate, precisely because a flat
target was assumed to mean "the desk has decided to be out". The result is that a collapse in source
BREADTH is executed as a decision to LIQUIDATE — the entire position, in one cycle, at full urgency.

That is not a theoretical path; it is what the desk does every day. The ADR-0134 origination triggers,
landed last cycle expressly to make this attributable, record the sequence on 2026-07-31 between 20:10Z
and 20:49Z: source counts fall 4 → 3 → 2 through the equity cash close, and then every routed name exits
on `fusion exit — target decayed to flat [forecast=-0.0, sources=1]`. The cause is structural, not a
change of view — since ADR-0113 the price-driven sensors advance only when the tape PRINTS, so when the
cash session ends they stop speaking and only the cross-sectional source, which reads a snapshot, is
left. Breadth collapses to one at every close, by construction. The book has been flat ever since, and
`/api/fusion/targets` still shows all five routed names at `sources: 1`, `agreement: 0.0`,
`combinedForecast: 0.0` with contributing forecasts as large as ±10.97 underneath.

Doing nothing has two live costs. The desk round-trips its entire book daily on a signal that never
fired, paying spread and fees both ways — the attribution endpoint shows fees an order of magnitude
larger than firm total PnL, with the strategy book negative outright net of them. And it leaves the book
DORMANT at zero gross against the owner's deployment mandate (ADR-0132), for as long as breadth stays at
one, which across a weekend is days.

## Decision

We will distinguish an **unestimable** view from a **measured view of flat**, and hold the position in
the first case. `ForecastCombiner.Combined` gains `estimable`, false in exactly one circumstance —
sources spoke but `1 − Σŵᵢ² ≤ 0`, the same residual-degrees-of-freedom test the agreement scalar already
makes. When `FusionPlanner` sees `estimable == false` it targets the inventory **already held** and plans
a delta of zero: no exit, and equally no entry, since an uncorroborated view may not size a position
either. The flag is carried on `Target` and surfaced on `/api/fusion/targets`.

Two cases are deliberately out of scope and byte-identical to before. A name with **no** source at all
still has an implicit target of flat and is still worked down — that is ADR-0065's orphan sweep, and there
is no view there whose uncertainty could be unknown. Two or more sources netting to zero is a *measured*
view of flat and still exits in full under ADR-0090. Nothing in the deterministic floor moves: the
ADR-0086 trailing cut, the firm drawdown breaker, the pre-trade guardrail, the edge gate and the
risk-reducing stages downstream all still run against the held target and can all still flatten it. The
controls that exit without a view remain the ones that decide that exit.

## Alternatives considered

**Give the one-source case a non-zero agreement (a prior, or a floor).** This is the direct way to
un-flatten the book, and it is wrong twice over. It re-opens exactly the defect ADR-0124 closed, and on
this desk it would hand the whole book to `xsreversion` — the only source currently speaking and, per
`/api/signals/telemetry`, the one with the most negative measured expectancy of the five. Sizing on the
worst-measured source alone is not a fix, it is a trade.

**Keep the sensors speaking through the close** (bridge the print gap, or hold the last reading). This
addresses the cause rather than the symptom and is attractive, but it means synthesising readings from a
tape that is not printing — a fabricated forecast is a worse invariant-7 problem than a missing one, and
ADR-0113 removed exactly that assumption from the live path on purpose. Deferred, not rejected: if
breadth turns out to collapse *during* the session too, the sensors' warm-up is the thing to fix.

**Gate the exit on the session state — flatten at the close, hold otherwise.** Rejected as the wrong
axis. The defect is not about the close; the close is merely the most reliable way to reproduce it. Any
breadth collapse triggers it, and a session-state dial would leave the mechanism live and add a
market-calendar dependency to the fusion path.

**Suppress the ADR-0090 full-urgency exit and grind flat instead.** Cheaper per cycle, but it still
liquidates on an absence of information — slower, and paying more spread on the way.

## Consequences

- **Positive:** a position is exited by a view or by a risk control, never by silence. This removes a
  daily forced round-trip of the whole book, which is pure cost against measured-zero information, and
  removes the mechanism that has held the desk at zero gross for three days.
- **Negative — the real one:** positions now persist across breadth collapses, so the desk will carry
  overnight and weekend gap risk it previously (accidentally) flattened away. That risk is bounded by the
  ADR-0086 trailing cut and the firm breaker, not by the forecast, and those act only after a move. If
  breadth never returns for a name, the position is held indefinitely rather than swept — the flag is
  surfaced on the targets endpoint precisely so that is visible rather than silent.
- **Negative:** one more state to reason about in the planner, and a boolean threaded through six target
  reconstructions in the fusion package.
- **Follow-ups:** watch whether breadth recovers at the next cash open; if the routed names stay at
  `sources: 1` during the session, the sensors' warm-up (ADR-0071/0113) is the next target, not the
  combiner.

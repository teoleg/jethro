The desk liquidates its whole book at every equity close because source breadth collapses to one and the combiner reads "no corroboration" as "be flat" — now an unestimable view HOLDS the position instead (ADR-0135).

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`,
`/api/signals/telemetry`, `logs/report.md`, the scorer's ledger row, or the repo source. None is authored
here — invariant 7 / ADR-0016.)*

## Situation — the four questions

1. **Money.** Total PnL **$32.51**. Since last run **+0.00**; over the last three runs **+0.00**. Not
   bleeding, but not moving either, and `on_track=false` against the +1%/3-iteration target. The
   attribution split says more than the headline: **ALPHA is −18.60 having paid 281.28 in fees**, and the
   firm total is positive only because **HEDGE carries +86.93**. Total fees are roughly nine times total PnL.
2. **Risk.** Gross **$0.00**, net **$0.00** — **0.0%** of the $1,500,000 firm cap, **$1,500,000** of
   headroom. The **DORMANT** flag is up. Not a danger state; the opposite — the largest opportunity on the
   board, open since 2026-07-31 20:49Z.
3. **Cause.** Last cycle's change (`ad6c42c88`, ADR-0134 origination triggers) scored **⚠️ INCONCLUSIVE**,
   exactly as predicted for a telemetry-only change that moves no money, and `.pending-baseline.json` is
   gone so the hold is clear. It did not cause the dormancy — the book was already flat when it was measured.
4. **Danger.** No. Breaker clear, regime CALM, zero exposure. The failure here is inaction, not risk.

## What the orders say — and it is the whole diagnosis

The ADR-0134 triggers shipped last cycle paid for themselves immediately. `recent_orders` now names why
every position died, and the sequence between 20:10Z and 20:49Z on 2026-07-31 is unambiguous: source
counts fall 4 → 3 → 2 through the equity cash close, then every routed name exits on
`fusion exit — target decayed to flat [forecast=-0.0, sources=1]`. Not one of those exits was a change of
view. Since ADR-0113 the price-driven sensors advance only when the tape **prints**, so at the close they
go quiet and only the snapshot-based cross-sectional source is left speaking. `/api/fusion/targets` still
shows all five routed names at `sources: 1`, `agreement: 0.0`, `combinedForecast: 0.0` — with contributing
forecasts as large as ±10.97 sitting underneath that zero.

The mechanism is in `ForecastCombiner`: at one effective source the residual degrees of freedom
`1 − Σŵᵢ²` are zero, so ADR-0124's dispersion is **unestimable** and the agreement scalar returns 0. That
is correct statistics. But the 0 multiplies the forecast to exactly 0, `targetQuantity` maps 0 to a target
of flat, and **ADR-0090 works a flat target in full** — so a breadth collapse executes as a full-urgency
liquidation of the entire book. Daily, both ways, on measured-zero information. That is where ALPHA's
fees come from, and why the book has sat at zero gross for three days.

## The change

An unestimable view is not a view of flat. `Combined` gains `estimable` (false only when sources spoke but
`1 − Σŵᵢ² ≤ 0`), and the planner then targets the inventory already held with a zero delta — no exit, and
equally **no entry**, because an uncorroborated view must not size a position either. Two cases stay
byte-identical: a name with no source at all is still swept flat by ADR-0065, and two or more sources
netting to zero is a *measured* view of flat and still exits in full. The deterministic floor is untouched
— the ADR-0086 trailing cut, the firm breaker, the pre-trade guardrail and the edge gate all still run
against the held target and can all still flatten it.

I deliberately did **not** take the tempting route of giving the one-source case a non-zero agreement. It
would re-open the defect ADR-0124 closed, and on this desk it would hand the entire book to `xsreversion`
— the only source currently speaking and the one with the **most negative** measured expectancy of the
five on `/api/signals/telemetry`. Un-dormanting the book on the worst-measured source is not a fix.

**Honest limits.** This removes a cost the desk was paying for nothing; it does not by itself create edge,
and the standing "work on edge, not the combiner" priority still stands — but this is not combiner
*tuning*, it is a structural gate that made holding any position impossible past a close. It also accepts
real overnight and weekend gap risk that the daily liquidation was removing by accident, now bounded by
the trailing cut and the breaker rather than by the forecast. If breadth stays at one *during* the next
cash session, the sensors' warm-up (ADR-0071/0113) is the next target, not the combiner.

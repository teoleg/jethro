Stopped the edge gate crediting the desk with more edge than it measured: a round trip that comes out negative is arrival-mark drift booked as execution, not a cost, and the desk-wide hurdle was being set by it (ADR-0106).

*Every figure below is quoted from the live endpoints, the report or the ledger; none is computed here.
The ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** The report's SITUATION header reads total PnL `$890.12`, the window at `-1.25` and the
last three runs at `+88.80`; `run-status.json` reads `pnl_growth_pct 15.33` against a `1.0` target,
`on_track` true, `stale` false, `underwater` false. Live `/api/risk` `.total` has since read
`899.68183780`. The book is **not** bleeding — the window's `-1.25` sits well inside the `$50` deadband
and PnL has risen since the report was cut. Attribution splits it `ALPHA +1,213.87`, `MACRO +376.996`
(unchanged to the cent for an **eleventh** cycle), `HEDGE -700.65`, fees `$459.59`.

**2. Risk.** The report shows gross `$7,176.74` / net `-$1,549.06`; live gross has since read
`$2,170.03` against a single ALPHA position (short 14 JNJ), with the hedge flat. Breaker `halted: false`,
VaR95 `$96.07`, ES95 `$140.17`, firm gross a fraction of a percent of the declared `1,500,000`.

**3. Cause.** Last cycle's change (ADR-0105, `d983eede9`) scored **BAD** and was auto-reverted by the
scorer — `$891.36 -> $887.50` for `$0.00 -> $7,181.05` of gross. That is a fourth intervention on the
hedge target in five cycles (0095 BAD, 0098 GOOD, 0100 MIXED, 0105 BAD).

**4. Danger — the flag is an artifact, and I checked before acting on it.** The header raised
`BLEEDING; EXPOSURE RISING; DANGER`. Both legs are measurement artifacts: the `-1.25` is inside the
noise band, and the `+7,176.74` of "rising" exposure is measured from a `$0.00` prior reading, i.e. the
book re-opening after the ADR-0104 brake had driven it flat, plus the ADR-0105 revert. Live gross has
since **fallen** to `$2,170.03` while PnL rose. This is not a live danger state and de-risking would
have been the wrong move; I say so explicitly rather than reflexively cutting.

**5. Order-level post-mortem.** `recent_orders` shows the HEDGE book buying and selling ES on a ~60
second metronome — eleven fills, alternating side, roughly `0.079` contracts traded for a net position
change of `0.0025`, about thirty units of turnover per unit of exposure moved. `HEDGE` carries
`realizedPnl -701.03` against `feesPaid 46.82`: the overlay's entire loss is realised round-trip churn,
and it consumed 55% of ALPHA's `+1,213.87` while adding 39% of firm gross. It is by an order of
magnitude the worst thing on this desk.

**6. Memory.** `loop-findings.md` says the hedge-target family and the fusion source-weight family
(0087 BAD, 0093 BAD, 0097 MIXED) are both freshly burned. A fifth hedge-target refinement would be
re-attempting a reverted idea in a different costume, so I did not ship one — see below for what I
checked instead.

**7. Change vs market.** The window's PnL move is market, on positions nothing I shipped touched — I
claim **no** PnL credit for it. The exposure move is **100%** attributable to code, but to the
*scorer's* ADR-0105 revert, not to a change of mine. Nothing this cycle is mine to be credited or
blamed for.

## What I checked and rejected before choosing

The hedge is the biggest prize and I spent most of the cycle on it. Every mechanism I could justify
mapped onto an already-reverted ADR (effectiveness/rho-squared gate = 0095; target shrink = 0098; rate
tracking = 0100; posture floor = 0105), so I left it and recorded the one genuinely untried angle —
rebalance *cadence* rather than target *shape* — for a later cycle. I also killed three of my own
candidates on the evidence rather than shipping them: cross-sectional demeaning of the forecast (the
live cross-section is already balanced, net/gross `-0.064`, so it would do nothing); stripping the
diversification multiplier from measured-failing sources (a pure scale change — it cuts exposure and
PnL in the same proportion, so it is neutral on PnL-per-unit-exposure and not worth a cycle); and a
time stop at the measured horizon (the ladder shows the reversion edge is `+8.43` bps at 900s and
`+9.48` at 3600s, and forecasts are re-emitted every cycle, so a held position is a renewed call rather
than stale risk — the telemetry does not support it).

## What I changed, and why it is real

`/api/fusion/targets` reports `roundTripBpsByInstrument.NQ = -0.25445`. **A round trip cannot pay the
desk** — both legs cross the touch or wait for it and the fee is charged twice. That reading is price
drift over the fill window, booked as if it were execution: under ADR-0084 the desk *posts* to enter,
and a passive order fills precisely when the price comes to it, so entry drift is systematically
favourable.

Left in the map it does real damage, because ADR-0075 states the desk-wide verdict at the **cheapest**
entry. That one impossible number became the desk's cost, and `netEdgeBps = avgReturnBps - cheapest`
came out **above the expectancy actually measured**, for every source at once — `reversion` graded
`8.683` against a measured `8.429`, `trend` `-5.977` against `-6.232`. The gate that exists to make the
desk pay for its trading was handing it 0.25 bps of edge no signal produced. It also collapsed that
name's ADR-0101 buffer to the `0.10` Carver floor, so the desk re-trades its least honestly-priced name
the most often.

A non-positive measurement is now treated as **no** measurement and falls back to the ADR-0099
quote-or-blend path a never-filled name already takes; a positive one is untouched, so ADR-0084's
genuine saving is preserved (AAPL measures `0.889` against a `2.00` quoted touch — one half-spread,
exactly what posting should save). Strictly one-way and asserted as tests: every per-name hurdle is at
least what it is today and the gate is monotone in cost, so this can only remove a trade, never add one,
and exposure cannot grow because of it. No source changes verdict.

I am honest about the size: this is a **correctness fix in a money gate**, not a large PnL lever. Its
measurable effect is confined to that name's no-trade buffer and to the honesty of the published net
edge. The bigger prize it exposes is in the deferred register — the same arithmetic suggests the *whole*
cost series is biased low, not just where it crosses zero into the impossible, which would make every
cost-keyed control on this desk too permissive. That is the next thing worth measuring.

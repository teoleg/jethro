The desk pays two fifths of what it makes to get there — so the no-trade buffer's width is now its own measured cost-to-edge ratio instead of a published convention (ADR-0101).

*Every figure below is quoted from the live endpoints and the report; none is computed here. The ledger's
numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** Total PnL `$626.57` on `/api/risk` `.total`. The SITUATION header puts the window at
`+2.07` and the last three runs at `+292.11`; `run-status.json` reads `on_track` true, `stale` false,
`underwater` false, `pnl_growth_pct` far above the 1% target. The book is **not bleeding** — but the
window is essentially **flat**, and it was flat while the desk filled 2,254 orders today. Attribution
splits it `ALPHA +879.66`, `MACRO +376.99` (unchanged to the cent for a **sixth** cycle, a stranded
`0.000029` ES), `HEDGE −630.08`.

**2. Risk.** Gross exposure `$34,531.90`, net `$940.79`. Gross **fell** `−27,584.72` on the window and
`−27,376.87` over three runs. VaR95 `$323.92`, ES95 `$480.92`, breaker `halted: false`. No danger flags,
nowhere near the drawdown breaker.

**3. Cause.** Last cycle's ADR-0100 (the hedge closes the gap at its target's own directional efficiency)
scored ⚠️ MIXED — PnL `−8.62`, gross `−29,039.35`, risk-adjusted `0.00996 → 0.01809`. The live cycle
confirms the mechanism: `trackingRate 0.52696`, the axis reads `ON-TARGET … largest delta under the
4104.24 no-trade band, holding`, and the window sent **one** HEDGE ES order against eleven the cycle
before. The overlay's churn is fixed. Its level is not: HEDGE went `−599.22 → −630.08`, a sixth
consecutive deterioration — but on 412 ES fills at a measured `0.207` bps and `$43.57` of fees, so that
loss is directional, not execution, and it is not attributable to either of the last two cycles' work.

**4. Danger.** None. Bleeding? No. Exposure rising? No, sharply falling. Breaker? Far away. So no
de-risking override applies this cycle.

**5. Order-level post-mortem.** The window's `recent_orders` are ALPHA almost end to end: JNJ bought
thirteen times in twelve minutes (`2,4,5,7,7,2,6,16,2,13,11,8,10`), GOOG bought five times then sold
eight, AAPL sold eleven times — a position being walked toward a target it never reaches — plus four
orders cancelled as `fusion re-plan — passive order superseded by a fresh target (ADR-0084)`. No trigger
opened a *losing* position: the losers are closed and flat (`GOOGL −161.84`, `SAP −85.46`, both diagnosed
and fixed by ADR-0099), and every live position is a winner (`AAPL +433.21`, `JPM +264.15`, `JNJ +201.35`,
`MSFT +172.51`). **The leak is not a bad trigger, it is the price of the good ones:** ALPHA paid `$355.89`
of fees to make `$879.66`.

**6. Memory.** `docs/loop-findings.md` closes the cost-*model* side (ADR-0099, GOOD) and both the hedge
sizing and rate sides (ADR-0098 GOOD, ADR-0100 MIXED, ADR-0095 reverted), and warns off re-tuning either.
The source-weighting lever is closed in both directions (ADR-0087 and ADR-0093 both reverted BAD). It
queues the diminishing marginal return — gross scaling faster than PnL — as the next lever.

**7. Change vs. market.** The window's `+2.07` is a rounding error on positions ADR-0100 never touched;
ADR-0100 is credited with the gross collapse and the overlay going quiet, and with none of the PnL. The
`−30.86` on HEDGE is the market moving against a short ES leg. Market and change cannot be separated in
the ALPHA line this window and I will not pretend otherwise.

## Diagnosis and the change

The mechanism costing risk-adjusted PnL is **turnover priced at a width the desk never measured**.
ADR-0094 moved the no-trade band to where it can bind but left its half-width at
`position-buffer.fraction = 0.10`, Carver's published convention. A convention is what you use when you
cannot measure — and this desk measures both inputs every cycle *for the edge gate*: each name's own
round-trip cost (`0.49–1.01` bps of measured slippage plus a 1 bp fee per side) and the gross expectancy
of the one source that clears the gate (`reversion`, `8.82` bps over 500 resolved calls on 65 cohorts,
`t = 9.3`, while `trend −4.69`, `momentum −4.80` and `social −5.92` are all measured *significantly
negative*). The width those two imply is `2C/μ`, and it is several times `0.10`.

So each name is now buffered at `max(carverFraction, min(1, 2C/μ))`. The derivation is the aim's own
first-order condition: closing a gap `g` is worth its cost when `½λσ²g² > C·|g|`, and `a = μ/(λσ²)` turns
that into `band = a·(2C/μ)` — which is why the risk-aversion parameter this desk has never stated
**cancels** and never has to be invented. No new dial and no new number; both inputs already sit in the
`EdgeGate.Decision` the buffer is already handed. It is strictly one-way: the measured width is used only
where it is *wider*, so it can only remove turnover, never add it; the aim path is untouched quantity for
quantity, so intended exposure is unchanged by construction and this is a PnL improvement at constant
risk; and an exit is still not buffered at any width, so the ADR-0086 cut, the ADR-0065 unwind and the
deterministic floor beneath them are unaffected. Proposed ADR-0101 in the same commit, worked examples
pinned as exact-decimal tests, `./gradlew -Pci test` green.

**Why not the hedge, which is the larger number.** `HEDGE −630.08` is 47.5% of firm gross and exceeds the
whole firm's PnL — it is the single biggest drag on the objective. But its sizing side and its rate side
have each been changed in the last three cycles and the memory records both as closed. What is left is the
*posture* question — whether a mean-reversion book wants a beta overlay at all — and answering it by
standing the overlay down would take firm net from `$940` to `$17,349` on the strength of a market that
has lately been going up. Dismantling a working risk control to make the metric look better is exactly the
trade I am here to avoid. The turnover leak is the same order of magnitude, is fixable with no change in
risk posture at all, and is one-way safe. The overlay-posture question stays queued.

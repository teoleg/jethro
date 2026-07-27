Stopped the desk hedging the exposure it always carries: the overlay is now worn back to the edge of the book's own measured net-exposure band instead of all the way to flat (ADR-0105).

*Every figure below is quoted from the live endpoints, the report, the ledger or the committed
attribution snapshots; none is computed here. The ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** Total PnL `$866.30` on `/api/risk` `.total`. The SITUATION header puts the window at
`+10.97` and the last three runs at `+93.40`; `run-status.json` reads `pnl_growth_pct 28.71` against a
`1.0` target, `on_track` true, `stale` false, `underwater` false. The book is **not** bleeding.
Attribution splits it `ALPHA +1,179.74`, `MACRO +376.996` (unchanged to the cent for a **tenth**
cycle), `HEDGE −690.44`, fees `$453.90`.

**2. Risk.** Gross `$6,020.79`, net `−$6,020.79` — the book is entirely one-sided short, so the hedge
is holding nothing. Gross is **falling**: `−13,757.16` on the window, `−20,678.30` over three runs. No
danger flags. VaR95 `$113.73`, ES95 `$163.91`, breaker `halted: false`, firm gross ~0.4% of the
declared `1,500,000` limit.

**3. Cause — last cycle's change.** ADR-0104 (book volatility brake) scored ⚠️ **MIXED**: PnL
`+$7.93` with gross `−$12,489.73`, and the scorer's own risk-adjusted read improved `0.03887 →
0.09041`. That is the brake doing exactly what it was built to do, and it stays. Two deployments sat
in that window (the ADR-0103 revert and the brake), so I claim no precise share of the gross fall for
either — the direction is unambiguous and the sign is right.

**4. Danger.** None. Not bleeding, exposure falling, breaker clear. So this is a cycle to attack a
known drag rather than to de-risk.

**5. Order-level post-mortem.** `recent_orders` shows the ALPHA book round-tripping hard — JNJ bought
`5+8+3+8` between 10:42 and 10:44 and sold `37` at 10:46:36; AAPL bought `2+2+1+3+2` and sold
`14+12+18`. That is the ADR-0080-derived position path sweeping its full amplitude, already the target
of ADR-0084/0090/0094/0101/0103. No trigger opened a clearly identifiable loser this window. The
HEDGE book, though, sent nine ES orders in twenty minutes — `SELL 0.0092, SELL 0.0106, SELL 0.0123,
SELL 0.0142, BUY 0.0283, BUY 0.0152, SELL 0.0210, BUY 0.0422, SELL 0.0127` — to end up holding
essentially nothing, on an axis whose panel says `status: FLAT`, `held 0 → target 0`.

**6. Memory.** `docs/loop-findings.md` names the overlay POSTURE question as open lever (a) in the
last two postmortems, deferred explicitly by both ADR-0098 and ADR-0100. Rule 1 in that file — *a
lever named in three consecutive postmortems and acted on in none is the lever* — applies.

**7. Change vs market.** The window's `+10.97` is market on positions nothing of mine touched; I claim
no PnL credit for it. The gross fall is the ADR-0104 brake plus the ADR-0103 revert, both structural,
neither mine to re-credit.

## Diagnosis

The overlay is the one persistent, mechanism-understood drag on this desk, and the committed
attribution snapshots make it unarguable: HEDGE total PnL reads `−598.79 → −623.08 → −626.93 →
−645.64 → −650.16 → −690.53` across six consecutive scored windows — a loss in every one — while the
strategy books went `+1,171.14 → +1,555.99`. In the most recent window the strategy books made about
`+$91` and the overlay lost about `−$40`; that difference is the whole reason the headline moved
`+$10.97` instead of `+$51`. It is not execution cost: HEDGE fees are `$46.37` against a `−$690` loss.

ADR-0098 fixed the target's *level* and ADR-0100 its *rate*, and both scored well. Neither touched
what both explicitly deferred: `jethro.hedge.equity-rebalance-floor-usd=0` — hedge from the first
dollar. That is not a decision; it is the absence of one, and it means every breach is met by hedging
back to the **centre**. The desk's net equity exposure reads `7,678 → 939 → 8 → 1,804 → −7,612 →
−6,020` across the day's snapshots: a quantity that crosses zero and comes back. Hedging that to the
centre pays a round trip on every oscillation of an exposure the desk carries continuously — and,
against a book whose one measured edge is mean reversion, does it by taking the opposite side of the
desk's own view.

## Change

The overlay now wears only the fraction of its sized hedge that covers exposure standing **above the
level this desk habitually carries**: `f = max(0, |N| − H)/|N|`, with `H` the median of that axis's
own sampled `|net exposure|` history, applied after the ADR-0098 shrink and before the ADR-0100 rate.
It neutralizes `|N| − H` and leaves exactly `H` standing — hedge to the band edge, never to the
centre, which is the standard transaction-cost result (Leland 1985; Whalley & Wilmott 1997;
Zakamouline 2006). `H` is a quantile of the quantity's own measured history, not a risk-appetite
figure anybody invented — the ADR-0104 convention, so it self-calibrates to any feed. Strictly
one-way (`|q'| ≤ |q|`, sign preserved), silent while warming, and unwinds are untouched. Worked
example, pinned as a test: `$1m` long at `β̂ = 1.8` → full hedge `−6.428571` ES; at `H = $600,000`,
`f = 0.4` exactly → `−2.571428` ES, neutralizing `$400,000` and leaving the `$600,000` band edge.
ADR-0105 Proposed, two deferred-register rows, `./gradlew -Pci test` green.

**What to check next cycle.** `habitualNetUsd` and `excessFraction` on `/api/hedging` — whether the
band warmed at all (30 samples ≈ half an hour of hedge cooldowns) and whether it bound. Expect firm
**gross** flat-to-down (the ES overlay is itself gross) and firm **|net|** larger by construction, up
to `H`; that is the declared cost of the posture, not a regression. If it scores BAD, doubt the median
as the band width — not the existence of a band — and do **not** return to hedging from the first
dollar.

HOLD at 5/6 cycles, no change — but I found the code cause of the missing trade-trigger, and that the hedge is frozen while the strategy book has gone negative underneath it.

*(Every figure below is read from `/api/risk`, `/api/ops/jvm`, `/api/attribution`, `/api/hedging`,
`/api/signals/telemetry`, `logs/report.md`, the scorer's snapshot, or the repo source. None is authored
here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** Total PnL **$105.97** on the SITUATION header, **-47.48** since last run and **+0.08**
   over the last 3; live `/api/risk` `total.totalPnl` reads **111.03425652**. The `2026-07-31T18:35:50Z`
   heartbeat has `pnl_growth_pct` **-40.91** against `pnl_target_pct` **1.0**, `on_track` **false**,
   `stale` **true**, `underwater` **false**. Down run-over-run and off target.
2. **Risk.** Gross **$38262.24** = **2.6%** of the $1,500,000 firm cap (headroom **$1,461,738**); net
   **$17218.54** = **1.7%** of the $1,000,000 net cap. `Flags: none`. Gross rose **+5619.53** this
   window — that is deployment with room to spare, not a danger.
3. **Cause.** The pending change (`4f67f0515`) is **✅ VERIFIED deployed and landed**: `uptimeSeconds`
   **1460** against `asOfMillis` **1785524432537** puts boot at **2026-07-31T18:36:12Z**, after the
   revert commit; ADR-0133 reads `**Status:** Reverted` and no Java source mentions `0133`. Its effect
   is the scorer's to judge at 6/6.
4. **Danger.** None. Not near a cap, breaker clear. But see below — the headline is being flattered.

## Where the money actually went

`/api/attribution` reads `firmTotal` **111.03425652** = HEDGE **160.19086234** + ALPHA **-13.33312927**
+ MACRO **-35.82347655**, on `totalFees` **270.415875** of which ALPHA paid **264.094908**.

The HEDGE and MACRO figures are **byte-identical to last cycle's reading**. Neither book traded. ALPHA
read **40.37358247** last cycle and reads **-13.33312927** now, so the entire run-over-run fall is the
strategy book — and it fell while gross exposure rose. `hedgeMasking` is **true** while `/api/hedging`
`covarianceReady` is **false**: the firm total is positive only because a *frozen* hedge P&L sits on top
of a strategy book that has now crossed from "not covering its fees" (Rule 217, last cycle) to negative
outright. That is the live bleed, and it enters the register at **#2**.

## What I learned about #1 — it is not an empty column, it is a missing concept

I re-verified item #1 and it is ⚠️ STILL-BROKEN: of this window's 60 `recent_orders`, **32 of 32 FILLED**
and the **1 ROUTED** carry a NULL `reason`, while **27 of 27 CANCELLED** carry text. This cycle I traced
the cause instead of restating the symptom. In `OrderService.routeApproveAndFill`, reasons are written
only on failure branches (`gate.reason()`, `"no market data for …"`, `"IOC — not marketable on
arrival"`); the success path is `transition(order, OrderStatus.ROUTED, null)` and submit publishes
`publishOrderEvent(order, null)`. So `reason` records **why a status changed**, not **why the desk
wanted the trade** — and only orders that fail ever have a status change worth explaining.

That reframes the fix and makes it concrete: the trigger must be threaded from the fusion/strategy call
site that decides to trade into `submit`, not recovered at the order layer. It is ready to ship the
moment the hold clears.

## Decision

**No change.** `scripts/score-change.py score` prints `still accumulating evidence (5/6 cycles)` and
`reports/.pending-baseline.json` is present — one cycle from a verdict, and a new change now would throw
away five cycles of evidence on the revert. On the standing priority nothing moved: at `horizonSeconds`
**3600**, momentum **5.749961141428572** bps over **10** cohorts, social **5.743293811097191** over
**22** and reversion **4.911734698471268** over **63** all remain small against their own cohort
dispersion (**29.247194899516227**, **25.910850044717932**, **32.17586652023403**), while trend
**-2.044825086028045** and xsreversion **-6.146473850702721** are negative. The edge gate correctly lets
none of them size. Next cycle's one change is register **#1**, with the trade-off stated up front: it
moves no money and will most likely score ⚠️ INCONCLUSIVE, which is the right outcome for a change that
buys evidence — and every money change after it, starting with #2, depends on being able to name the
trigger that opened the loser.

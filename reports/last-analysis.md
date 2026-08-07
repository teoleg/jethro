# Last analysis — 2026-08-07 15:30Z

**No change: the ADR-0116 window on `403a95ffd` is 4/6. The freeze paid for itself a third time — splitting
this window's orders by ORIGIN rather than by status exposed a structural one-way ratchet: the desk's build
leg fills 4 of 11 while its cut leg fills 41 of 41, because an ADR-0084 passive entry is swept by the next
30-second re-plan and a reduce crosses as MARKET. That is now must-fix #1, above the σ seed.**

## 1. Money

Total PnL **-$902.04**, **-$50.34** on the run and **-$4.17** over the last three. The deterministic
heartbeat reads `pnl_growth_pct` **5.14%** against `pnl_target_pct` **1.0%** — `on_track=true`,
`stale=false`, `underwater=true`. First down-run since the book came off dormant; small, and inside the
noise of a book this size, so I am not treating -$50.34 as a signal on its own.

## 2. Risk — not danger, and for the first time the concern points the other way

Gross **$18,465.91** = **1.2%** of the firm gross cap $1,500,000 (headroom **$1,481,534**); net
**$3,800.15** = **0.4%** of the $1,000,000 net cap. `breaker.halted` **false**, `riskCuts` **[]**,
`edgeGate` **null**. Feed healthy: `provider: alpaca`, `ticksIn` **65004**, `ticksDropped` **0**.

Gross **fell -$1,273.50** this window. With 98.8% of the cap unused, a shrinking book is the failure mode
CLAUDE.md names — undeployed capital under the budget — not safety. That is what I went looking for a
mechanism behind.

## 3. Cause — the ratchet, and it is mechanical, not a market call

I have been reading `orders_by_status` in aggregate and concluding the cancel path was healthy. Split by
`origin` it is not:

| origin | FILLED | CANCELLED | fill rate |
| --- | --- | --- | --- |
| `fusion entry — target increase` | 4 | 7 | 36% |
| `fusion reduce toward a smaller target` | 41 | 0 | 100% |

Every cancel carries `reason` = `fusion re-plan — passive order superseded by a fresh target (ADR-0084)`.
The mechanism reads end-to-end in code: `FusionExecutor.route` posts a risk-increasing delta as a `DAY`
`LIMIT` at the mark and crosses a risk-reducing one as `MARKET`; `FusionLifecycle.tick` opens every cycle
with an unconditional `cancelStalePassiveOrders()`; `jethro.fusion.interval-seconds=30`. So an entry gets at
most 30 seconds resting at the mid and a cut fills instantly.

ADR-0140's partial-adjustment `aim` is *supposed* to converge slowly (`AAPL` `aim` **28.976441** against
`targetQty` **266.629944**). What is not supposed to happen is that the build half of that convergence is
then multiplied by a ~0.36 fill probability while the cut half is multiplied by 1.0. The book can only
drift below its own target, which is exactly what gross did.

## 4. Change vs market — the window's move is credited to NOTHING, fourth cycle running

`403a95ffd`'s branch has still fired zero times (**0** `fusion exit — target decayed to flat` in the whole
report), so none of the move is its. And the σ story is now a fitted curve rather than an argument: across
four observations today with nothing edited between them, `ops_jvm.uptimeSeconds` **838 → 2638 → 4438 →
6238** maps to `streamVolMeasuredNames` **1 → 2 → 21 → 21**, and the arithmetic predicted the switch at
**3630 s** — which falls exactly in the 2638→4438 gap. σ is a pure function of process lifetime. The
remaining PnL move sits on 21 positions inside a single window and I am not going to guess a split between
market and mechanism that the numbers do not support.

## 5. What next cycle does

Once the window closes, the one change targets **#1**: make the passive sweep conditional on intent actually
changing — a working entry survives the re-plan when the fresh target still wants the same side, same name,
size no smaller, and is cancelled only on a side flip, a drop from the target book, or a smaller size, with
the surviving order counted against the fresh delta rather than re-posted alongside it. That restores
working time to the build leg without crossing the spread and without touching the reduce leg or the
deterministic floor. Architecturally significant ⇒ ADR in the same commit. Its VERIFY-BY is the entry fill
rate rising above **36%**, guarded by the reduce leg staying at **100%** so an equalising regression cannot
read as a pass.

The σ seed drops to **#2** — still broken, unchanged fix, deferred on cost only: it bites the first hour
after each restart, the ratchet bites every 30-second tick the market is open.

# Last analysis — 2026-08-07 15:00Z

**No change: the ADR-0116 window on `403a95ffd` is 3/6 — and the freeze paid for itself again, closing out
item #1's root cause. The σ seed can NEVER complete, by arithmetic: it needs 121 prices at a 30 000 ms step
(≈ 60 min of contiguous series) and the loop's own teardown cadence never leaves an island longer than one
app lifetime, so σ only ever warms from live prints.**

## 1. Money

Total PnL **-$837.74**, up **+$22.43** on the run and **+$60.14** over the last three. The deterministic
heartbeat reads `pnl_growth_pct` **4.2%** against `pnl_target_pct` **1.0%** — `on_track=true`,
`stale=false`, `underwater=true`. Not bleeding; still below the water line.

## 2. Risk — not danger, this is the book doing what it is supposed to do

Gross **$17,910.83** = **1.2%** of the firm gross cap $1,500,000 (headroom **$1,482,089**); net
**$7,600.62** = **0.8%** of the $1,000,000 net cap. `breaker.halted` **false**, `riskCuts` **[]**,
`riskCutStoppedNames` **0**, `edgeGate` **null**. Feed healthy: `provider: alpaca`, `ticksIn` **49689**,
`ticksDropped` **0**. Gross rose with 98.8% of the cap unused — the intended direction off dormant, not a
danger flag.

## 3. Cause — the move is the CLOCK again, not the change, and I am crediting `403a95ffd` with none of it

Nothing was edited between 14:30Z and 15:00Z, yet the book went from one `NQ` future to **21** equity
positions and gross **$11,677.55 → $17,910.83**. The only thing that moved is uptime:

| read | 14:30Z | 15:00Z |
| --- | --- | --- |
| `ops_jvm.uptimeSeconds` | 2638 | 4438 |
| `streamVolMeasuredNames` | 2 | 21 |
| `fusion_targets.instruments` | 23 | 21 |
| `insideBuffer` | 22 | 15 |
| `grossExposure` | $11,677.55 | $17,910.83 |

`streamVolMeasuredNames` now equals `instruments` exactly (**21 of 21**), and the moment it did, the desk
deployed. That is item #1's conversion measured a third time, and it is entirely a function of the session
getting longer.

The window's **+$22.43** splits as realized **-898.10895975 → -842.93863544** and unrealized
**+37.94573000 → +5.20070888** — i.e. the `NQ` mark blip I refused to bank last cycle was realized, and the
fresh 21-name equity book is carrying a small negative unrealized (`byAssetClass` EQUITY
`unrealizedPnl` **-7.32152001**). None of it is attributable to `403a95ffd`, whose branch still has not
fired.

## 4. Danger

No. Nowhere near a cap, breaker not tripped, no risk cuts, feed clean.

## 5. Order-level post-mortem — item #3's cancel storm was an ARTIFACT, and it cleared on its own

The window ran **60** orders: **45 FILLED**, **13 CANCELLED**, **2 ROUTED** — a 75% fill rate against last
cycle's **1 of 12**. By reason: **25** `fusion entry — target increase`, **27** `fusion reduce toward a
smaller target`, **8** `auto-hedge EQUITY (ADR-0019)`. The self-cancellation ratchet I opened as item #3 was
a **one-name** pathology: with only `NQ` planned, every 30 s re-plan landed on the same working order. With
21 names planned the re-plans spread out and `NQ` filled **19 of 19**. Item #3 demotes below the line as an
artifact of a degenerate book, not a standing defect — but it is a watch item, because it will return the
next time item #1 narrows the book to one or two names.

## 6/7. Root cause of item #1 — closed this cycle, at the arithmetic

The 13:46Z boot logs terminate **every** sensor seed on `GAP_BREAK` or `HISTORY_EXHAUSTED` after covering
only **~920–1280 s**: trend `145 of 193` (AAPL), `72 of 193` (JNJ), `52 of 193` (CAT); reversion
`82 of 241` (AAPL), `40 of 241` (CAT). I had assumed retention was the constraint. **It is not** —
`jethro.ui.history-hours=12` and `data/ui-history/live/data.mdb` has been accumulating since Jul 27. The
real constraint is the shape of what is retained:

- The app only lives ~15–30 min per loop cycle, so the durable mark store is a **chain of short islands**
  separated by the loop's own teardown gaps.
- `SensorWarmup.GAP_TOLERANCE_SAMPLES` = **30** consumption steps. At σ's **30 000 ms** step that is 900 s;
  at trend's 5 000 ms step, 150 s. Every one of those is **narrower than the inter-cycle gap**, so the walk
  always truncates at the newest island.
- σ needs `warmupPrices()` = `vol-span` + 1 = **121** prices at `jethro.fusion.interval-seconds=30`
  ⇒ **3 630 s ≈ 60.5 min** of contiguous series. One app lifetime is ~900–1 800 s.

**So the σ seed cannot complete for any name, at any time of day, by construction** — the overnight session
gap is a special case of a defect that fires on every single cycle. Observed exactly: uptime 2638 s → 2
names measured; uptime 4438 s → 21. The desk is structurally unable to add risk for the first hour of every
process life, and a code change restarts the process — so next cycle's change will itself be locked out for
its first hour, which is a fact the ADR-0116 window has to absorb.

The fix goes at the **seed**, never at ADR-0126's gate: `history_status` reads `days: 1574`,
`instruments: 55`, `ready: true` — a deep, durable daily series that no session gap severs, which the σ
seed does not draw on. Scaling it to the sampling interval by the √time convention `StreamVolatility`
already documents is the candidate, and it errs conservative (a daily σ carries overnight jumps, so it
*over*states intraday σ, widening rather than tightening the ADR-0086 stop). That is next cycle's one
change, with its ADR — not this one, because the window is 3/6 and piling a change on top destroys the
evidence.

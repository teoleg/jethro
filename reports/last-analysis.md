# Last analysis — 2026-08-07 16:00Z

**No change: the ADR-0116 window on `403a95ffd` is 5/6, its final held cycle. The freeze paid again — this
time by catching a flaw in my own test. The entry fill rate I nominated last cycle as the proof metric for
must-fix #1 climbed 36% → 48% with nothing edited, so it would have graded the unbuilt fix as a pass on
noise. Replaced with the measure that did not move: 9 of the window's 12 cancels were superseded by a
same-side, no-smaller target — orders that should never have been cancelled at all.**

## 1. Money

Total PnL **-$908.75**, **+$10.00** on the window and **-$48.59** across the last three. The deterministic
heartbeat's last entry (`2026-08-07T15:34:57Z`) reads `pnl_growth_pct` **-2.32%** against
`pnl_target_pct` **1.0%** — `on_track=false`, `stale=true`, `underwater=true`. Not bleeding this window,
but flat-to-underwater and off the growth target, which is a monitored failure, not a rest state.

## 2. Risk — not danger; the book is undeployed, which is the opposite problem

Gross **$20,625.93** = **1.4%** of the firm gross cap $1,500,000 (headroom **$1,479,374**); net
**$1,036.11** = **0.1%** of the $1,000,000 net cap. `breaker.halted` **false**, `riskCuts` **[]**,
`edgeGate` **null**. `var95` **165.86**, `es95` **225.80**, `var99` **297.67** on `coveredExposure`
**$20,625.93** with `skippedExposure` **$0.00`. Feed healthy — `provider: alpaca`, `ticksIn` **77677**,
`ticksDropped` **0**; `regime` **CALM**, `trend` **CHOP**, `volRatio` **0.96**. Nothing here is near a
limit. With 98.6% of the gross cap unused, the live problem is that the desk cannot build, not that it
holds too much.

## 3. Cause — the pending change is still ungraded, and the freeze is binding

`scripts/score-change.py score` prints `403a95ffd still accumulating evidence (5/6 cycles) — held, not
scored this run`; `reports/.pending-baseline.json` is present. Per ADR-0116 that forbids a code change this
cycle. Its precondition still holds — **0** `fusion exit — target decayed to flat` against **26** reduces,
fifth cycle running — but absence is not evidence, so the verdict stays ungraded. It scores next cycle.

## 4. Danger — no

The only flag is UNDERWATER. Not near the exposure cap, not near the drawdown breaker, no risk cuts firing.

## 5. Order-level post-mortem — the ratchet re-measured, and my proof metric failed

Splitting `recent_orders` by origin a second time: entry **11 FILLED / 12 CANCELLED**, reduce **26/0**,
hedge **11/0**. **All 12 cancelled orders in the window are entry origin** — the reduce and hedge legs
never cancel, in either window measured.

But the fill *rate* moved 36% → 47.83% with no code change, because it depends on how often the tape
touches the mid inside the 30-second re-plan. Had I built the fix and graded it on that threshold, I would
have credited it for the weather. So I re-cut the same 12 cancels by what the *fresh* target wanted:
**9 were the same side at a no-smaller size**, **1** was a genuine side flip, **0** were size reductions.
Those 9 are orders the re-plan cancelled and then immediately re-expressed. `MSFT` posted
`SELL 6 → 7 → 11 → 13 → 14` over three minutes, cancelling four times before the fifth filled; `JNJ` posted
`BUY 23 → 28`, cancelled both, and never filled. That count is a property of the predicate the fix changes,
not of the tape — it is the new VERIFY-BY.

## 6. Change vs. market

Nothing was edited this cycle, so the **+$10.00** and the **-$422.67** gross move are credited to nothing.
Gross fell **-$1,273.50** last window and **-$422.67** this one while the entry fill rate rose — directionally
what the ratchet predicts, but two points on a noisy rate with no intervention is not evidence, and on 21
positions inside one window I cannot separate market from mechanism without guessing. Logged, not chased.

## Next cycle

`403a95ffd` scores, which frees the one change for must-fix **#1**: make `cancelStalePassiveOrders`
conditional on intent actually changing — a working passive entry survives when the fresh target still
wants the same side in the same name at a no-smaller size, counted against the fresh delta rather than
re-posted alongside it; cancelled only on a side flip, a smaller size, or the name leaving the target book.
Architecturally significant, so it ships with its ADR in the same commit. It touches order working time
only — the pre-trade guardrail, the drawdown breaker and the ADR-0049 veto all sit upstream of `route` and
are untouched.

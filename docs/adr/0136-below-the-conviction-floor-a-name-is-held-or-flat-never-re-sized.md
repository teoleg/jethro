# ADR-0136 — Below the conviction floor a name is held or flat, never re-sized

**Status:** Reverted
**Date:** 2026-08-03
**Reverted:** 2026-08-04 — see "Why it was reverted" at the foot of this record.
**Supersedes:** nothing. Narrows the ADR-0065 waiver of the ADR-0059 conviction floor.
**Related:** ADR-0059 (conviction floor), ADR-0065 (orphaned positions / the reduce waiver),
ADR-0080 (partial adjustment), ADR-0086 (trailing risk cut), ADR-0090 (an exit is not buffered),
ADR-0094/0101 (position buffer), ADR-0118 (trapped exit), ADR-0122 (edge gate off on the paper book),
ADR-0132 (deploy capital), ADR-0134 (order origination triggers).

> **REVERTED.** `scripts/score-change.py` scored the implementing commit `e3b33679d` **❌ BAD** at the
> close of its ADR-0116 evaluation window; the ledger row carries the computed vector, the t-statistic,
> the cycle count and the verdict — every number there is the scorer's, not this record's. The scorer's
> own `git revert` conflicted on the loop's report files and did not land, so the running code was
> reverted by hand in the next cycle, restoring **only** the two code paths (`FusionLifecycle` and the
> `application.properties` provenance comment) and deleting `ConvictionFloorRoutingTest`, while leaving
> this record and the loop's findings in place. The mechanism is **not** to be re-attempted as specified.
> What the window established is at "Why it was reverted" below; a future ADR that wants the conviction
> floor to bind on a partial reduce must supersede this one and answer that, not restate this design.

## Context

The ADR-0059 conviction floor (`jethro.fusion.min-forecast-to-route`, 5.0 on the Carver [-20,+20]
scale) exists to stop the desk churning on a weak or oscillating signal. ADR-0065 waived it for any
risk-**reducing** delta, with this stated reason, still in the code today:

> the conviction floor asks "is this view strong enough to put risk ON?". It has no business blocking
> a trade that takes risk OFF — and applied there it would permanently trap exactly the positions
> whose view has decayed to nothing.

That reason is sound, and it is a reason about **getting out**. The waiver as written was keyed on
`TargetPlanner.isRiskReducing` alone, which is equally true of a *partial rebalance toward a smaller
non-zero target*. So the two halves of the same cycle contradicted each other: the desk declared a
view too weak to open a position on, and then traded on it anyway — every cycle, at the ADR-0080
rate, paying fee and spread each time.

### What it looks like in the order log

Read from `recent_orders` for the 2026-08-03 18:00–19:00Z window (ADR-0134 origination triggers).
BAC, held short, tagged `fusion reduce toward a smaller target`:

| time (UTC) | order | forecast | floor |
| --- | --- | --- | --- |
| 18:53:15 | `BUY 1` | `0.3474462086964614` | 5.0 |
| 18:54:15 | `BUY 1` | `0.5363969925205933` | 5.0 |
| 18:55:16 | `BUY 1` | `0.7016479414426884` | 5.0 |
| 18:55:47 | `BUY 1` | `2.835935684486436` | 5.0 |
| 18:56:17 | `BUY 1` | `1.72006937226672` | 5.0 |
| 18:59:49 | `BUY 1` | `1.82687778188073` | 5.0 |

Six separate fills, each leaving the short open, none on a view the desk would have opened a position
with. `GOOG` (`-1.548`, `-3.930`) and `NVDA` (`-4.886`, `-3.415`) did the same thing in the same window.

### Why it is worth a decision now

The book's deficit is its fee bill, not its market risk. On the same window `/api/attribution` reads
`firmTotal -264.50191793` against `totalFees 346.044801`; the strategy book is `ALPHA -329.32225499`
on `feesPaid 334.905421`. Gross of fees the desk is roughly flat — the cost is the whole of the loss.
And per the clustered-denominator reading of `/api/signals/telemetry` (`stdCohortMeanBps` over
`cohorts`, not `stdReturnBps` over `resolved`), no source shows significant edge at any measured
horizon, so there is no measured expectancy for this turnover to be buying.

This ADR does **not** claim to fix that deficit. The dribble is a minority of the window's turnover;
the majority is the breadth-collapse round trip, which is tracked separately and whose two attempted
remedies (ADR-0135 and its revert) were both scored ❌ BAD. What this fixes is a self-contained
inconsistency that is cheap to remove, cannot trap a position, and can only ever reduce turnover.

## Decision

**Below the conviction floor a name has exactly two states — held or flat. It is never re-sized.**

The floor is waived for an order that takes the position exactly flat, and for nothing else:

```
|combined forecast| ≥ floor              → route (unchanged, whatever the delta does)
reducing AND currentQty + deltaQty = 0   → route: this is the EXIT, never blocked
otherwise                                → suppressed
```

Implemented as `FusionLifecycle.clearsConvictionFloor`, a pure static function at the one place the
routing decision is made.

### Why the exemption is "the order lands flat", not "targetQty is flat"

A close does not only arrive as a flat target. ADR-0118's trapped-exit branch in `PositionBuffer`
plans the whole position out while `targetQty` is still non-zero — the ADR-0102 clamp has put the aim
at flat because the target sits on the other side. Keying the waiver on what the **order does to the
position** rather than on which upstream field happens to read flat is what makes "never trapped"
hold for every path at once, including paths added later.

Every control that means *get out* plans the name flat — the ADR-0086 chandelier cut, the ADR-0065
orphan unwind, the ADR-0027 firm breaker above them — so each lands exactly flat and passes here
untouched. **The deterministic floor is not narrowed by one order.**

### One-way

Every branch either returns the same answer as the ADR-0065 rule or returns false where that rule
returned true. It can never route an order the previous rule blocked, never enlarge one, and never
change a delta. Risk can therefore only come off more slowly, never go on faster —
`ConvictionFloorRoutingTest.theRuleIsStrictlyOneWay` asserts this over the cross product of the
forecast and position cases.

## Consequences

- A name whose view has decayed below the floor stops being re-sized. It is held until the view
  recovers above the floor, the target goes flat, or a control cuts it — never stranded.
- Turnover and fees fall by the sub-floor partial reduces. On the observed window that is a minority
  of turnover: this is a real but **partial** attack on the cost problem, and is presented as such.
- Exposure may sit marginally higher, because the slow bleed-down of sub-floor positions stops. Gross
  was `$54,238.11` against the `$1,500,000` firm cap (3.6%, `$1,445,762` of headroom) when this
  shipped, so this is deployment inside the ADR-0132 budget, not cap pressure. The pre-trade
  guardrail, the firm caps and the drawdown breaker are untouched and still bind.
- No new dial and no new number: the same `min-forecast-to-route=5.0`, applied to one more branch.

### Alternatives rejected

- **Leave it.** The desk goes on paying spread to track a view it has declared too weak to act on.
- **Waive the floor only when `targetQty == 0`.** Simpler to read, but strands the ADR-0118 trapped
  exit, whose `targetQty` is non-zero — it would have reintroduced the trap ADR-0065 was written
  to prevent.
- **Raise the floor instead.** That is a dial change with no provenance (the 5.0 is already flagged
  self-chosen), and it would suppress entries the desk does want, not just the sub-floor re-sizing.
- **Revive the ADR-0101 cost-derived buffer width.** It measures `max(0.10, min(1, 2C/μ))`, and with
  measured `C` above measured `μ` here it pins at the `1.0` cap — one full average position, which an
  aim clamped inside its own target (ADR-0102) can never cross. That freezes the book, which is the
  exact failure ADR-0133 was written to patch and was itself scored ❌ BAD. Not attempted.

## Verification

`ConvictionFloorRoutingTest` — the six BAC forecasts suppressed; the exit routed at forecast `0.0`,
`-0.0`, `4.999` and on a fractional position; strong-view entries and above-floor partial reduces
untouched; sub-floor entries blocked as before; an overshooting flip gated as the entry it is; and the
one-way property over the cross product.

**VERIFY-BY next run:** in `recent_orders`, the count of orders tagged
`fusion reduce toward a smaller target` whose `[forecast=…]` has `|forecast| < 5.0` must be **0**
(it was 10 in the 18:00–19:00Z window: 6 BAC, 2 GOOG, 2 NVDA). Orders tagged
`fusion exit — target decayed to flat` must still appear at sub-floor forecasts — their absence would
mean an exit had been trapped, and would falsify this change rather than confirm it.

## Why it was reverted (2026-08-04)

The change **passed its own falsification test and still lost the vector.** Both halves held, verified
the cycle after deployment by splitting the order log at the JVM boot time: sub-floor
`fusion reduce toward a smaller target` orders went to zero (they had been 13 pre-boot — BAC ×9, GOOG ×2,
NVDA ×2), while `fusion exit — target decayed to flat` still routed at a sub-floor forecast, so no exit
was trapped. The suppression was real and not merely an absence of opportunity: `fusion_targets` in the
same post-boot window still planned non-zero deltas on three sub-floor names (`NEE`, `JNJ`, `NVDA`) that
never became orders. The mechanism did exactly what this ADR specified.

The scorer then graded it ❌ BAD on the risk-adjusted return over the full evaluation window, with the
t-statistic clearing the hurdle on the losing side. **That is the finding: the mechanism worked and the
objective still got worse, which means the turnover it removed was not what was costing the money.**
This ADR said so itself under "Scope, stated honestly" — the sub-floor dribble is a minority of the
window's turnover — and the window has now priced that admission. The majority of the churn is the
breadth-collapse round trip: at the equity close the effective source count drops to one, every combined
forecast decays to flat, and the whole book is liquidated in a single sweep of
`fusion exit — target decayed to flat [forecast=±0.0, sources=1]` orders. That path is untouched here,
and it is where the fee bill is made.

Three mechanisms have now been tried against this bleed and all three scored BAD: ADR-0135 (hold an
unestimable view), its own revert, and this one. Read together they say the cost is not in *which* orders
the floor lets through but in the **breadth collapse that empties the book at the close** — a
sensor-availability defect upstream of every routing rule, not a routing rule. The next attempt belongs
there, and it must not be another variation on the conviction floor.

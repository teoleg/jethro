The desk planned 21 targets and routed none — and the report physically cannot say which suppressor did it, because `/api/fusion/targets` is cut at a 6000-char cap exactly before the `edgeGate` and `insideBuffer` fields.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, `recent_orders`, or the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — last cycle's change (`026cda49d`, the ADR-0136 revert): ✅ DEPLOYED, ⏳ NOT YET SCORED

Deployment is confirmed, so this is live code and not a stranded commit: the commit is stamped
`2026-08-04T13:37:19Z`, and `ops_jvm.uptimeSeconds` **`1325`** against `traffic.timestampMillis`
**`1785852002696`** (`2026-08-04T14:00:02Z`) puts boot at **`2026-08-04T13:37:57Z`** — after it. The
mechanism is gone from the running code: `grep` over `app/` for `clearsConvictionFloor` and `ADR-0136`
returns nothing.

**It is not graded and must not be disturbed.** There is no ledger row for `026cda49d` and
`reports/.pending-baseline.json` still exists — the ADR-0116 window is still accumulating. Per the
contract that means **no code change this cycle**. What follows is diagnosis for the next one.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-314.91** (`/api/risk` `.total`, firm headline incl. the hedge). Unchanged
   since last run and across the last three (**+0.00**, **+0.00**). `UNDERWATER`. Diagnostic split:
   `ALPHA -371.22428289`, `HEDGE +113.11464104`, `MACRO -56.79950536` against `totalFees 356.521740` —
   **the fee bill is still larger than the entire firm deficit.** Gross of fees this desk is roughly
   flat; the cost is the loss.
2. **Risk.** Gross **$0.00**, net **$0.00** — **0.0%** of the $1,500,000 firm cap, **$1,500,000** of
   headroom. `var95 0.00` with note `"no positions"`; `breaker.halted false`; hedge axis `EQUITY`
   `netExposureUsd 0.0`, status `FLAT`. The book is **DORMANT** — a failure to attack, not safety.
3. **Cause.** Not attributable to last cycle's revert. It has been live 22 minutes and has **placed no
   orders at all**: the newest row in `recent_orders` is `2026-08-04 13:34:31`, *before* the
   `13:37:57Z` boot. The book was already flat when it booted.
4. **Danger.** No. Bleeding-near-the-cap is the danger state and gross is at 0.0% of cap with the
   breaker clear. The live problem is the exact inverse — the largest *opportunity*, not a risk.

## The real finding — the desk has opinions and is not acting on them

The session is open (`feeds` alpaca `connected true`, `lastUpdateAgeMillis 16`, `delayed false`;
`ticksIn 26538`, `ticksDropped 0`; `AAPL` mark `ageMillis 333`). `fusion_targets` at
`atMillis 1785851991548` reports `routing: true` over **21 instruments** with real conviction —
`JPM combinedForecast 13.201644846856365 → targetQty 921.47787`, `BAC 11.759593061749303 → 4682.485093`,
`PG 9.730455372959304 → 1672.761797` — and **every visible target carries `currentQty 0` and
`deltaQty 0`**. This is not an absence of opportunity. The desk sized 21 names and planned to trade
none of them, 22 minutes into an open session.

**Two mechanisms can produce exactly that, and the report cannot distinguish them.** In
`FusionLifecycle` the gate clamp runs at line 302 (`reduceOnlyWhere`) and the buffer at line 321, both
*before* `lastBook` is published at line 324 — so the `deltaQty 0` in telemetry is post-both. Either
(a) `EdgeGate` is shut, and `TargetPlanner.reduceOnly` projects every increase onto zero because
`currentQty` is zero; or (b) the ADR-0094 `PositionBuffer` is holding every name inside its band. These
call for **opposite** remedies, and nothing in the report picks between them.

**Why it can't: a truncation bug in the report generator.** `scripts/system-report.py:405` emits
`json.dumps(data, indent=1)[:6000]` per endpoint. The delivered `fusion_targets` block measures
**6001 characters** — hard-cut mid-object inside the `targets` array. The `TargetBook` record orders its
fields `… targets, edgeGate, portfolioRiskMultiplier, …, aims, insideBuffer, …`, so **every field that
would name the suppressor sits after the cut and is discarded every single cycle.** The controller
serializes them; the report throws them away.

That is the honest explanation for the ledger's recent shape. ADR-0135, its own revert, and ADR-0136
were three consecutive routing-rule changes at this layer, all graded ❌ BAD. Rule 280 concluded the
defect was one layer up. It is — but the layer up is **observability**: the loop has been proposing
fixes to a suppressor it cannot see. Restoring those fields costs no risk and puts no money on; it is
what makes the *next* change aimed instead of guessed. It is now must-fix **#1**, ahead of the breadth
collapse, and it is the one change for next cycle — once `026cda49d` has a ledger row.

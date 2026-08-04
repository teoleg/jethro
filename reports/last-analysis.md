Fixed the report truncation that hid the suppressor — and it named it on the first read: the ADR-0094 buffer holds all 22 names, the edge gate is open, and the risk-scaling stage zeroes almost every aim.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, `recent_orders`, or the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — last cycle's change (`026cda49d`, the ADR-0136 revert): ✅ DEPLOYED, ⏳ STILL NOT SCORED

Deployment re-confirmed against this run's telemetry: `ops_jvm.uptimeSeconds` **`1390`** at
`traffic.timestampMillis` **`1785853801356`** (`2026-08-04T14:30:01Z`) → boot **`2026-08-04T14:06:51Z`**,
well after the commit's `13:37:19Z`. There is still no ledger row for it and
`reports/.pending-baseline.json` still holds its snapshot, so its ADR-0116 window is still accumulating.
It has placed **no orders**: the newest `recent_orders` row remains `2026-08-04 13:34:31`, before its boot.

**Its evidence is untouched by this cycle.** The one change made here is to `scripts/system-report.py` —
the offline report generator. It is not in the JVM, not on the trade path, and cannot alter PnL, exposure
or any routing decision, so it cannot contaminate the pending measurement. I deliberately did **not** run
the `score-change.py baseline` command, so `026cda49d` keeps the baseline it must be graded against.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-314.91** (`/api/risk` `.total`, firm headline incl. hedge). Unchanged since
   last run and across the last three (**+0.00**, **+0.00**). `UNDERWATER`, `stale=true`, `on_track=false`.
   `totalFees 356.521740` against a firm deficit of `-314.90914721` — **the fee bill is still larger than
   the entire loss**; gross of costs this desk is roughly flat, and the cost is the loss.
2. **Risk.** Gross **$0.00**, net **$0.00** — **0.0%** of the $1,500,000 firm cap, **$1,500,000** headroom.
   `DORMANT`. A failure to attack, not safety.
3. **Cause.** Not attributable to code. The book was already flat before the current boot and nothing has
   traded since `13:34:31`.
4. **Danger.** No. `DANGER` requires bleeding near the cap; gross is at 0.0% of cap and the breaker is
   clear. The live state is the inverse — the largest *opportunity*, not a risk.

## The change: stop the report from silently discarding the fields that name the suppressor

`scripts/system-report.py:405` rendered each endpoint as `json.dumps(data, indent=1)[:6000]` — a cut of
the *string* at a fixed offset. On any endpoint larger than the budget, every field ordered after the
bulky one was discarded and the block ended mid-object. Measured against this run's live payloads,
`/api/fusion/targets` serialises to 14,266 chars and the old form lost **17 of its 22 top-level fields** —
`edgeGate`, `insideBuffer`, `aims`, `riskCuts`, `portfolioRiskMultiplier` and the whole vol-budget group,
all of which sort after the long `targets` array. `/api/risk` (10,881) and `/api/discovery` (20,757) were
cut the same way.

The fix elides the **bulk** instead of the tail: long arrays are progressively capped until the object
fits, each elision stated explicitly (`… N of M elements shown`). Every top-level key now survives — on
the live payloads, lost-fields goes from 17 to **0** for `fusion_targets`, and the rendered block is
*smaller* (5,449 chars in a full end-to-end run) than the old truncated one, so this costs no budget.
Where an object cannot fit even with every array emptied it now says `CUT — treat the tail as UNKNOWN,
not absent` instead of ending mid-object. A full `system-report.py` run completes with no block at or
over budget.

## It paid immediately — and it overturns the diagnosis three BAD changes were built on

Reading the previously-hidden tail from the live endpoint this cycle resolves the ambiguity Rule 283
recorded, in one line: **`edgeGate` is `null`** — the edge gate is *not* shutting the book — and
**`insideBuffer` is `22`**, i.e. *every* name is being held inside its ADR-0094 position-buffer band. The
suppressor is mechanism (b), the buffer, not mechanism (a), the gate. `routing` is `true`, `riskCuts` is
`[]`, `riskCutStoppedNames` `0`, `breaker.halted false`.

The `aims` map — also previously discarded — shows why the two readings looked alike: `GOOG 2.445123`,
`NVDA -8.537723`, `AAPL -8.609491`, `CAT 0.984813`, `AMZN 3.444406`, and **zero for the other 17 names**,
against `targets` entries carrying far larger `targetQty` with `currentQty 0` throughout. So the desk is
not being blocked from acting on 21 strong views; the risk-scaling stage collapses almost all of them to a
zero aim, and the handful that survive are single-digit share counts the buffer band then absorbs. That is
a **sizing** question, not a routing-permission question — the opposite of what ADR-0135, its revert, and
ADR-0136 each assumed. All three graded ❌ BAD prescribing into this blind spot.

No PnL or exposure move is claimed for this change and none is possible: it alters no trading code. Next
cycle's change targets the sizing collapse, aimed with numbers instead of guessed — but only once
`026cda49d` has a ledger row.

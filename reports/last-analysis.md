No change — held at 2/6 cycles; the restart-liquidation defect is now CONFIRMED rather than suspected: XOM was sold short on a 3-source view and bought back 39 seconds after a reboot on a 1-source view, same size, both sides at 1.00 bps.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, `recent_orders`, `orders_by_status`, `turnover_cost_by_name`, the boot log, or computed from
those by script. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — verify last run's change first

**`120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric.** Live `/api/fusion/targets` sums to
**$499,999.999386065** of planned gross against the **$500,000** `jethro.risk.max-gross-exposure` the
guardrail permits — **0.99999999877×**. The cap binds a second consecutive cycle. Its PnL verdict is the
scorer's, not mine, and stands at **2/6 cycles**.

**`scripts/score-change.py score` prints `120b22b41 still accumulating evidence (2/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` is present.** Under ADR-0116 that forbids a second
change on top of an open measurement window, so I made **no code edit** and recorded **no baseline**. The
cycle's work went into Step 0 and into `reports/must-fix.md`.

**Item #1 of the last block: ⚠️ STILL-BROKEN — and its VERIFY-BY resolved *against* it, which promotes it
from hypothesis to confirmed mechanism.** It asked for zero cold-sensor WARNs at boot and no `sources=1`
liquidation in the five minutes after start. Both failed, on an independent second boot.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-616.93791140** (`/api/risk` `.total`, firm headline incl. hedge) — **down
   $6.09** since last run, **down $122.80** over the last 3. `UNDERWATER`. By book: ALPHA
   **-$582.76196433**, HEDGE **+$22.62355829**, MACRO **-$56.79950536**.
2. **Risk.** Gross **$30,409.11287500** = **2.0%** of the $1,500,000 firm cap, headroom **$1,469,591**;
   net **$4,677.20712500** = **0.5%** of the $1,000,000 net cap. `riskCuts []`, `bookVolBrake 1.0`,
   `portfolioRiskMultiplier 1.0`, breaker untripped. **Not** a danger state — the opposite: a near-DORMANT
   book holding **$27,962.865** against its own **$499,999.999386065** plan, **5.59%**, with **12 of 20**
   names flat. Under ADR-0132 that unused $1.47M of headroom is the failure to attack, not safety.
3. **Cause.** No change shipped this window (code frozen at 2/6), so the move is the running desk's own
   behaviour. Gross rose **+$9,587.54** — the book partially rebuilding after the restart wave, which is
   the direction ADR-0132 wants, not a concern at 2.0% of cap.
4. **Danger.** No. Bleeding, but at 2.0% of the gross cap with the breaker untripped. The response is to
   fix the cost mechanism and redeploy, never to de-risk.
5. **Order-level post-mortem.** Fees remain the majority of the loss: firm **-$616.93791140** against
   `totalFees` **$381.753017** ⇒ pre-fee trading of **-$235.18489440**, so **fees are 61.88%** of the
   deficit. Cumulative LIVE turnover **$4,344,591.80** = **142.87×** firm gross, over **2,277** fills;
   **5,264** FILLED / **1,951** CANCELLED / **128** REJECTED.
6. **Memory.** Rules 294–307 applied. Rule 303 in particular: the ledger's flagged auto-revert of
   `026cda49d` stays **deliberately not completed**, because `026cda49d` is itself the revert of the
   graded-BAD ADR-0136 and completing it would re-apply a rejected mechanism.
7. **Change vs. market.** Nothing this window is attributable to code — none shipped. The restart round
   trips described below are **baseline behaviour of the running system**, reproduced on a second
   independent boot, not the effect of any change I made.

## The finding: the restart liquidation is confirmed, and it is priced

The 17:07:55Z boot (`traffic.timestampMillis` **1785864602305** − `ops_jvm.uptimeSeconds` **1327**) re-ran
the identical cold-start pattern — **14** equities cold on the trend sensor at 17:08:08–17:08:44Z, **2** on
reversion. **Thirty-nine seconds after boot**, at 17:08:34Z, the desk liquidated XOM and CAT with reason
`fusion exit — target decayed to flat [forecast=-0.0/0.0, sources=1]`; MCD followed at 17:14:09Z.

XOM is the clean, complete case. `SELL 15` at **16:38:50.180388Z** on
`fusion entry — target increase [forecast=-5.086943349481853, sources=3]` — a three-source conviction
short. `BUY 15` at **17:08:34.605741Z** on `fusion exit — target decayed to flat [forecast=-0.0,
sources=1]` — same size, opposite side, at **1.00 bps** each way. The view did not change its mind; the
process died, its sensors came back cold ("still cold for XOM after seeding **138 of 193** stored prices"),
and a one-source view is planned flat. That is a full round trip bought and paid for by the deployment
cadence, and it recurs every cycle.

**Two things I checked that sharpen the fix and rule out a wrong one.** First, the warm-up requirement has
**zero margin**: tracing `EwmacTrendForecaster.update`, `warmupSamples()` (`slow-span 64` + 1 +
`normalisation-span 256`/2 = **193**, matching the log) is the *exact* minimum number of prices needed to
publish, so the seed asks for precisely what it needs and not one more — **NEE at 192 of 193 is as blind as
CAT at 130**. Second, the walk did **not** stop at the process boundary: seeds of 130–192 mean it crossed
into the previous process's marks and terminated further back, so "the restart gap breaks the walk" is
*not* established. `SensorWarmup.seedPrices` has exactly two short-exits — the hole `break` at
`GAP_TOLERANCE_SAMPLES (30) × step` and the `LOOKBACK_MULTIPLE (2) × samples × step` read window — and
naming which one binds is the fix's first job, which is why next cycle's change must log the terminator,
not just widen a constant.

A distinct defect fell out of the same log: twelve rates names seed the **full** 193/193 and 241/241 and
stay cold, because `update` returns cold while step vol is zero and never advances the scale estimator. A
flat stored series is permanently sensorless. Filed as item #4 — dead coverage on MACRO, not the bleed.

## What happens next cycle

The one change targets must-fix **#1**: repair the warm-restart seed so the sensors publish at boot —
seed with margin above `warmupSamples()`, reach far enough back to cover the full warm-up span, and log
which terminator fired. It changes ADR-0071/ADR-0114 semantics, so it ships with its ADR
(`Status: Implemented`). It is explicitly **not** ADR-0135's "hold instead of liquidate", which is graded
❌ BAD and will not be re-attempted.

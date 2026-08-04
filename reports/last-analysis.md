No change — held at 3/6 cycles; the restart-liquidation defect's terminator is now measured, not guessed: 16 of 18 warm-restart seeds die on read-window exhaustion, the gap tolerance never fires once, and the two names that seeded FULL are exactly the two whose print spacing matches the window's assumption.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, `recent_orders`, `turnover_cost_by_name`, `/api/history`, the boot log, or computed from those
by script. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — verify last run's change first

**`120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric.** Live `/api/fusion/targets` sums to
**$498,877.903542685** of planned gross against the **$500,000** `jethro.risk.max-gross-exposure` the
guardrail permits — **0.99775580708537×**. The cap binds a third consecutive cycle and no name flipped
side. Its PnL verdict is the scorer's, not mine, and stands at **3/6 cycles**.

**`scripts/score-change.py score` prints `120b22b41 still accumulating evidence (3/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` is present.** Under ADR-0116 that forbids a second
change on top of an open measurement window, so I made **no code edit** and recorded **no baseline**. The
cycle's work went into Step 0 and into `reports/must-fix.md`.

**Item #1: ⚠️ STILL-BROKEN — reproduced on a third independent boot, and its mechanism is now closed out.**

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-599.45227577** (`/api/risk` `.total`, firm headline incl. hedge). The report's
   18:00:02Z snapshot read **$-577.41** — the desk is trading actively between the two reads, so I quote
   both rather than pick the flattering one. Since last run **+$36.36**; over the last 3 runs **-$43.64**.
   `UNDERWATER`. By book: ALPHA **-$564.74731276**, HEDGE **+$22.09454235**, MACRO **-$56.79950536**.
2. **Risk.** Gross **$49,356.54110000** = **3.3%** of the $1,500,000 firm cap; net **$-14,663.92890000**
   against the $1,000,000 net cap. Breaker untripped, `riskCuts` empty. **Not** a danger state — the
   opposite. The desk holds $49k against its own **$498,877.90** plan, under **10%** of it. Under ADR-0132
   that ~$1.45M of unused headroom is the failure to attack, not safety, and gross **rising** off the
   restart trough is the direction the mission wants.
3. **Cause.** No change shipped this window (code frozen at 3/6), so the move is the running desk's own
   behaviour. Gross rose because the sensors finally warmed ~8 minutes after boot and the desk rebuilt on
   three-source conviction — the same rebuild it pays for every cycle.
4. **Danger.** No. Bleeding, but at 3.3% of the gross cap with the breaker untripped. The response is to
   fix the cost mechanism and redeploy, never to de-risk.
5. **Order-level post-mortem.** The window splits cleanly at the boot. **Before warm-up:** PFE `SELL 12`
   `[forecast=0.0, sources=0]` and HD `BUY 1` `[sources=1]` at **17:39:45Z**, 40 s after the 17:39:05Z
   boot; JPM `SELL 24` `[sources=1]` at 17:40:46Z; CAT `SELL 3` `[sources=1]` at 17:45:50Z — four
   liquidations triggered by blindness, not by a view. **After warm-up:** from 17:46Z every order carries
   `sources=2/3` and a forecast of ±5 to ±8 — WMT, GOOG, CVX, MSFT, AAPL, PG, MCD entries. Same desk, same
   half hour, opposite behaviour, and the only thing that changed is whether the sensors had caught up.
   Fees remain the majority of the loss: firm **-$599.45227577** against `totalFees` **$387.112525** ⇒
   pre-fee trading of **-$212.33975077**, so **fees are 64.58%** of the deficit. Cumulative LIVE turnover
   **$4,402,699.14** = **89.20×** gross.
6. **Memory.** Rules 294–311 applied. Rule 303 in particular: the ledger's flagged auto-revert of
   `026cda49d` stays **deliberately not completed**, because `026cda49d` is itself the revert of the
   graded-BAD ADR-0136 and completing it would re-apply a rejected mechanism.
7. **Change vs. market.** Nothing this window is attributable to code — none shipped. The liquidate-then-
   rebuild pattern is **baseline behaviour of the running system**, now reproduced on three independent
   boots, not the effect of any change I made.

## The finding: Rule 310's open question is answered, and it was not the obvious answer

Rule 310 explicitly warned against assuming the process-restart gap truncates the warm-restart seed, and it
was right to. I re-implemented `SensorWarmup.seedPrices` faithfully — same `GAP_TOLERANCE_SAMPLES=30`,
`LOOKBACK_MULTIPLE=2`, the `consumptionStepMillis` median, the `age < intervalMillis` thinning — and
replayed it against the live `/api/history` store at the configured cadences (trend 5 s / 193 samples,
reversion 10 s / 241). Across 18 name-sensor pairs: **16 end on read-window exhaustion, 2 are satisfied,
and the `GAP_TOLERANCE` break fires exactly zero times.** No outage, no restart hole, no feed-mode
boundary. The walk just runs out of window.

**The cause is a unit mismatch of exactly one factor.** The window is `LOOKBACK_MULTIPLE × samples × step`,
denominated in `step` — the name's *median* print gap — on the implicit assumption that the walk accepts
one point per `step`. It does not: the `age < intervalMillis` thinning merges every run of short gaps, so
the walk consumes history at a wider **effective spacing**. Measured as `seed span ÷ (n − 1)`, that ratio
is **2.26×** `step` on average (range 1.92–2.81) against a `LOOKBACK_MULTIPLE` of **2**. The seed fills
**iff eff/step ≤ 2** — and the only two names that filled, BAC and NEE, are the only two measuring exactly
**2.00**. That is as clean a confirmation as this system offers.

Stack that on Rule 309 — `warmupSamples()` is already the *exact* minimum, so NEE at 192 of 193 is as blind
as CAT at 130 — and the desk is running two zero-margin conditions in series and losing on ~13 of 14 names
every boot. Three boots, three liquidation waves, ~40 s after each start.

## What happens next cycle

The one change targets must-fix **#1**, and it is now specified rather than guessed: when the backward walk
exhausts its read window still short of `samples`, **re-read further back and continue** until the seed is
full or a genuine gap truncates it — sizing the lookback by the spacing the walk actually consumes at
instead of by the median print gap. Self-calibrating, no fitted constant. Plus margin above the bare
`warmupSamples()` ask, and a log line naming the terminator and the span covered so the next cycle grades
it from the app's own output rather than from a replication script. It changes ADR-0071/ADR-0114 semantics,
so it ships with its ADR (`Status: Implemented`). It is explicitly **not** ADR-0135's "hold instead of
liquidate", which is graded ❌ BAD and will not be re-attempted.

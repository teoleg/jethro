HOLD at 4/6 cycles, no change — and this cycle disproved my own last headline: the desk did NOT flatten at this teardown, and I found why three cycles of diagnosis kept stalling — every order that actually trades records no reason for trading.

*(Every figure below is read from `/api/risk`, `/api/ops/jvm`, `/api/attribution`, `/api/risk/breaker`,
`/api/signals/telemetry`, `logs/report.md`, the scorer's snapshot, or a SQL aggregate over the `fills`
and `orders` tables. None is authored here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** Total PnL **$164.68**, **+103.62** since last run, **-95.01** over the last 3. The
   `2026-07-31T18:08:44Z` heartbeat has `pnl_growth_pct` **-72.0** against `pnl_target_pct` **1.0**,
   `on_track` **false**, `stale` **true**, `underwater` **false**. Up run-over-run, still off target.
   The decomposition matters more than the headline: `/api/attribution` `firmTotal` **164.74096826**
   is HEDGE **160.19086234** plus ALPHA **40.37358247** plus MACRO **-35.82347655**, on `totalFees`
   **268.179380** of which ALPHA alone paid **261.858413**. The strategy books are not covering their
   own fees; the hedge is carrying the firm total.
2. **Risk.** Gross **$33,927.71** = **2.3%** of the $1,500,000 firm cap (headroom **$1,466,072**); net
   **$12,537.56** = **1.3%** of the $1,000,000 net cap. `Flags: none`, `/api/risk/breaker`
   `halted: false`. Not a danger state — an under-deployment state.
3. **Cause.** The pending change (`4f67f0515`, the manual completion of the failed auto-revert) is
   **✅ VERIFIED deployed and landed**: `uptimeSeconds` **1279** against `asOfMillis`
   **1785522627137** puts boot at **2026-07-31T18:09:08Z**, after the revert commit; ADR-0133 reads
   `**Status:** Reverted` and a grep for `0133` across the Java sources returns nothing. Its **effect**
   is the scorer's to judge at 6/6, not mine.
4. **Danger.** None. Not bleeding, not near a cap, breaker clear.

## What I found, and what I retract

Last cycle I wrote that the desk "liquidates the entire book to exactly flat at every loop teardown."
**That does not hold.** Summing every LIVE ALPHA fill executed before this boot, six names carry across
it — BAC **68.000000**, GOOG **1.000000**, MCD **-43.000000**, MSFT **37.000000**, NVDA **-30.000000**,
PFE **-221.000000**. And the giant heartbeat-minute bursts are outliers rather than a cadence: `16:05`
(**$63,599.14**), `17:12` (**$81,499.01**) and `17:38` (**$99,922.58**) are outsized, while the other
teardowns are ordinary — `16:37` **$28,990.48**, `18:06` **$20,729.30**, `18:09` **$17,188.13**. The
flatten is **episodic**, and I generalised it from two consecutive cycles. What survives is the narrower
claim that never needed "every": all sources publish `horizonSeconds` **3600** against a ~30-minute
process recycle, so the holding period is structurally shorter than the horizon the expectancy is
measured over.

I could not go further, and the reason is now the top of the register. The procedure requires attributing
each move to the **trigger** that opened it. Of the **523** FILLED orders since 12:00Z, **0** carry a
`reason`. All **249** populated reasons belong to CANCELLED orders, and every one is the same string
(`fusion re-plan — passive order superseded by a fresh target (ADR-0084)`). So the column this loop is
told to post-mortem is populated only for orders that never traded. That is why three consecutive cycles
proposed a mechanism and then falsified it — including mine. It is also why I am recording this window's
**+103.62** as **unattributed** rather than crediting it: with no trigger on any fill, market and change
cannot be separated from the numbers, and guessing would be the exact error I just corrected.

On the standing priority, nothing changed: at the 3600s horizon reversion measures **4.870510576792947**
bps over **63** cohorts and social **5.656591478039339** over **22**, both small against their own cohort
dispersion (**32.18731967290294** and **25.926539977790824**), while trend
(**-1.9332555813332086**), momentum (**-1.053279287301587**) and xsreversion
(**-6.334834134258276**) are negative. The edge gate still lets none of them size, correctly.

## Decision

No change — `scripts/score-change.py score` prints `still accumulating evidence (4/6 cycles)` and
`reports/.pending-baseline.json` is present, so a new change now would destroy the revert's evidence.
Next cycle's one change targets register **#1**: give FILLED orders the trigger that produced them. I am
stating the trade-off up front rather than discovering it in the ledger — that fix moves no money and
will most likely score ⚠️ INCONCLUSIVE. It is still the right next change, because every money change
after it depends on being able to tell which trigger opened the loser.

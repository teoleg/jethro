# Every full exit fires because corroboration went AWAY — so an uncorroborated view now HOLDS, not liquidates (ADR-0144)

## Situation — the four questions, in live numbers

1. **Money.** Total PnL **-$897.87616775**. Since last run **+0.00**; over the last three runs **+0.00** —
   the US session was closed for all of them (`chore(status)` reads `market-closed` from 08:30Z through
   13:00Z) and it reopened as this report was cut. The daily curve is the real move: **-628.07** (08-05) →
   **-897.88** (08-06) → **-897.88** (08-07, unchanged). Yesterday bled; today has not started.
2. **Risk.** Gross **$0.00** — **0.0%** of the firm gross cap $1,500,000, headroom **$1,500,000**; net
   **$0.00** against the $1,000,000 net cap. `breaker.halted` **false**. **DORMANT**, which the mission
   names a failure to attack, not a rest state.
3. **Cause.** Last cycle's change `27564bb15` (ADR-0143) was scored **❌ BAD** and auto-reverted by
   `ac15c43`. Two things are true at once and both matter: the vector it was graded on
   (PnL **-838.66 → -897.88**, gross **15,986.26 → 0.00**) moved across a window in which
   **`scripts/` cannot reach the running app** — it holds the loop's own Python tooling, never loaded by
   the JVM — so the verdict is not attribution, it is coincidence with a session close; and ADR-0143's
   own **VERIFY-BY passed in the same breath that removed it**, the ledger note reading
   `reverted (code reverted in 2 path(s); ADR + ledger + findings kept)` where all nine prior BAD rows read
   `⚠️ REVERT FAILED`. The self-correction arm works now. I am not re-attempting the reverted idea.
4. **Danger.** No. Nothing is near a cap, the breaker is clear, and gross is zero. The live state is the
   inverse — an idle book, which is the opportunity.

**Restart, stated plainly:** `ops_jvm.uptimeSeconds` **12388** against `traffic.timestampMillis`
**1786109402412** puts boot at **2026-08-07T10:03:34.412Z**, mid-closure. Every equity sensor is
consequently cold — the log carries `seeding 1 of 193` / `1 of 241` on KO, GOOG, UNH, CAT, PG, PYPL, CVX,
MCD, JNJ, PFE, all terminating `HISTORY_EXHAUSTED`/`NO_HISTORY` — because a frozen overnight tape stores
no recent prices to seed from. `fusion_targets.instruments` is **2**: NQ and EURUSD, the two names that
print overnight.

## Order-level post-mortem — the close liquidated the whole book for a non-reason

The window contains no orders (closed tape), so the post-mortem is the last seven minutes of the previous
session, and it is unambiguous. At **20:10:31Z** BAC, PFE and HD all flattened on
`fusion exit — target decayed to flat [forecast=-0.0, sources=1]`; at **20:16:06Z** and **20:17:37Z** MSFT,
NVDA and AMZN followed at `sources=1`, `sources=1`, `sources=0`. Six full exits, every one at
`sources ≤ 1`, none at a reversed forecast. That is the census's mechanism firing on the entire book at
once: as the tape stopped printing, sources dropped out, and the desk paid the **2.00 bps** equity round
trip to liquidate positions its own signals had not turned against. `totalFees` **435.119863** is
**48.46%** of `|firmTotal|`.

**Change vs market:** nothing to split. The market was shut for the whole span, PnL moved **+0.00**, and
no committed path reached the app before the 10:03:34Z restart. My changes earn neither credit nor blame.

## The mechanism, and the one change

ADR-0124 zeroes the agreement scalar at one *effective* source because the dispersion is unestimable — a
correct answer to "may risk go ON this name". But that scalar multiplies the combined forecast, and the
combined forecast is **also the exit trigger**: `TargetPlanner` turns 0 into a flat target and ADR-0090
works a flat target in full, this cycle. So "no corroboration" is executed as "the view is zero, sell
everything". This run's `fusion_targets` shows it frozen in place — EURUSD carries one contribution,
`trend forecast=20.0` at the cap, and a `combinedForecast` of **0.0**.

I first checked the register's prerequisite (Rule 440): **ADR-0140's aging is not reached here.** It ages
names *absent from the target list*; an uncorroborated name is *present*, with an affirmative flat target,
so it never enters the absence clock. Unreached rule, not wrong horizon.

**ADR-0144:** a flat target on a **held** name with **≤ 1 source** that the ADR-0086 cut did **not**
flatten routes nothing and is left out of the aim snapshot, so ADR-0140 ages its intent over one evidence
horizon exactly as any other absence. `sources ≤ 1` is the precise characterisation of the unestimable
case and needs no new state. Entry is untouched (the census's `0 of 25` entries at one source stays zero),
a corroborated flat view still exits in full, a stop cut still outranks it, and the branch can only ever
*remove* an order — one-way, like ADR-0076/0098/0124 before it. Five new `PositionBufferTest` cases;
`-Pci test` green (625 app tests, 0 failures).

**VERIFY-BY next run:** the trigger × source-count census over the window — the share of
`fusion exit — target decayed to flat` fills carrying `sources=1` must fall **below 1.0** — paired with the
stationary holding-period estimator rising **without** gross falling.

## Also found, recorded not acted on

`/api/market/regime` reads `volRatio` **277215656.99**, `regime` **ELEVATED**. The mechanism is certain:
`VolatilityRegime` skips no frozen mark, so an overnight tape of identical prices gives `relVol = 0` for
every name, the EWMA baseline (λ=0.97) decays toward zero across thousands of cycles, and the first real
reading at the open divides by it. Worse, it **latches** — `baseline = ewma.min(baseline)` while ELEVATED
means the baseline can never climb back, so the process stays risk-off for its whole life. The identical
lesson is already coded one file away (`FusionLifecycle.applyRiskCut`, ADR-0116: "absorbing those as zero
returns decays σ toward zero"). It is **not** this cycle's change because it costs no money today —
`fusion_targets.routing` is **true**, so `StrategyLifecycle.autoExecuting()` is false and the regime scale
gates nothing that trades; it reaches only the landing-page badge and the sim backtest. Filed as must-fix
**#3** with its own VERIFY-BY.

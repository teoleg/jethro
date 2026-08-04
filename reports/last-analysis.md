No change — held at 4/6 cycles; a second restart defect is now measured: the desk's INTENT is in-memory only, so every reboot resets it and the no-trade buffer then forbids the first order in a name for 12 minutes at best and 35 at the median — which is why the book holds 6.63% of its own $494k plan.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, `recent_orders`, `turnover_cost_by_name`, the boot log, or computed from those by script. None
is authored here — invariant 7 / ADR-0016.)*

## Step 0 — verify last run's change first

**`120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric, fourth cycle running.** Live
`/api/fusion/targets` sums to **$494,262.182127365** of planned gross against the **$500,000**
`jethro.risk.max-gross-exposure` the guardrail permits — **0.98852436425473×**. The cap binds a fourth
consecutive cycle and no name flipped side. Its PnL verdict is the scorer's, not mine, and stands at
**4/6 cycles**.

**`reports/.pending-baseline.json` is present and the scorer holds `120b22b41` at 4/6.** Under ADR-0116
that forbids a second change on top of an open measurement window, so I made **no code edit** and recorded
**no baseline**. The cycle's work went into Step 0 and into `reports/must-fix.md`.

**Item #1: ⚠️ STILL-BROKEN — fourth independent boot, same 40-second signature.** Boot at **18:08:35.358Z**
(`traffic.timestampMillis` 1785868202358 − `ops_jvm.uptimeSeconds` 1287); **13** equities cold on trend and
**4** on reversion at 18:08:49–18:09:24Z; then at **18:09:15.234888Z** KO `BUY 12`
`[forecast=0.0, sources=0]` FILLED and **18:09:15.386281Z** NEE `SELL 2` `[sources=0]` REJECTED. Four boots
— 16:45:57Z, 17:07:55Z, 17:39:05Z, 18:08:35Z — four liquidation waves.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **-$606.83516377** (`/api/risk` `.total`, firm headline incl. hedge). Since last run
   **-$9.22**; over the last 3 runs **-$1.01**. `UNDERWATER`, and `pnl_growth_pct` **-12.9%** against the
   **+1.0%** target — off track. By book: ALPHA **-$578.82740076**, HEDGE **+$28.79174235**, MACRO
   **-$56.79950536**.
2. **Risk.** Gross **$50,120.42330000** = **3.3%** of the $1,500,000 firm cap, headroom **$1,449,874**; net
   **-$15,066.63670000** = **1.5%** of the $1,000,000 net cap. `riskCuts []`, `bookVolBrake 1.0`, breaker
   untripped. **Not** a danger state — the opposite, and that is the point below.
3. **Cause.** No change shipped this window (code frozen at 4/6), so the move is the running desk's own
   behaviour: liquidate on cold sensors at boot, then rebuild slowly.
4. **Danger.** No. Bleeding, but at 3.3% of the gross cap with the breaker untripped. The response is to
   fix the mechanism that prevents deployment, never to de-risk.
5. **Order-level post-mortem.** The window splits at the boot exactly as the last three did. The two
   `sources=0` orders at 18:09:15Z are liquidations triggered by blindness, not by a view. Every order from
   17:55Z through 18:07Z in the *previous* JVM carries `sources=2/3` and a forecast of ±5 to ±11 — real
   entries. Fees remain the majority of the loss: firm **-$606.83516377** against `totalFees`
   **$387.738249** ⇒ pre-fee trading of **-$219.09691477**, so **fees are 63.90%** of the deficit.
6. **Memory.** Rules 294–314 applied. Rule 303 in particular: the ledger's flagged auto-revert of
   `026cda49d` stays **deliberately not completed**, because `026cda49d` is itself the revert of the
   graded-BAD ADR-0136 and completing it would re-apply a rejected mechanism.
7. **Change vs. market.** Nothing this window is attributable to code — none shipped. The
   liquidate-then-rebuild pattern is **baseline behaviour of the running system**, now on its fourth
   reproduction.

## What this cycle found

**First, Rule 313's coin flip is confirmed from the app's own log rather than from my replication.** The
seed depth is a property of the **boot**, not of the name: **NEE seeded 193 of 193 at the 17:39:05Z boot and
181 of 193 at the 18:08:35Z boot**, while **BAC seeded full at both**. So no per-name allowlist or per-name
constant can fix item #1 — only sizing the read-back by what the walk actually consumes, which is the fix
already specified.

**Second, and new: there is a SECOND restart defect, additive to the first, and it is the one that keeps the
capital undeployed.** `PositionBuffer` holds the ADR-0080 aim — the desk's intended position — in a plain
`HashMap` field (`PositionBuffer.java:104`), constructed per-JVM in `FusionConfig.java:217`, with no store
and no restore; `nextAim` seeds `from = held` on first sight. Every restart therefore throws away the intent
the previous process built and restarts it at the just-liquidated holding. The buffer then blocks re-entry
for a long time, because the band is scaled by the **target**, not by the aim: from a zero aim the first
order in a name is released only once `1 − (1−a)^n ≥ 10·width/|forecast|`, with `TARGET_ABS = 10.0`,
`width = 0.10` (`edgeGate` is `null`, so the ADR-0101 measured width is inactive) and the derived rate
`a = 1 − exp(−30/3600) = 0.008298707`. Computed on this cycle's live target book that is **12.4 minutes** of
uptime for the fastest name (MSFT), **34.7 minutes** at the median, and **never** for 4 of the 20 sized names
(MCD, WMT, NEE, NQ, whose band already exceeds their target).

The loop reboots the JVM about every 30 minutes. That is why `insideBuffer` reads **17 of 22** and the desk
holds **$32,767.885** against its own **$494,262.182127365** plan — **6.63%** — while ADR-0132 asks it to
deploy. The independent corroboration is the previous JVM's timeline: booted **17:39:05Z**, first
`fusion entry — target increase` FILL at **17:55:28.697412Z**, ~16 minutes later, which tracks the table and
not any market event.

## What happens next cycle

The one change still targets must-fix **#1** — the warm-restart seed — because #1 gates #2: a cold sensor
plans the name flat, and a flat target snaps the aim to zero, so restoring intent while the sensors still
blink out at boot would restore nothing. The fix is specified: when the backward walk exhausts its read
window still short of `samples`, re-read further back and continue until the seed is full or a genuine gap
truncates it, sizing the lookback by the spacing the walk actually consumes rather than by the median print
gap; plus margin above the bare `warmupSamples()` ask and a log line naming the terminator. Item **#2** —
persisting the aim across the process boundary — follows once #1 is VERIFIED. Neither is ADR-0133's band cap
(`e61c7f5aa`, graded ❌ BAD) nor ADR-0135's hold-instead-of-liquidate, and neither will be re-attempted.

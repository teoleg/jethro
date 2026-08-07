# Last analysis — 2026-08-07 18:00Z

**No change (`3c43242ba` is at 3/6 cycles in its evaluation window) — but this cycle found the mechanism
behind the cost problem: the desk traded $60,988 of notional to move its position by $13,846, and on KO it
traded 184 shares for a net position change of exactly zero, because the fusion target tracks a forecast
that decays from -9.97 to -0.0002 in ninety seconds.**

## Situation (live, read from this run's report — never authored)

1. **Money.** Total PnL **$-1,032.14**. Down **$8.17** on the window, **$86.77** across the last three runs.
   Bleeding slowly, UNDERWATER, off the +1%/3-iteration target (`pnl_growth_pct` **-12.68%**,
   `on_track=False`, `stale=True`).
2. **Risk.** Gross **$13,141.18** = **0.9%** of the $1,500,000 firm cap, headroom **$1,486,859**; net
   **$-4,224.78** = **0.4%** of the $1,000,000 net cap. Gross **rose $1,713.05** on the window. No
   `NEAR FIRM CAP` flag, no breaker. Under ADR-0132 the barely-deployed book is the failure here, and gross
   rising with this much headroom is the right direction, not a danger.
3. **Cause.** No change was deployed this cycle or last. `3c43242ba` (the hand-completed revert of ADR-0144)
   is still under measurement — `scripts/score-change.py score` prints *"still accumulating evidence (3/6
   cycles)"* and `reports/.pending-baseline.json` is present — so the contract freezes new code.
4. **Danger.** None. Bleeding, yes; near the cap or the breaker, no. So the live danger state does not apply
   and de-risking would be exactly the wrong move.

## Step 0 — verification: `3c43242ba` ✅ VERIFIED, qualification closed

`ops_jvm.uptimeSeconds` is **5052** at a report stamp of **18:00:02Z**, so the JVM has run since **16:35:50Z**
— the *same* process as last cycle (3251s then), no restart in between. `fusion exit — target decayed to flat`
fired at **17:59:09** (`KO SELL 13`), **83 minutes** into that process. Last cycle's caveat — that every exit
row sat inside the boot transient — is now closed on a long warm window. The exit leg fires, repeatedly.

## The diagnosis this window bought (must-fix #1, now specified)

Fees are **$474.61** of the **-$1,032.14** firm total — **46%** of the entire cumulative loss — on **3,235
LIVE fills** and **$5,586,655** of turnover carrying a **$13,141.18** book. That was already known. What was
missing was *why*, and `recent_orders` answers it in the `forecast=` field:

- **AAPL**: entered short 34 at `fc=-9.97` (17:37:51); bought back 16 at `fc=-0.119` (17:39:23) and 8 more at
  `fc=-0.00018` (17:41:55). The forecast collapsed to zero in **92 seconds** and the target followed it 1:1.
- **KO**: `BUY 92 at fc=+5.52` (17:41:55) → `SELL 48 at +2.19` → `SELL 13 at -3.69` → `SELL 18 at -0.62` →
  `SELL 13 at -0.0`. A complete round trip in 18 minutes, **184 shares traded for zero net position**.
- Across the 32 ALPHA fills in the window: **$60,988 gross traded / $13,846 net moved = 4.4× churn**
  (NVDA 7.8×, AAPL 5.4×, KO infinite).

`fusion_targets` shows the volatility at source: KO's `combinedForecast` of **-3.06** is built from a
`reversion` contribution of **-19.90** against `trend` **+1.26**. The reversion source swings an order of
magnitude wider than the combined signal and nothing damps it between forecast and order. Set against
`signal_observations` hit rates of **0.501 / 0.498 / 0.489** on samples of 14,105 / 13,196 / 14,372, the desk
is paying roughly 2 bps a round trip to re-express a coin flip every ninety seconds. **That is a better
explanation of the INCONCLUSIVE wall than any combiner hypothesis** — a random walk minus fees drifts down at
the fee rate, and re-weighting sources that don't predict cannot outrun a deterministic cost.

## Change vs. market — attributed honestly

**The window's -$8.17 and +$1,713.05 gross are credited to nothing I did.** No logic was deployed this cycle
or last; the running commit only removed code. The PnL move is market on positions I did not choose, and the
gross rise is the fusion book continuing to re-deploy in a process that has now been warm for 83 minutes —
the same clock effect Rules 433/459 already record. I claim no credit and accept no blame for either.

## Next cycle

Once `3c43242ba` is scored, the one change targets must-fix #1 with a **damper, not a size cut**: hysteresis
on the target proportional to the target's own rolling σ (and/or a minimum holding period), so a target must
move by more than its own noise before it routes. ADR-0132 forbids buying quiet by holding nothing, so the
VERIFY-BY is the **churn ratio** (gross traded ÷ net moved, from **4.4×**) guarded by gross exposure not
falling below **$13,141.18** and `firmTotal` not deteriorating.

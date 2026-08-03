No change — last cycle's manual revert is at 1/6 of its measurement window, and it is ✅ VERIFIED on every criterion it was shipped against.

*(Every figure below is read from `/api/risk`, `/api/signals/telemetry`, `/api/fusion/targets`,
`recent_orders`, `turnover_cost_by_name`, the scorer's output, or `git`/`grep`. None is authored here —
invariant 7 / ADR-0016.)*

## Situation — the four questions

1. **Money.** Total PnL **`$-68.53`**, **`-9.93`** since the last run and **`-29.61`** across the last three.
   `UNDERWATER` and off the growth target (`pnl_growth_pct -47.48` vs `pnl_target_pct 1.0`, `on_track=false`,
   `stale=true`). The book is not collapsing; it is grinding down in small increments.
2. **Risk.** Gross **`$49,046.31`** — **3.3%** of the firm cap `$1,500,000`, headroom **`$1,450,954`**; net
   **`$17,174.47`**, **1.7%** of the `$1,000,000` net cap. Gross rose **`+1,018.67`** this run. Nowhere near a
   cap, breaker not tripped, not DORMANT. **Not a danger state** — exposure rising with this much headroom is
   the desired direction, not something to de-risk.
3. **Cause.** The change under measurement is `c20fb0b70`, last cycle's manual completion of the ADR-0135
   revert. The scorer reports `still accumulating evidence (1/6 cycles)` and
   `reports/.pending-baseline.json` is present, so it is **held, not scored**.
4. **Danger.** None on the risk axis. No cap proximity, no breaker, and this time no process danger either —
   the rejected code is out.

## Step 0 — `c20fb0b70` (the ADR-0135 revert): ✅ VERIFIED, all four criteria

Graded on the **code**, not on ancestry, per the rule the last block wrote down:

- `grep -rn "estimable" app/src/main/java/io/jethro/app/fusion/` returns **0** lines. The only surviving
  occurrence repo-wide is a prose comment in a test.
- `/api/fusion/targets` rows carry no `estimable` field — **0** matches for the string in the whole report.
- The ADR index shows `0135 … Reverted`, with the annotated record kept.
- **Behavioural, the one that actually matters:** the pre-ADR-0135 branch is live again.
  `fusion exit — target decayed to flat [… sources=1]` fires on `PFE SELL 26` (16:40:13Z), `CAT SELL 1` and
  `JNJ SELL 63` (16:38:42Z), `CVX BUY 1` (16:43:46Z). Under ADR-0135 those were suppressed; they are back, so
  the revert reached the running desk rather than just the source tree.

Nothing is claimed for it on PnL. Restoring previously-running code should restore prior behaviour, not
create edge; the window's `-9.93` is mark-to-market plus cost on positions this change did not select. What
it buys is that the next measurement is attributable at all.

## What the telemetry says while I wait — the horizon/cost mismatch, now precisely quantified

The register's item #1 is confirmed by the cleanest read yet of `/api/signals/telemetry`. Measured expectancy
by source and horizon, with the t-statistic computed from the reported `stdReturnBps` and resolved count:

- **225s** — `reversion +0.042`, `trend +0.037`, `social +0.402`, `momentum -0.707`, `xsreversion -0.107`.
  Every source inside ±0.71 bps, every `|t| < 0.71`. Nothing to trade.
- **900s** — best is `reversion +0.774` (`t=+0.91`). Still nothing.
- **3600s** — `social +5.917` (`t=+1.34`) and `reversion +3.079` (`t=+1.07`) are the largest expectancies in
  the whole table.

And the fusion weights are already aligned with that: `social 1.680` and `reversion 1.572` are the two
heaviest. **The desk weights the sources that pay at 3600s, then re-plans every 30s and turns the position
over long before 3600s arrives.** `turnover_cost_by_name` shows `fee_bps 1.00` per side on every equity, so a
round trip spends ~2 bps of fee to chase a 225s expectancy of ~0.04 bps. `BAC` this window: four re-plans in
90 seconds, three of them `CANCELLED … superseded by a fresh target (ADR-0084)`. `JNJ`: five cancelled
entries 16:25–16:27Z, then `BUY 51` filled, then `SELL 6/7/9/2` "reduce toward a smaller target" by 16:31Z,
then `SELL 63` liquidated at 16:38Z. That is the mechanism, not a metaphor for it.

One genuinely new datum worth recording: `xsreversion` at 3600s is `-7.765` bps at **`t=-2.19`** on 512
resolved — the **only** statistically significant number in the table, and it is negative. Its fusion weight
is already at the floor `0.25`, so the telemetry weighting is doing its job; but a source with significantly
*negative* measured expectancy still sizing at floor weight in the correct-sign direction is a separate
defect from the horizon mismatch, and it is ranked as such rather than folded into it.

## Why no change

The contract is explicit: a pending change under measurement must not have a new change stacked on it. At
1/6 cycles, shipping the horizon fix now would make both unattributable — which is exactly how the last two
BAD verdicts got muddled. The horizon/cost fix is designed and ranked #1; it ships the cycle after
`c20fb0b70` scores.

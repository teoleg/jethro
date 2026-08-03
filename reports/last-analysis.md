No change — `c20fb0b70` is at 2 of 6 measurement cycles; meanwhile the telemetry's clustered dispersion field shows NO source is significant at ANY horizon, which retires last cycle's "one significant number" reading.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`,
`/api/fusion/targets`, `recent_orders`, `turnover_cost_by_name`, or the scorer's own output. None is
authored here — invariant 7 / ADR-0016.)*

## Situation — the four questions

1. **Money.** Total PnL **`$-205.30`**, **`-130.88`** since the last run and **`-161.94`** across the last
   three. `UNDERWATER`, off the growth target. This is the largest single-window drop in the recent record —
   the grind became a step down, and §5 below attributes it.
2. **Risk.** Gross **`$57,438.25`** — **3.8%** of the firm cap `$1,500,000`, headroom **`$1,442,562`**; net
   **`$14,847.85`**, **1.5%** of the `$1,000,000` net cap. Gross rose **`+6,033.80`**. The only flag raised is
   `UNDERWATER`; no cap proximity, no drawdown breaker, not `DORMANT`. Exposure rising with this much
   headroom is the intended direction and is **not** a reason to de-risk.
3. **Cause.** The change under measurement is `c20fb0b70` (the manual completion of the ADR-0135 revert).
   `reports/.pending-baseline.json` is still present and **no new ledger row has appeared**, so it is held,
   not scored — 2 heartbeats have accrued since its baseline at `16:37:25Z` against `MIN_CYCLES 6`.
4. **Danger.** No. `DANGER` requires bleeding *near* the cap or the breaker; the book is bleeding at **3.8%**
   of gross cap. The correct response is to fix the mechanism that loses money, not to cut exposure.

## Step 0 — `c20fb0b70`: ⚠️ still under measurement, and its restored branch is confirmed live a second time

The revert's behavioural criterion holds on a fresh window: `fusion exit — target decayed to flat [… sources=1]`
fired on `MCD SELL 35` (17:08:59Z) and `BAC SELL 240` (17:10:00Z). That is the branch ADR-0135 suppressed,
running again — the deployment check, not just a source-tree grep (Rule 256).

**And it is not PnL-neutral, which I record rather than gloss.** `MCD` now shows `realizedPnl -27.35` on a
flat position — that loss was crystallised by the restored exit. `BAC` was accumulated 16:43–16:50Z and
liquidated by the same branch at 17:10:00Z. I am *not* calling that a regression: reverting to
previously-running code restores prior behaviour by construction, and whether holding those two names would
have done better is unknowable from here. The scorer settles it in four more cycles. What I will not do is
claim the revert was costless when the register can see it selecting trades.

## The measurement that changes my mind — clustered dispersion retires Rule 258

`/api/signals/telemetry` reports `cohorts` and `stdCohortMeanBps` alongside `resolved` and `stdReturnBps`.
Prior cycles built t-statistics from `stdReturnBps / √resolved` and concluded `xsreversion` at 3600s was
`t = -2.19`, "the only significant number in the table". **That was overstated.** Signal observations across
names and overlapping windows are not independent draws; the cohort dispersion is the honest denominator,
and there are `33` cohorts behind those 517 `xsreversion` observations, not 517. Recomputed on
`stdCohortMeanBps / √cohorts`, `xsreversion`'s 3600s reading falls well inside the noise band — as does
every other cell at every horizon.

The raw table, as reported:

| horizon | source | resolved | avgReturnBps | cohorts | stdCohortMeanBps |
| --- | --- | --- | --- | --- | --- |
| 225s | reversion | 5198 | `+0.041` | 500 | `6.72` |
| 225s | trend | 5443 | `+0.056` | 500 | `6.84` |
| 225s | social | 666 | `+0.303` | 65 | `6.33` |
| 3600s | social | 309 | `+5.690` | 28 | `28.27` |
| 3600s | reversion | 517 | `+3.146` | 71 | `30.92` |
| 3600s | xsreversion | 517 | `-8.288` | 33 | `30.03` |

**So the honest statement is: no source in this universe has demonstrated significant edge at any measured
horizon.** That is the standing-priority answer the prompt asks for, and it is not a reason to stop — it is
the reason item #1 is a *cost* fix, not a signal fix. What survives is the ordering: the largest expectancies
sit at **3600s**, the 225s column is inside `±0.31` bps for every source that matters, and one side of an
equity round trip costs `fee_bps 1.00` (`turnover_cost_by_name`, every equity; `0.20` on `ES`/`NQ`).

## Order-level post-mortem — the re-plan ladder buys the MOST size at the WEAKEST forecast

This is new, and it sharpens item #1 from "turnover costs money" to a directional defect. `JPM`, this window:

| time | forecast | qty | status |
| --- | --- | --- | --- |
| 17:13:02Z | `14.48` | 4 | CANCELLED — superseded |
| 17:13:32Z | `12.23` | 10 | CANCELLED — superseded |
| 17:14:03Z | `9.64` | 13 | CANCELLED — superseded |
| 17:14:33Z | `9.40` | 15 | CANCELLED — superseded |
| 17:15:04Z | `9.21` | 18 | CANCELLED — superseded |
| 17:15:34Z | `5.90` | 21 | **FILLED** |
| 17:17:35Z | `5.05` | 10 | **FILLED** |

The forecast decays monotonically while the ordered size *grows*, and the two orders that actually fill are
the two weakest views on the ladder. The passive orders resting on the strong forecasts get superseded by
the next 30s re-plan before they fill; only the late, decayed ones survive to execution. That is adverse
selection **manufactured by the re-plan cadence itself** — the desk systematically fills its worst signal.
`JPM` now carries `realizedPnl -54.74` and `totalPnl -79.46` on the 46 shares it assembled this way.
Across the window, **22 of 60** `recent_orders` rows are `fusion re-plan — passive order superseded by a
fresh target (ADR-0084)`.

Related, and worth flagging before anyone reads `targetQty` as intent: `/api/fusion/targets` shows `JPM`
`targetQty 357.679` against `currentQty 46.0` with **`deltaQty 0.0`**; `CAT` `targetQty -94.603` against
`currentQty 0` with `deltaQty 0.0`. The stated target and what the planner actually orders differ by an
order of magnitude.

## Change vs market — attribution, honestly

The position table is **cumulative**, not per-window, so I will not pretend to decompose the `-130.88` into
exact per-name contributions. What can be said:

- The largest single loss, `NVDA` `totalPnl -123.36` (`unrealizedPnl -141.35` on a short of 44), sits on a
  position that appears **nowhere** in the window's orders. That is mark-to-market on an untouched short —
  **market, not change.**
- `JPM`'s `realizedPnl -54.74` is on 46 shares the desk assembled itself this window, via the ladder above —
  **the desk's own trading, not the market.**
- `MCD`'s `realizedPnl -27.35` was crystallised by the branch `c20fb0b70` restored — **the change under
  measurement.**
- Book split: `ALPHA -280.72` (`feesPaid 314.30`), `HEDGE +132.22`, `MACRO -56.80`. The hedge is the only
  book making money. `totalFees 324.11` firm-wide against a firm total of `-205.30` — **the desk's gross
  trading result before fees is positive; fees alone put it underwater.** That is the whole thesis of item #1
  in one line.

## Why no change

The contract is unambiguous: a pending change under measurement must not have a new one stacked on it, and
`c20fb0b70` is at 2/6 with its baseline file present. Shipping the horizon/cadence fix now would make both
unattributable — the exact failure that muddied the last two BAD verdicts. Item #1 is designed, and this
window supplied the missing piece (the ladder's adverse selection); it ships the cycle after `c20fb0b70`
scores.

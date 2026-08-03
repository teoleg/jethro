No change — `c20fb0b70` is at 5 of 6 measurement cycles; and this window finally shows item #1 executing in full: three names were opened and completely flattened inside the same window, on a breadth collapse rather than a change of view.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`, the `tca`
section, `recent_orders`, `turnover_cost_by_name`, or the scorer's own output. None is authored here —
invariant 7 / ADR-0016.)*

## Situation — the four questions

1. **Money.** Total PnL **`$-285.01`**, **`-44.02`** since the last run, **`-79.52`** across the last three.
   `UNDERWATER` and off the +1%/3-iteration target. The bleed is a grind, not a step.
2. **Risk.** Gross **`$25,804.56`** — **1.7%** of the firm cap `$1,500,000`, headroom **`$1,474,195`**; net
   **`-$5,053.66`**, **0.5%** of the `$1,000,000` net cap. Gross **fell `-39,623.47`** this window. The only
   flag is `UNDERWATER`: no cap proximity, no breaker. A book this far under its budget shrinking by
   two-thirds is the *opportunity* signal, not a safety one.
3. **Cause.** The change under measurement is `c20fb0b70` (the manual completion of the ADR-0135 revert).
   `scripts/score-change.py score` → `still accumulating evidence (5/6 cycles) — held, not scored this run`,
   and `reports/.pending-baseline.json` is still present against its `16:37:25Z` baseline. Held, per
   contract — one more cycle and it scores.
4. **Danger.** No. `DANGER` is bleeding *near* the cap or the breaker; this is bleeding at **1.7%** of gross
   cap. The answer is to fix what loses money, not to cut exposure that is already barely deployed.

## Step 0 — `c20fb0b70`: ⚠️ UNDER MEASUREMENT, and its restored branch did something large this window

Deployment confirmed on behaviour for a fourth window (Rule 256 — behaviour, not a source grep). The
restored `fusion exit — target decayed to flat` branch fired three times:

| time | name | order | breadth |
| --- | --- | --- | --- |
| 18:05:27Z | `JNJ` | `BUY 26` | `sources=1` |
| 18:05:28Z | `BAC` | `BUY 435` | `sources=0` |
| 18:15:35Z | `KO` | `BUY 175` | `sources=1` |

**Those three flattens are the `-39,623.47` gross drop, and each closes a position this same window opened.**

## The finding — a complete round trip in 12 to 30 minutes, on a breadth reading, not a view reversal

Cross the flattens against the entry fills in `recent_orders` and the round trips are exact:

| name | opened (fill, forecast) | flattened | held |
| --- | --- | --- | --- |
| `BAC` | `SELL 159` 17:43:48Z `-10.14`, `SELL 146` 17:46:20Z `-10.09`, `SELL 130` 17:46:51Z `-11.20` | `BUY 435` 18:05:28Z | **~19 min** |
| `KO` | `SELL 97` 17:44:18Z `-12.83`, `SELL 76` 17:45:19Z `-15.03` | `BUY 175` 18:15:35Z | **~30 min** |
| `JNJ` | `SELL 20` 17:53:25Z `-6.18`, `SELL 3` 17:54:26Z `-6.54`, `SELL 3` 17:54:56Z `-5.54` | `BUY 26` 18:05:27Z | **~12 min** |

The exits carry `forecast=0.0`/`-0.0` — the desk did not change its mind about direction. It stopped being
able to *count* enough sources, and the breadth collapse alone paid a full round trip. `BAC` was entered on
three separate strengthening fills at `-10` to `-11.2` and bought back entire, 19 minutes later.

**A note on units, because I nearly got this wrong.** `Forecast.java` is a Carver-scaled forecast
(`TARGET_ABS = 10.0`, `CAP = 20.0`) — a `-15` on `KO` is a 1.5×-average-strength *view*, **not** 15 bps of
expected return. So no calibration claim can be read off those numbers; only telemetry measures return.

## The cost, at the horizon that matches the holding period

Per side, read: `fee_bps 1.00` on every equity (`turnover_cost_by_name`), plus `tca` `avgSlippageBps`
`BAC 0.4878`, `KO 0.4638`, `JNJ 0.4307`. A round trip pays both, twice.

`/api/signals/telemetry` at **900s** — the horizon that actually brackets a 12–30 minute hold — with the
clustered denominator (Rule 258):

| source | resolved | avgReturnBps | cohorts | stdCohortMeanBps |
| --- | --- | --- | --- | --- |
| trend | 1995 | `+0.371` | 275 | `13.699` |
| reversion | 1834 | `+0.698` | 237 | `13.870` |
| social | 619 | `+0.849` | 61 | `15.773` |
| momentum | 113 | `+2.140` | 23 | `14.144` |
| xsreversion | 1902 | `-1.261` | 126 | `12.653` |

Every cell sits far inside its own cohort dispersion — **no source is significant at 900s, and none is at
225s or 3600s either.** The two sources behind these entries (`sources=2`–`3`, trend/reversion) measure
`+0.37` and `+0.70` bps — **below the cost of a single side**, let alone the round trip the breadth
collapse forces. That is item #1, no longer as an argument but as three executed examples.

Book split says the same thing: `ALPHA -350.28` on `feesPaid 330.60`, `HEDGE +122.06` on `9.66`,
`totalFees 341.37` against `firmTotal -285.01`. Fees remain the whole of the deficit.

## What I got wrong last cycle, and the methodology fix

Last cycle I set item #1's VERIFY-BY at "supersession cancels below `27/59`, zero-fill names below `4`,
gross not falling". **With no change shipped, all three moved:** cancels `26/60`, zero-fill names **2**
(`HD`, `PG`), and gross fell `-39,623.47`. A threshold that swings that far on its own cannot grade a
change — it would have scored a no-op as a partial success. Replaced with a direct count of the defect:
**same-window round trips** (names both opened and fully flattened inside one window) — **3** this window
(`BAC`, `KO`, `JNJ`), on `435`/`175`/`26` shares.

## Decision

No code change — `c20fb0b70` must finish measuring, and stacking on it is exactly what muddied the last two
BAD verdicts. Next cycle, once it scores, the one change targets the **entry** side: require the source
breadth that justifies an entry to persist before size is committed, so a reading about to collapse never
opens a round trip. Deliberately **not** the exit branch — that is ADR-0135's mechanism, already graded ❌
BAD and reverted, and a reverted idea is never re-attempted.

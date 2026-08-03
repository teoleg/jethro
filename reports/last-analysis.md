ADR-0136 is deployed and its mechanism is ✅ VERIFIED — sub-floor partial reduces went from 13 to 0 while the exit still routes — so it is held under measurement with no new change, and I logged that eight of the report's own JSON sections are silently truncated into invalid JSON, meaning this loop has been diagnosing on half the cross-section.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`, `/api/fusion/targets`, the `tca` section, `recent_orders`, `turnover_cost_by_name`, or the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

## Situation — the four questions

1. **Money.** Total PnL **`$-271.70`**, **`+4.93`** since the last run, **`-30.71`** across the last
   three. `UNDERWATER`, and `pnl_growth_pct` **`-45.7%`** against the **`+1.0%`** target — `on_track=false`,
   `stale=true`. The one-run move is up; the three-run trend is still down.
2. **Risk.** Gross **`$72,532.50`** — **4.8%** of the firm cap `$1,500,000`, headroom **`$1,427,467`**;
   net **`-$8,969.11`**, **0.9%** of the `$1,000,000` net cap. Gross **rose `+18,438.03`**. The only flag
   is `UNDERWATER`: no cap proximity, no drawdown breaker. A book at 4.8% of its budget rebuilding is the
   direction ADR-0132 asks for, not a risk event.
3. **Cause.** `e3b33679d` (ADR-0136) is **still under measurement** — `reports/.pending-baseline.json`
   exists for it and no new ledger row has appeared, so the scorer has not judged it. Per the contract
   that forbids a new change this cycle. Its *mechanism* is verified below; its *PnL verdict* is not mine
   to guess.
4. **Danger.** No. Bleeding-near-the-cap is the danger state and we are nowhere near it — 4.8% gross,
   0.9% net, breaker clear. Gross rising with this much room is not a reason to de-risk.

## Step 0 — ADR-0136 (`e3b33679d`): ✅ VERIFIED on mechanism

**It deployed.** Commit `e3b33679d` at `2026-08-03T19:15:32Z`; the JVM reports `uptimeSeconds` **`813`**
at `timestampMillis` `1785785403524` → boot **`19:16:30Z`**, after the commit. I am not grading code the
app never ran.

**The commit named its own falsification test, and both halves pass.** Splitting the window's 60 orders
at the boot time:

| | pre-boot (old code, 44 orders) | post-boot (ADR-0136 live, 16 orders) |
| --- | --- | --- |
| `fusion reduce toward a smaller target` at \|forecast\| < floor `5.0` | **13** | **0** |
| `fusion exit — target decayed to flat` at sub-floor forecast | 2 | **1** (`KO SELL 51`, `forecast=-0.0`) |
| `fusion entry — target increase` below the floor | 0 | 0 |

The 13 suppressed were `BAC`×9 (forecasts `0.347`, `0.536`, `0.702`, `1.720`, `1.827`, `2.836`, `4.126`,
`4.493`, `4.437`), `GOOG`×2 (`-1.548`, `-3.930`), `NVDA`×2 (`-3.415`, `-4.886`). Post-boot every routed
entry cleared the floor and the sub-floor **exit still routed** — the trapped-position failure this
change risked did not occur.

**And it is suppression, not absence of opportunity.** The `fusion_targets` snapshot at
`atMillis 1785785388985` (`19:29:48Z`, well after boot) still plans non-zero deltas on three sub-floor
names — `NEE` (`combinedForecast -3.775`, `currentQty 107`, `deltaQty -0.888`), `JNJ` (`-2.495`, `-18`,
`-0.175`), `NVDA` (`2.053`, `-8`, `+0.066`). The floor gate sits at route time, downstream of that field,
so those planned deltas are exactly the dribble the old rule would have routed as one-share fills — and
none of them became an order. That is the difference between "the fix works" and "nothing came up".

## Change vs market — no attribution claimed

ADR-0136 was live for roughly **13 of the window's minutes**. The `+4.93` is mark drift on positions
opened before it, plus the 15 post-boot entries selected by the *unchanged* fusion path (`XOM`, `JNJ`,
`WMT`, all at \|forecast\| ≥ `5.0`), which is what took gross to `$72,532.50`. **None of the PnL move is
attributable to ADR-0136**, favourably or otherwise. Positions are cumulative, so I claim no per-name
decomposition of the window delta either.

## The standing edge question, re-checked — still no

On the clustered (cohort) denominator, no source is significant at any horizon. Computed by script from
`avgReturnBps`, `stdCohortMeanBps` and `cohorts`:

| horizon | best positive | t | most negative | t |
| --- | --- | --- | --- | --- |
| `3600s` | `social` `+4.96` bps | `+1.00` | `xsreversion` `-7.06` bps | `-1.44` |
| `900s` | `momentum` `+2.16` bps | `+0.73` | `xsreversion` `-1.02` bps | `-0.93` |
| `225s` | `social` `+0.19` bps | `+0.26` | `xsreversion` `-0.14` bps | `-0.32` |

Max \|t\| anywhere is `1.44`, and it is *negative*. Against `fee_bps 1.00` plus measured `tca` slippage of
roughly half a bp, nothing here is actionable. `totalFees` **`349.04`** against `firmTotal` **`-271.70`**:
the fee bill is still larger than the entire deficit, on **`$3,935,992`** of turnover.

## What I found instead — the loop has been reading a truncated report

`scripts/system-report.py:405` emits every JSON endpoint as `json.dumps(data, indent=1)[:6000]`. Eight of
the 24 blocks hit that cap and are cut **mid-object into invalid JSON, with no marker**: `risk`, `marks`,
`fusion_targets`, `discovery`, `social`, `tca`, `strategy_selection`, `orders_day`. `fusion_targets`
reports `"instruments": 20` and only **9** survive the cut — so every cross-section diagnosis this loop
has written, including the sub-floor analysis above, ran on the first nine names and looked complete.

Two things this does **not** compromise, checked: `signals_telemetry` is `4517` chars, under the cap, so
the no-edge conclusion stands on the full source set; and `scripts/score-change.py` fetches `/api/risk`
and `/api/attribution` directly over HTTP (`urlopen`, line 94), never through the report — so no ledger
number was ever computed from truncated input. Invariant 7 holds. This is a defect in the loop's *own
eyes*, not in its money math.

## Decision

**No code change.** `e3b33679d` is mid-evaluation and piling a change on top destroys its evidence. The
truncation fix is now register item #1 and is the change I make the moment ADR-0136 is scored — it is
cheap, cannot touch the trading vector, and every future diagnosis depends on it.

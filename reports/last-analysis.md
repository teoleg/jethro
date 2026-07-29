The desk's two weight-discipline rules have never once fired on this book — both are gated on `anyAdmitted`, which the disabled edge gate makes permanently false — so 54% of the combiner's vote sits on four measured-negative sources while reversion, the only one positive at every horizon and now clearing the raw hurdle, carries 46%.

*(Every figure below is read live from `/api/risk`, `/api/fusion/targets`, `/api/var`, `/api/hedging`,
`/api/attribution` or `/api/signals/telemetry`, or computed from those endpoints by the script quoted in
the commit. None is authored here — invariant 7 / ADR-0016. The PnL verdict stays the scorer's.)*

## Situation

1. **Money.** Underwater and drifting down. Total PnL **−$107.91**, **−$4.47** on the window, **−$55.42**
   over the last 3 runs. `pnl_growth_pct` **−362.61%** against the **+1.0%** target — `on_track=false`,
   `stale=true`, `underwater=true`. Not bleeding hard, but off target.
2. **Risk.** Not the problem. Gross **$25,582.39 = 1.7%** of the $1,500,000 firm cap (headroom
   **$1,474,418**); net **$98.00 = 0.0%** of the $1,000,000 net cap. Gross fell **−$1,634.51** on the
   window. VaR95 **$238.62**, ES95 **$315.28** on **$25,582.39** covered with `skippedExposure 0.00`.
   Breaker `halted: false`. Regime `CALM` / `CHOP`, `volRatio 1.01`.
3. **Cause.** ADR-0124 is at **4/6** and unscored. Its live footprint is confined to the four names it
   silences — TSLA, META, GOOGL and now ORCL — all at `targetQty 0`, none of which traded this window.
4. **Danger.** **No.** UNDERWATER is the only flag; 98.3% of the gross ceiling is unused and the breaker
   is clear. This is not the DANGER state and nothing here calls for de-risking.

## Step 0 — ADR-0124 → ✅ VERIFIED, fourth consecutive cycle

Deployment proven behaviourally rather than from `git log` (rule 84): `uptimeSeconds` **1206** against
`traffic.timestampMillis` **1785349802582** puts the JVM start at **18:10:02Z**, and `/api/fusion/targets`
reads `sources=1 → agreement 0.0, combinedForecast 0.0, targetQty 0` for **TSLA, META, GOOGL and ORCL** —
a value only the ADR-0124 code produces, and ORCL is a name it had not yet seen. Corroborated names still
carry all the conviction (**GOOG `sources=3, agreement 0.796, fc −6.322`**; **AAPL `sources=3,
agreement 0.838, fc +4.942`**).

**No code change this cycle.** `reports/.pending-baseline.json` is present and `score-change.py score`
prints `3e7817e4f still accumulating evidence (4/6 cycles)`. Stacking a change on a measurement in
progress destroys the evidence.

## What I found instead — and it is the new must-fix #1

`TelemetryWeights.compute` sorts every source into ADMITTED, UNPROVEN (ADR-0097 → held at
`weights.min`) or CONTRADICTED (ADR-0111 → stood down to 0). Both demotions sit behind
`if (!anyAdmitted) return out;`. Since ADR-0122 disabled the edge gate on this paper book, **nothing ever
clears admission, so `anyAdmitted` is permanently false and neither rule has ever fired.** They were
written for a desk whose gate is ON; in exploration mode they switch themselves off at exactly the moment
discrimination is worth most.

Proven live, not inferred: `/api/fusion/targets` `weights` reads `reversion 2.2992, social 1.1056,
momentum 0.6265, xsreversion 0.5275, trend 0.4412` (Σ = 5.0000) — **no source at the
`jethro.fusion.weights.min=0.25` floor and none stood down to 0**, the exact signature of
`anyAdmitted == false`.

Cohort-clustered on `/api/signals/telemetry` (`t = avgReturnBps ÷ (stdCohortMeanBps ÷ √cohorts)`, the
gate's own ADR-0077 construction):

| source | 225 s | 900 s | 3600 s | weight | share |
|---|---|---|---|---|---|
| **reversion** | **+0.488** | **+1.611** | **+1.239** | 2.2992 | **45.98%** |
| social | −0.000 | −0.482 | −0.200 | 1.1056 | 22.11% |
| momentum | −0.039 | +0.309 | −1.446 | 0.6265 | 12.53% |
| xsreversion | −0.926 | +0.190 | −1.074 | 0.5275 | 10.55% |
| trend | −0.099 | −0.038 | −1.140 | 0.4412 | 8.82% |

**54.02% of the vote is carried by the four sources that are non-positive at the selected rung.**
Reversion is the only source positive at *all three* rungs, and its expectancy scales in horizon the way a
real signal does and noise does not — **+0.174 → +2.708 → +8.614 bps** — now clearing the raw 1.5 hurdle
at its best rung (**t +1.611 on 103 cohorts**, up from +1.406 on 102 last cycle). So there is finally a
measured edge to shape, which is the precondition the standing priority sets before touching the
combiner; and this is not a re-weight of edgeless sources, it is a designed safety rule that is
unreachable in the configuration the desk actually runs.

## Checked and deliberately not promoted

- **"The gate measures the wrong horizon"** → **falsified**: `HorizonLadder` (ADR-0082) already evaluates
  3600 / 900 / 225 (`jethro.signals.horizon-rungs=3`) and selects by best p-value.
- **"Bonferroni across 3 nested rungs is over-conservative"** → true in the literature, but with the gate
  off it changes nothing about what sizes this book. Revisit only if exploration mode is turned off.
- **JPM** was bought **0 → +16** this window on `agreement 0.527` with `reversion +11.288` against
  `trend −13.392`, and is the worst realized name at **−$25.43** — suggestive of exactly that dilution,
  but n = 1 name. Rule 77: log, don't chase.

## Attribution this window — honest split

Every name ADR-0124 touches sat at `targetQty 0` and did not trade, so **none** of the **−$4.47** is the
change's. It is market: ALPHA carries **−$71.91** of unrealized mark against **−$10.42** realized, on four
equity shorts (MSFT, AAPL, GOOG, AMZN) and two longs (JPM, NVDA). The **−$1,634.51** gross fall is the
hedge tracking down — EQUITY net **−$6,409.26** against one ES leg at **+$6,507.25**, firm net **$98.00**,
`/api/hedging` `status: ON-TARGET` — not a de-risking (rule 91). MACRO's **−$35.82** is frozen:
`positionCount 1` at `grossExposure 0.00`, a closed historical NQ loss, not an ongoing bleed.

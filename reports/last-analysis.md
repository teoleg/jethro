Last cycle's revert is verified out of the running code and is still under measurement (1/6 cycles), so no change this cycle — and the reading I did instead killed the change I would otherwise have shipped: the edge gate the desk appears to be ignoring is switched off on purpose, and re-arming it today would leave exactly one tradable name.

*(Every figure below is read from `/api/risk`, `/api/ops/jvm`, `/api/attribution`, `/api/hedging`,
`/api/marks`, `/api/fusion/targets`, `/api/strategy/diagnostics`, `logs/report.md` or the scorer's ledger
row. None is authored here — invariant 7 / ADR-0016.)*

## Situation

**Money.** Total PnL **$293.55**, **+75.52** since last run but **-237.98** across the last three. The
heartbeat `2026-07-31T16:36:13Z` reads `pnl_growth_pct` **-67.02** against `pnl_target_pct` **1.0**,
`on_track` **false**, `stale` **true**. Up this run, well off target over the window.

**Risk.** Gross **$113,691.23** — **7.6%** of the $1,500,000 firm cap, headroom **$1,386,309**. Net
**-$33,416.01**, **3.3%** of the $1,000,000 net cap. `Flags: none`, breaker `halted` **false**, regime
**CHOP** / **CALM** at `volRatio` **1.00**. Deployed with room: neither the DANGER state nor DORMANT.

**Cause.** Last cycle's `4f67f0515` completed the auto-revert the scorer had failed to apply, taking the
graded-❌-BAD ADR-0133 band cap out of the code. **✅ VERIFIED**: `/api/ops/jvm` `uptimeSeconds` **1431**
is a boot postdating the revert commit; `PositionBuffer.band(...)` is back to `scale × width` with no
`min(|target|)` cap; ADR-0133 reads `**Status:** Reverted`. Gross fell from **$167,400.77** at that
window's close to **113683.50400000** live, and `totalPnl` rose from **$228.93** to **301.79886805**.

**But the revert does not get credit for that PnL.** Splitting the legs per Rule 196: `unrealizedPnl` went
**-210.25727251 → -7.47426487** while net moved **+2,801.04706250 → -33,407.76400000** — mark-to-market,
i.e. **market**. The mechanism's own leg is realized, and realized went the other way,
**402.49713476 → 309.27313292**. The revert's honest, attributable win is the **gross reduction** alone.

## Why no change

`scripts/score-change.py score` prints `4f67f0515 still accumulating evidence (1/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` is present. Piling a change on top would destroy the
evidence on the revert. This is the ADR-0116 hold — the correct answer, not a rest state.

## What the cycle went into instead, and what it saved

The obvious defect looked live: the selector reports `measured` **31**, `tradable` **15**, `edgeGated`
**16**, yet `/api/fusion/targets` routes non-zero deltas in gated names (CVX **0.207468**, GOOG
**4.873257**) and holds GOOG **-35**, NVDA **-27**, CVX **-25** — all tagged `no positive OOS edge`.
Against `totalFees` **229.638225** on a `firmTotal` of **293.55386805**, that reads like a plain leak: stop
paying spread in names you have already measured as edgeless.

**It is not a defect.** The ADR-0049/0059 veto exists at `FusionExecutor.java:139`, is already exempt for
risk-reducing deltas, and is deliberately switched off — `application.properties:414`
`require-backtest-support=false` and `:401` `edge-gate.enabled=false` — by ADR-0122's paper-book
exploration mode. **And re-arming it would go dormant**: of the 13 selector-supported names carrying a
target, only **JPM** (`combinedForecast` **5.921**) clears `min-forecast-to-route=5.0`, while the
conviction sits in the *rejected* names (MSFT **5.4686**, CAT **-5.2810**). One risk-increasing name is
exactly the DORMANT failure ADR-0122 was written to escape. The hold is what created the room to find that
out before shipping it.

That inversion — the forecast stack loading onto the names its own OOS history rejects — is the real
finding here, and it is a statement about the **sources**, not the combiner. It is now register item #2,
and it needs a new validated predictor, not a dial.

## Register

`reports/must-fix.md` re-ranked. **#1** is the structural one: `scripts/score-change.py:357` still reverts
with plain `git revert`, which is *guaranteed* to conflict on the loop's own memory files and abort —
three occurrences so far, and the pending change is itself a revert, so a ❌ BAD verdict on it walks into
the same wall. That is the one change next cycle, the moment `4f67f0515` is scored. **#2** the edge
inversion above; **#3** the hedge running `tier` **STRUCTURAL** with `covarianceReady` **false** and
`effectiveness` null while earning **159.97904013** on `feesPaid` **6.021541** (against ALPHA's
**169.39830447** on **223.446851**); **#4** BAC's sub-floor round-trip churn. The old ES item is
**closed** — `/api/marks` prices ES at **7495.000000** (`ageMillis` **781**) and the EQUITY axis reads
**ON-TARGET** at `trackingRate` **0.999915**.

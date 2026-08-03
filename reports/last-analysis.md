ADR-0135 is live and correct on the one-source names — but the re-entry blocker is a different control: the ADR-0126 unarmed-stop veto freezes every equity name flat because the risk-cut σ sensor never warms inside the app's ephemeral lifetime.

*(Every figure below is read from `/api/risk`, `/api/fusion/targets`, `/api/signals/telemetry`, the app's
own WARN log in `logs/report.md`, the scorer's output, or the repo source. None is authored here —
invariant 7 / ADR-0016.)*

## No code change this cycle — the pending change is still under measurement

`scripts/score-change.py score` reports: **`74a47adee still accumulating evidence (1/6 cycles) — held, not
scored this run`**, and `reports/.pending-baseline.json` is present. Per the ADR-0116 rule, piling a new
change on top would destroy the evidence for ADR-0135. So this run verifies, diagnoses, and records; it
ships no code. The diagnosis below is what next cycle's one change targets, and it is now item **#1** in
`reports/must-fix.md`.

## Situation — the four questions

1. **Money.** Total PnL **$32.51**. Since last run **+0.00**; over the last three runs **+0.00**.
   `on_track=false`, `stale=true`. The split: ALPHA **-18.59706568**, HEDGE **+86.93246234**, MACRO
   **-35.82347655** — the firm total is positive only because of HEDGE, unchanged from last cycle.
2. **Risk.** Gross **$0.00**, net **$0.00** — **0.0%** of the $1,500,000 firm cap, **$1,500,000** of
   headroom. **DORMANT**, now for a fourth day. Not danger; the largest opportunity on the board.
3. **Cause.** Last cycle's ADR-0135 change deployed and is behaving exactly as specified (below), but it
   could only stop the desk *exiting*. It was never going to make the desk *re-enter*, and it hasn't.
4. **Danger.** None — no bleed, breaker clear, nothing near a cap. The failure here is inaction, not risk.

## Step 0 — ADR-0135 (`74a47ade`): ✅ VERIFIED on its own terms

- **Deployed.** `/api/fusion/targets` now returns the `estimable` field on every target — the field this
  change introduced. The running app carries the commit.
- **Doing what it claimed.** The nine one-source names — CAT, UNH, JPM, TSLA, HD, META, WMT, XOM, CVX —
  all read `sources: 1`, `estimable: false`, `agreement: 0.0`, and `deltaQty: 0.000` against
  `currentQty: 0`. That is the ADR-0135 branch: an unestimable view targets the inventory already held and
  trades nothing **in either direction**. No liquidation order was generated on any of them.
- **Caveat, stated plainly.** The book was already flat when this deployed, so the branch is being
  exercised at zero inventory. Its real test — that a breadth collapse at the equity cash close no longer
  round-trips a *held* book — cannot be observed until the desk holds something again. That is gated on
  the item below, which is why the item below is #1.

## The re-entry blocker — traced end to end, and it is not the combiner

Thirteen names have a real, estimable view right now: MCD `combinedForecast -8.446` at `sources: 2`,
`agreement 0.596`, `targetQty -670.773`; BAC `+6.507` → `+3090.103`; PFE `-2.315` → `-1588.257`. Every one
of them shows `deltaQty 0.000` against `currentQty 0`, and the buffer telemetry reads `insideBuffer: 22`
with every entry in `aims` at `0.0`. The desk has conviction and routes nothing.

The mechanism is `PositionBuffer.mayIncrease` (`app/src/main/java/io/jethro/app/fusion/PositionBuffer.java:202`).
`edgeGate` is `null` in telemetry, so the ADR-0064 gate is silent and cannot be the vetoer; that leaves the
ADR-0126 clause `stopArmed == null || stopArmed.test(instrument)`. `FusionLifecycle.stopArmed`
(`FusionLifecycle.java:550`) answers from `streamVol.sigmaPerSample(instrument).isPresent()`. When it is
false the delta is clamped `reduceOnly` — which at `held = 0` is exactly zero — and then, at
`PositionBuffer.java:164`, **the aim is re-seeded to `held + delta` = 0**. So the ADR-0080 aim path is
reset to flat every single cycle and can never accumulate toward the target. That is precisely the observed
telemetry: all aims `0.0`, `insideBuffer: 22`, gross `$0.00`.

`streamVolMeasuredNames` is **1** of 22. The app's own WARN log names the other side of it, per instrument:
`risk-cut σ sensor still cold for … after seeding N of 121 stored prices` — AAPL **75**, MSFT **67**, GOOG
**61**, NVDA **85**, AMZN **44**, BAC **35**, KO **34**, WMT **33**, NEE **33**, PFE **31**, JNJ **30**,
XOM **30**, PG **29**, CVX **29**, JPM **28**, CAT **27**, HD **27**, UNH **27**. Eighteen equities, every
one short of the requirement.

**Why it is structural, not a passing warm-up.** The requirement is `vol-span=120` (`application.properties:453`)
→ `warmupPrices() = 121` (`StreamVolatility.java:119`), replayed at the 30 s planning cadence — about
**60.5 minutes** of 30 s-spaced history. The durable seed supplies 27–85 of it, and the app is *ephemeral*:
`uptimeSeconds` is **727** and the loop tears it down each cycle. A sensor needing ~60 min of samples, seeded
short, in a process that lives ~30 min, never warms — so `stopArmed` is false for every equity on every boot,
forever. The tape is not the problem; marks are live (`ageMillis` 122–232, `ticksDropped: 0`) and the same
mark store warms the covariance for **14** names. The stop sensor is simply the strictest consumer of the
thinnest-supplied history, and it holds the whole equity book hostage.

ADR-0126's principle is sound — do not open a position the risk cut cannot protect. The defect is that its
warm-up requirement is unsatisfiable under the deployment's process lifetime, so a control meant to gate
*some* positions vetoes *all* of them, permanently. That is what has kept $1,500,000 of headroom idle while
the desk holds measurable views. Fixing it is next cycle's one change; the VERIFY-BY is in
`reports/must-fix.md`.

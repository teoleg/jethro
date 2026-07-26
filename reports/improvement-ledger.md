# Improvement ledger — what each change did to PnL & exposure

The running record of the continuous-improvement loop (ADR-0063). Every code change the loop makes is
scored **on the next run** by its measured delta on the objective — **total PnL and total exposure,
firm-wide (all books incl. the hedge)** — the real money made and the real money at risk, the same
figures as the Overview headline. Newest entries at the top.

**Every number here is produced by `scripts/score-change.py`, not by the model** (invariant 7 /
ADR-0016). The script reads the live `/api/risk` `.total` (and `/api/attribution` for fees) in exact decimal,
computes the deltas and the verdict deterministically, writes each row, and commits an audited JSON
snapshot under `reports/attribution/` so any verdict can be recomputed from source. The rows below are
never hand-edited.

## Verdict rule (computed, with a documented noise deadband)
Moves smaller than the deadband are treated as market noise, not an effect of the change. Defaults:
PnL ±$50, gross exposure ±1% of prior — both `PLACEHOLDER — Oleg to set` (env
`JETHRO_SCORE_PNL_DEADBAND_USD`, `JETHRO_SCORE_EXPOSURE_DEADBAND_FRAC`), deliberately conservative, not
calibrated.
- ✅ **GOOD** — PnL up (beyond deadband) **and** exposure not up: more money for less/equal risk.
- ❌ **BAD** — PnL not up **and** exposure up: no gain, more risk. **Auto-reverted** — the scorer opens
  a `git revert` commit so a bad change does not stay.
- ⚠️ **MIXED** — the other cases (PnL up but exposure also up; PnL down/flat with exposure down/flat).
  Annotated by the risk-adjusted read (did PnL-per-$1-gross improve?); the note says which way.

PnL/exposure are the **firm total** (from `/api/risk` `.total`: total PnL net of all costs, total gross/net
exposure — all books incl. the hedge). "Before" is the vector at the moment the change was committed;
"After" is the vector on the next run, once the book has traded under the new code.

| Scored (UTC) | Commit | What changed | PnL before→after (Δ) | Gross exp before→after (Δ) | Net exp before→after (Δ) | Verdict | Note |
|---|---|---|---|---|---|---|---|
| 2026-07-26T00:00:25Z | `f2f79b3f6` | fusion target book spans held positions — a name with no fresh forecast is planned flat and worked down; risk-reducing deltas skip the conviction floor and the backtest-support veto (ADR-0065) | -$7,011.81 → -$7,009.10 (+$2.71) | $345,623.41 → $1,118.10 (-$344,505.31) | -$30,395.29 → -$1,118.10 (+$29,277.19) | ⚠️ MIXED | risk-adj (PnL/$1 gross) worsened -0.02029→-6.26876 |
| 2026-07-25T20:43:57Z | `1c319b120` | cost-aware edge gate: fusion goes reduce-only unless a source's measured expectancy beats measured round-trip slippage with significance (ADR-0064) | -$10,439.20 → -$6,760.94 (+$3,678.27) | $415,183.73 → $368,230.93 (-$46,952.80) | -$192,131.75 → -$7,272.24 (+$184,859.51) | ✅ GOOD | PnL up, exposure not up; risk-adj (PnL/$1 gross) improved -0.02514→-0.01836 |

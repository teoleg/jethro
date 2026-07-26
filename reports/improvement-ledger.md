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

**Owner target (2026-07-26): total PnL must grow ≥ 1% every 3 iterations.** The per-run heartbeat
(`reports/run-status.json`, shown on the Improve page) carries `pnl_growth_pct` vs `pnl_target_pct` and
the `on_track` / `stale` / `underwater` flags. A flat or negative PnL off that target — especially with
exposure still high — is a **failure state** the loop must actively work, not an acceptable "flat".

| Scored (UTC) | Commit | What changed | PnL before→after (Δ) | Gross exp before→after (Δ) | Net exp before→after (Δ) | Verdict | Note |
|---|---|---|---|---|---|---|---|
| 2026-07-26T17:15:07Z | `daa2d2bd6` | reversion sensor: add a continuous, self-calibrating range-position mean-reversion forecast source (fade the stretch, weighted by 1-ER so it refuses to fade a trend) so the desk has a measurable view in the CHOP regime its detector reports — not the trend source negated, and it must earn its own expectancy before the edge gate lets it size (ADR-0070) | -$867.47 → -$867.78 (-$0.31) | $786.05 → $785.36 (-$0.68) | $56.67 → $56.36 (-$0.31) | ⚠️ MIXED | no material change (within noise band); risk-adj (PnL/$1 gross) worsened -1.10358→-1.10494 |
| 2026-07-26T17:00:09Z | `9b6db1d49` | hedge no-trade band: trade the delta once it clears the SMALLER of the absolute $10k churn guard and 25% of the hedge's own scale, so an absolute guard can no longer strand a hedge smaller than itself and a zero target is always reachable (ADR-0069) | -$876.87 → -$867.84 (+$9.03) | $6,145.14 → $785.01 (-$5,360.13) | $5,417.63 → $56.30 (-$5,361.32) | ⚠️ MIXED | risk-adj (PnL/$1 gross) worsened -0.14269→-1.10551 |
| 2026-07-26T16:45:12Z | `c12099fea` | fusion source trust: weight each source by Phi of its own measured expectancy t-statistic instead of the floored hit-rate advantage, which read every below-coin-flip source as identical (ADR-0067) | -$862.80 → -$865.56 (-$2.76) | $6,891.81 → $6,886.46 (-$5.35) | $4,900.98 → $4,898.28 (-$2.70) | ⚠️ MIXED | no material change (within noise band); risk-adj (PnL/$1 gross) worsened -0.12519→-0.12569 |
| 2026-07-26T16:00:12Z | `fb9273505` | edge gate: replace the binary measured-edge switch with a continuous risk appetite (Phi of the best-evidenced t-stat) scaling the per-name notional, keeping full reduce-only for measured harm (ADR-0067, supersedes ADR-0064) | -$22.83 → -$43.26 (-$20.43) | $0.00 → $43,624.32 (+$43,624.32) | $0.00 → -$2,223.07 (-$2,223.07) | ❌ BAD | exposure grew with no PnL gain — reverted; risk-adj n/a (zero gross) |
| 2026-07-26T15:30:27Z | `a76a0e6ca` | trend sensor: warm the scale estimator over half a normalisation span before dividing by it, so a freshly-warmed name no longer opens pinned at the forecast cap (ADR-0066 correction) | -$22.83 → -$22.83 (+$0.00) | $0.00 → $0.00 (+$0.00) | $0.00 → $0.00 (+$0.00) | ⚠️ MIXED | no material change (within noise band); risk-adj n/a (zero gross) |
| 2026-07-26T00:00:25Z | `f2f79b3f6` | fusion target book spans held positions — a name with no fresh forecast is planned flat and worked down; risk-reducing deltas skip the conviction floor and the backtest-support veto (ADR-0065) | -$7,011.81 → -$7,009.10 (+$2.71) | $345,623.41 → $1,118.10 (-$344,505.31) | -$30,395.29 → -$1,118.10 (+$29,277.19) | ⚠️ MIXED | risk-adj (PnL/$1 gross) worsened -0.02029→-6.26876 |
| 2026-07-25T20:43:57Z | `1c319b120` | cost-aware edge gate: fusion goes reduce-only unless a source's measured expectancy beats measured round-trip slippage with significance (ADR-0064) | -$10,439.20 → -$6,760.94 (+$3,678.27) | $415,183.73 → $368,230.93 (-$46,952.80) | -$192,131.75 → -$7,272.24 (+$184,859.51) | ✅ GOOD | PnL up, exposure not up; risk-adj (PnL/$1 gross) improved -0.02514→-0.01836 |

# ADR-0128: Historical data is periodically refreshed, not frozen at first load

- **Status:** Implemented
- **Date:** 2026-07-29
- **Deciders:** Oleg
- **Tags:** history, risk, VaR, data-pipeline

## Context

The daily-history date range shown in ops was **constant** (e.g. 7/20/2021 → 7/20/2026) — a fixed
5-year block whose end never advanced. The cause: `HistorySeeder` was **one-time** — `if (have >=
windowDays) return;` — so once the SEED window was on file it never fetched again. The bootstrap prior
was therefore frozen at whatever day it was first loaded, and every calculation that reads it —
historical + parametric **VaR**, **vol-targeting**, the **hedge covariance** — was running on a window
whose right edge was stale by however many days had passed since the initial load. The design intended
live accumulation (`MarketHistoryRecorder`) to carry it forward, but the visible range never moved, so
in practice the history was hardcoded-in-time.

## Decision

Make the seeder **periodic** and have the refresh keep the `daily_close` SEED series rolling forward:

- **Scheduled, not one-shot.** `HistorySeeder` runs on a daemon scheduler at
  `jethro.hedge.history-refresh-hours` (default 24) — seed at boot, then refresh daily.
- **Skip when current, fetch when stale.** A run is a cheap no-op when the SEED window is both
  sufficient (`>= windowDays`) AND its newest day is within `STALE_DAYS` (4) of today; otherwise it
  re-fetches and tops up the tail.
- **`DO UPDATE`, not `do nothing`.** The upsert now re-anchors the whole SEED series to one consistent
  scale and appends the new tail. Returns — the only thing VaR/covariance/vol-targeting consume — are
  invariant to the uniform re-scaling, and the SEED rows are a **separate `feed_mode` PK partition** from
  the session's live-accumulated closes, so this never touches the running stream (ADR-0073 preserved).
- **Wiring is automatic.** Every consumer already reads `daily_close` through `DailyCloseSeries` (SEED +
  running-mode), so keeping the *source* current updates VaR, vol-targeting and the hedge covariance with
  **no per-consumer change** — the one place to fix, fixed once.

The OOS-backtest bars file (`data/historical-bars.json`) is refreshed daily on the same principle by the
loop step added earlier (ADR-0127 companion); this ADR covers the DB history the risk engine runs on.

## Consequences

- **Intended:** the historical window's right edge tracks the latest close instead of freezing at first
  load, so VaR/vol-targeting/hedge covariance always run on current data — the thing the constant date
  range revealed was broken.
- **Cost:** a daily re-fetch of the configured range per name when the tail is stale (spaced to respect
  the provider's rate limit) and a rewrite of the SEED rows; both are daily-cadence and idempotent, and
  the skip-when-current guard makes same-day ticks free.
- **Honest scope:** the learned-signal **training bars** (`TrainingBarsLoader`) remain one-time; they
  feed the ADR-0053 model, not VaR/risk, and are a separate follow-up if wanted. Live accumulation
  (`MarketHistoryRecorder`) is unchanged and still contributes the running mode's own closes.
- Validated: full Spring context loads with the scheduled seeder; risk + trading + context tests green.

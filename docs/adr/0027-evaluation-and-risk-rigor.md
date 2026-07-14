# ADR-0027: Evaluation & risk rigor — hypothesis outcome scoring, out-of-sample backtests, VaR + firm breaker, day boundary

- **Status:** Proposed
- **Date:** 2026-07-14
- **Deciders:** Oleg
- **Tags:** risk, ai, backtest, quant

## Context

The platform currently cannot measure whether it has edge. (1) The bounded-autonomy gate
(ADR-0022) is a backtest run on the **same seed the live sim replays** — in-sample
self-confirmation, not evidence. (2) Executed hypotheses are never scored: a SWING-horizon
SHORT is submitted and nobody ever checks whether it made money, so model hit-rate and
conviction calibration are unknowable. (3) Risk has notional caps and five deterministic
scenarios but no distributional measure — "what's a normal bad day?" is unanswerable —
and no firm-level circuit breaker (book loss caps exist; nothing halts *all* auto-trading
on a firm drawdown). (4) P&L has no day boundary: inception-to-date only, no closing
marks, no daily attribution. These four are one theme: the measurement layer is missing.

## Decision

We will build the measurement layer, deterministic end to end (invariant 7):

1. **Hypothesis outcome scoring.** Every executed hypothesis carries its horizon
   (INTRADAY ≈ 6h, SWING ≈ 5 trading days, POSITION ≈ 20); at expiry the platform marks
   entry vs. current price and persists the outcome (signed P&L, WIN/LOSS) to
   `hypothesis_record`. Hit-rate and expectancy by conviction surface in the UI; the
   autonomy `min-conviction` becomes a dial calibrated against measured hit-rate.
2. **Out-of-sample backtesting.** Immediately: the autonomy gate's backtest runs on
   **K=5 seeds disjoint from the live seed**, requiring net-positive on a majority.
   Next: historical daily-bar replay (Finnhub `/stock/candle`, or the ADR-0026 calibration
   data) with **walk-forward** splits — fit on `[t₀,t₁)`, evaluate on `[t₁,t₂)`, roll.
   "Backtest-supported" then means out-of-sample supported.
3. **Historical-simulation VaR/ES + firm breaker.** Persist daily portfolio returns;
   VaR₉₅ = 5th-percentile 1-day loss over the trailing window, ES = mean beyond it,
   shown on the risk surface. A deterministic **firm max-drawdown breaker** halts all
   auto-execution (strategy AND autonomy; manual orders still allowed) when tripped;
   operator reset only.
4. **EOD day boundary.** Closing-mark snapshot per session (calendar from ADR-0026
   follow-up), daily realized/unrealized attribution, and day rollover so "today's P&L"
   is a real number that survives restarts.

## Alternatives considered

**Parametric (covariance) VaR first.** Deferred: needs the EWMA covariance estimator and a
normality assumption; historical simulation is assumption-light and reuses stored returns.
Revive alongside covariance-aware sizing.

**Monte-Carlo VaR.** Deferred until optionality exists — for linear books it adds cost, not
information, over historical simulation.

**Skip measurement, upgrade the model instead (bigger LLM).** Rejected: without outcome
scoring no model — of any size — can *earn* wider autonomy; capability without
measurement is exactly how AI trading goes wrong.

## Consequences

- Positive: the platform becomes falsifiable — edge is measured, not asserted; a firm-level
  safety net; daily P&L truth; conviction labels acquire meaning.
- Negative: more state (returns history, outcome jobs, EOD snapshots); the stricter OOS
  gate means autonomy fires less at first (correct, but visible); day-boundary logic
  drags in the calendar dependency.
- Follow-ups: TCA vs. arrival price (needs ADR-0025 spreads); conviction-calibrated
  autonomy limits; parametric VaR + covariance-aware sizing.

## Implementation note — autonomy gate correction (2026-07-14)

Point 2's OOS backtest is built and shipped, but as the **autonomy gate** it proved to be a
category error in practice: it measures the *momentum strategy's* edge on the thesis's
instrument, not the thesis's edge — and net of ADR-0025's honest costs it was rarely
median-positive, which silently revoked all autonomy (no AI trade ever fired). The gate is now
**point 1's own data**: the AI sleeve's measured track record. Below `min-track-record` scored
outcomes the envelope trades **probation size** (`probation-order-notional`, default cap/4) to
build the record; with a full record it trades full size **only while summed outcome P&L is
positive**; a non-positive record revokes autonomy until humans intervene. The OOS backtest
remains as advisory annotation on every thesis (and still gates nothing). This strengthens the
ADR's thesis — autonomy is earned from measured outcomes, not proxied from a different
strategy's backtest. Scoring uses mark-to-mark P&L at horizon expiry; probation-sized entries
score the same way, so the record reflects what was actually traded.

## Implementation note — session calendar + EOD day boundary (2026-07-14, point 4)

A `TradingCalendar` now defines the trading day. Pure sim runs get the **compressed sim
calendar** — one session per `sim-seconds-per-day` wall seconds, matching the simulator's
time base exactly, with synthetic sequential dates anchored past any persisted history so a
restart never rewrites a closed day. Consequence: the VaR window and daily attribution accrue
at sim speed (60 daily observations in ~2 wall hours at the default 120s/day) instead of
needing 60 real days. Live providers get **wall-clock dates** in `session-zone` (default
America/New_York); v1 treats every date as a session — Friday→Monday is measured as one day's
return and exchange holidays are not modelled yet (disclosed, not faked).

At each boundary the `EodService` freezes the ended day: closing marks → `daily_close`, firm
total → `firm_equity`, per-book cumulative P&L → `book_equity` (V18; day attribution =
consecutive-row differences computed at read time, never stored twice), expires working DAY
orders, and re-anchors "today's P&L" = live firm total − previous session close (restart-safe:
the anchor reloads from `firm_equity`). `/api/eod` serves session day, today's P&L and recent
daily history; the Overview stats row gained a "Today's P&L" tile. **DAY time-in-force is now
accepted** (it was rejected-with-reason until this calendar existed): unmarketable DAY LIMIT
orders work intraday exactly like GTC and are swept ROUTED→CANCELLED at the close by the same
CAS as manual cancels, so a racing fill still wins cleanly. The correlated simulator also
gaps close→open at each sim day boundary — one correlated Student-t draw carrying ~30% of a
trading day's variance (the stylized US overnight share), through the same regime/Cholesky
machinery, with the rates deltas fed to the curve so futures and swaps gap coherently.
Follow-ups: exchange holiday calendars + a 17:00-ET futures-style roll; closing-auction marks
for live feeds; intraday-vs-overnight P&L attribution.

## Implementation note — vol-targeted position sizing (2026-07-14)

Sizing now consumes the measurement layer: order notional = `risk-budget-daily` / σ_daily,
where σ_daily is the **EWMA (λ=0.94) of recorded daily-close returns** (`VolMath` over
`daily_close` — the same history the VaR window reads, so sizing and risk measure the same
world; with the compressed sim calendar the estimate is live after ~10 sim days ≈ 20 wall
minutes). One formula (`VolTargeting`) for BOTH engines — the momentum strategy and the AI
hypothesis sleeve — so risk per position is comparable across engines and asset classes.
Worked: $250/day ÷ 1.8%/day (AAPL) = $13,888.88; ÷ 0.40%/day (ZN) = $62,500 — the Treasury
future correctly gets MORE notional to carry the same daily risk. Per-class order caps
bound the near-zero-vol blow-up; regime scale still multiplies on top; warm-up falls back
to the previous fixed-notional (+ signal-vol clamp in the strategy) sizing, disclosed.
Follow-up: covariance-aware (portfolio-level) sizing remains listed under Consequences.

## Implementation note — second strategy through the OOS harness (2026-07-14)

A `Strategy` port now fronts the signal engine (the live lifecycle and the backtest engine
both drive it), and `MeanReversionStrategy` is the second implementation: the SAME
vol-adaptive z-score detector as momentum, opposite conclusion — SELL the +σ rip, BUY the
−σ dip (in a long-only book the SELL side only reduces, the standing guard applies).
Momentum and mean reversion cannot both be right on the same tape at the same horizon;
that is exactly what the harness is for — `jethro.strategy.algo` selects the live algo,
`/api/backtest?algo=…` (and the Backtest page's selector) runs either through the identical
guardrails, vol-targeted sizing, honest execution costs and multi-seed OOS medians. Adding
the second algo required ZERO new harness code — the point of building the harness first.

## Implementation note — calendar refinements (2026-07-14, task #15)

Three refinements to the session calendar. (1) **US trading days + futures roll**: the
live-feed calendar now rolls at `session-roll-hour` (default 17:00 ET, the CME settlement
boundary) and skips weekends and the ten NYSE full-closure holidays (`UsTradingCalendar` —
pure rules incl. Good Friday via the Gregorian computus, Sat→Fri/Sun→Mon observance; early
closes count as full sessions, disclosed). Friday 17:30 belongs to Monday's session. (2)
**Tape-synchronized sim days**: the sim adapter's tick loop runs at tickInterval + work
time, so a wall clock drifts away from the tape's day boundaries over a long run — the sim
calendar now keys to the adapter's own `simDayIndex()` counter, keeping session closes and
close→open gaps on the same boundary always (wall-time fallback until the tape starts).
(3) **Overnight/intraday P&L attribution**: the EOD watcher buffers the last PRE-boundary
state each check, so the close is guaranteed pre-gap; the new session's open (post-gap) is
persisted (`firm_equity.open_pnl`, V21) — today's P&L = overnight (open − prev close) +
intraday (live − open), restart-safe, on `/api/eod` and the Today's-P&L tooltip. Still
open, stated: real closing-auction prints need a licensed feed — the boundary snapshot
remains the close for live runs.

## Implementation note — walk-forward historical replay (2026-07-14, point 2 "Next")

The step past sim-seed OOS landed: `WalkForwardEngine` replays REAL daily bars
(`scripts/fetch_bars.py` → `data/historical-bars.json`, Stooq — the same source as the sim
calibration; ES/NQ use index proxies, stated in the script) with rolling fit/eval windows:
parameters (lookback × threshold grid) are chosen on each fold's FIT window only, then the
strategy is measured on the UNSEEN eval window with those frozen parameters — out-of-sample
by construction. Accounting matches the sim backtest exactly (same average-cost ledger,
per-fill costs from the live execution config, long-only clamp, order/position caps; windows
start flat and include end-of-window unrealized — no fake exit fills; strategies warm up on
the days preceding each window so short windows aren't half warm-up). "Supported" = OOS net
positive on a strict majority of folds. Defaults: fit 252d / eval 63d. Surfaces:
`/api/backtest/walkforward?algo=momentum|mean-reversion` + a Backtest-page panel that
explicitly labels fit P&L as in-sample flattery and the OOS column as the only evidence.
The sandbox cannot fetch data (no egress) — the bars file is generated on the Pi; a missing
file reports "no historical bars", never a fabricated tape.

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

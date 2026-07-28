# ADR-0115: The pre-trade guardrail opens no new exposure outside the US session

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, risk, trading

## Context

The Alpaca feed (ADR-0056) was mis-tagged `SIM` until the feedMode fix earlier today; once it ran as a
genuine `LIVE` epoch, the fresh (flat) book opened positions **after the 16:00 ET close**. Observed live:
`AAPL ±24` across the AI and ALPHA books ($16k gross, net-flat), plus `GOOG -16` and `JPM -20` shorts the
desk then **could not manage** — every cover order returned `REJECTED · no market data`, firing every ~30 s.

The cause: the platform had a *data-availability* guard ("no mark → reject") but **no session-hours guard**.
Alpaca streams some names after the cash close (IEX extended data), so a name that still prints (AAPL) gets
traded out-of-hours while a name that has gone quiet (GOOG/JPM) rejects — leaving a lopsided, half-stuck
book opened into a thin/stale tape that cannot be worked until the reopen. The market-closed gate added
today protects the *improvement loop* (no Opus cycle on a dead tape); it does nothing for the *trading
engine*.

The pre-trade guardrail (ADR-0018) is the single deterministic order-admission choke point and already
distinguishes **risk-adding** from **risk-reducing** orders (a loss-breached book may still flatten). That
is exactly the seam a session gate belongs in.

## Decision

Add a **session gate** to `PreTradeGuardrail`: outside the market session it admits **only risk-reducing
(flattening) orders** — new exposure is refused, existing positions can always be closed.

- "Session open" is `UsTradingCalendar.isRegularSessionOpen(now in exchange zone)` — a trading day (the
  existing NYSE full-closure calendar) and 09:30–16:00 ET. Half-day early closes stay unmodelled (the
  existing disclosed convention); for a gate that errs safe — at most a little extra on ~2 days/year, never
  blocking a real session.
- The gate is injected, not hard-wired: `TradingCalendar.isTradingSessionOpen()` defaults to **always-open**,
  so a **SIM/REPLAY** continuous tape is unaffected; only `WallClockSessionCalendar` (LIVE) restricts to
  hours. `RiskConfig` wires the guardrail's `SessionGate` to the calendar, behind
  `jethro.trading.session-gate.enabled` (default `true`).
- Deterministic — no model, exact decimal (invariants 1 & 7). The gate reuses the risk-adding test the
  guardrail already computes (`projected gross > current gross`).

## Consequences

- **Intended:** on a live feed the desk opens nothing overnight/weekends/holidays or after the cash close;
  it can still flatten out-of-hours when a mark exists. Tonight's failure mode cannot recur.
- **Honest cost:** a genuine desire to open at 09:29 or 16:01 is refused — acceptable, and reversible via
  the flag. Positions opened legitimately in-session are held across the close as normal.
- **Unaffected:** every other guardrail limit, the firm breaker (ADR-0027), sim/replay runs, and the
  backtest/OOS gates. Existing `PreTradeGuardrail(projection, limits)` callers keep the 2-arg constructor
  (always-allowed), so no test or sim path changes behaviour.
- **Deferred (register):** half-day early-close times, and applying the same session awareness to the
  fusion executor's re-plan cadence so it stops *emitting* the rejected out-of-hours orders rather than
  having them refused at the floor.

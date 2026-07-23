# ADR-0057: Intraday strategy family — VWAP-deviation reversion first, pairs deferred

- **Status:** Proposed
- **Date:** 2026-07-21
- **Deciders:** Oleg
- **Tags:** strategy, quant, cost, backtest

## Context

The two shipped algos (momentum, mean-reversion) are ONE detector — a vol-adaptive z-score over a
~2-minute window — read in two directions, per-instrument OOS-selected (ADR-0043/0044). Oleg wants
higher-frequency intraday strategies. Four forces shape the space. (1) **Cost is the binding
constraint**: an equity round trip is ~7 bps (spread + fee, ADR-0025), so a strategy doing K
round-trips/day must earn > K×~7 bps gross just to break even — the OOS *net-of-cost* gate
(ADR-0049/0027) is exactly what stops a too-frequent strategy from shipping. (2) **Data**: the free
Alpaca IEX feed (ADR-0056) is real-time but thin (~2–3% of volume) and dead off-hours → bar-based
signals, not microstructure. (3) **The sim tape is mean-reverting by construction** (ADR-0026), so
intraday results in sim are mechanism validation, not alpha. (4) Strategies no longer place orders —
they emit forecasts into fusion (ADR-0055). Doing nothing leaves one signal shape (window z-score) and
no genuinely intraday family.

## Decision

We will add intraday strategies as new `Strategy` SPI implementations — the same
detector→forecast→fusion→OOS path, **no new framework** — starting with **VWAP-deviation reversion**:
the signal is the z-scored deviation of price from a **session-anchored, volume-weighted VWAP**, faded
(rich vs VWAP → sell, cheap → buy), floored against dust and vol-adaptive like the existing detector. It
carries a session-open VWAP accumulator that resets at the ADR-0027 day boundary, uses the volume we
already model (ADR-0033), emits a Carver-scaled forecast (ADR-0055 `SourceForecasts`) — never an order —
and must clear the ADR-0049 OOS net-of-cost gate **per instrument** (the ADR-0043 selector extended to
rank it alongside momentum/mean-reversion) before it sizes anything. **Pairs/stat-arb is deferred** (below).

## Alternatives considered

- **Just tune the existing z-score faster (shorter window, lower threshold).** Rejected *as a family*:
  same signal at higher turnover — the 2026-07-19 post-mortem showed momentum already bleeds on this
  tape, and faster only pays more cost for no new edge. Kept as a live-tune lever (ADR-0052), not a strategy.
- **Pairs / stat-arb as the FIRST family.** Deferred, not rejected: highest value (market-neutral,
  genuinely different signal) but the most machinery — cointegration/spread estimation, a spread
  instrument, two-leg netting. Trigger: the VWAP family clears the OOS gate on *real* intraday data,
  proving the intraday path end-to-end.
- **True HFT / order-flow microstructure.** Rejected: needs paid SIP/L2 depth, sub-ms latency and a C++
  hot path; the free IEX feed can't support it on a dev box. Trigger: real market access + a depth feed.
- **A dedicated fast execution loop with its own order path.** Rejected: violates ADR-0055 (fusion is
  the sole order origin) — the family emits forecasts; fusion nets and executes.

## Consequences

- **Positive:** a genuinely new intraday signal (deviation-from-VWAP, not window momentum), naturally
  higher-frequency and economically motivated; reuses the whole OOS/cost/fusion/selection stack, so it's
  cheap to add and impossible to ship if it loses to cost.
- **Negative:** higher turnover means execution cost dominates — **many candidate configs will FAIL the
  net-of-cost gate** (intended, but expect few survivors); the session-anchored VWAP accumulator is new
  stateful code that resets at the day boundary — a fresh bug surface around open/holiday/roll; sim
  results are mechanism-only (mean-reverting tape), so real validation waits on accumulated real intraday
  history; the thin IEX feed bounds how intraday we can honestly go.
- **Follow-ups:** pairs/stat-arb (trigger above); passive/limit execution to cut turnover cost (ADR-0025
  refinement); extend the ADR-0043/0044 per-instrument selector to rank {momentum, mean-reversion,
  vwap-reversion}; a `/backtest.html` comparison including the new algo.

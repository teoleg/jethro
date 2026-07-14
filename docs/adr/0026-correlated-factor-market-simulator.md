# ADR-0026: High-fidelity market simulator — correlated cross-asset factor model with regime switching, calibrated from real history

- **Status:** Proposed
- **Date:** 2026-07-14
- **Deciders:** Oleg
- **Tags:** market-data, sim, quant, risk

## Context

The owner has no licensed market or reference data, so the simulator is the platform's
only always-available market — everything (strategy, risk, VaR, the AI loop's evaluation)
is only as truthful as the tape it runs on. Today's sim generates **independent** random
walks per instrument (uniform steps, no fat tails), with a shared regime that scales vol
but no cross-asset correlation except the curve-linked Treasury futures. Real markets do
not work that way: single names co-move with their index (β≈1), a risk-off day is stocks
↓ / bond futures ↑ / USD ↑ *simultaneously*, and 2022-style inflation shocks flip the
stock-bond correlation positive. Against uncorrelated tapes, portfolio risk measures
(VaR, diversification, hedges) are meaningless and strategies never face a correlated
drawdown. The sim must cover the **full real universe** — every equity (AAPL/MSFT/AMZN/
GOOG/SAP), index futures (ES/NQ), Treasury futures (ZT/ZF/ZN/ZB), swaps (USD_IRS_5Y/10Y),
FX (EURUSD/GBPUSD/USDJPY), and the SOFR/TSY curve tenors — with per-name realism, and it
must stay seeded-deterministic (ADR-0009) and offline-capable (CI has no network).

## Decision

We will replace independent per-instrument walks with a **seeded cross-asset factor
model**: global factors (EQUITY market, RATES level, RATES slope, USD) plus per-instrument
idiosyncratic terms. Each tick draws one correlated innovation vector from a
**regime-dependent covariance** (Cholesky of the configured correlation matrix) with
**Student-t innovations** (ν≈5, fat tails); instrument log-returns are
`rᵢ = Σ βᵢ,f · f + idioᵢ`. The RATES factors drive the existing curve simulator's level/
slope directly, so Treasury futures and swaps stay priced **from the curve**
(ADR-0014/0020) and rates instruments move in concert with everything else. Regimes follow
a seeded Markov chain — CALM, TREND_UP (steady bull), TREND_DOWN, **RISK_OFF** (equities↓,
yields↓ ⇒ bond futures↑, USD↑, correlations tighten), **INFLATION_SHOCK** (equities↓,
yields↑ — the 2022 pattern) — each with its own drift, vol multiple and correlation
overrides and realistic dwell times. Calibration (per-name annualized vols, betas, factor
correlations per regime, transition matrix) lives in a **checked-in JSON** curated from
long-run market statistics, refreshable by `scripts/calibrate_sim.py` from free daily
history (Stooq CSV, no API key) on a networked host — the platform itself never fetches at
runtime.

## Alternatives considered

**Replay real historical data as *the* sim.** Rejected as the only mode: one path per
history, so no unlimited seeded scenarios for multi-seed/walk-forward evaluation
(ADR-0027), plus refresh/licensing burden. Retained as a *backtest data source*.

**Per-instrument GARCH without factors.** Rejected: captures volatility clustering but not
cross-asset coherence — and coherence is precisely what's missing.

**Agent-based / order-book simulation.** Rejected: research-grade complexity; adds
microstructure the current strategies don't consume. Revisit only with microstructure
strategies (trigger: bid/ask strategies or impact modeling).

## Consequences

- Positive: portfolio-coherent tapes make VaR, hedges, and stress meaningful; strategies
  and the AI loop face realistic correlated drawdowns and regime shifts; unlimited seeded
  scenarios for out-of-sample evaluation; full-universe coverage with per-name calibration.
- Negative: still a model — no microstructure, idiosyncratic jumps are stylized,
  calibration goes stale until refreshed; more parameters to maintain; new tape breaks any
  test pinned to the old generator's exact path (tests recalibrate to the new seeds).
- Follow-ups: session calendar with overnight gaps + U-shaped intraday vol; bid/ask and
  volume/ADV emission (feeds ADR-0025 impact work); historical-replay adapter for
  backtests (ADR-0027).

# ADR-0051: Risk-off sizing from a price-derived volatility regime (no sim-regime oracle)

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** strategy, risk, sim-parity

## Context

Regime-aware sizing shrinks new-entry notional in a turbulent (risk-off) market. But the trading path
read the regime from **the simulator's own label** — `StrategyLifecycle` called `tradingCore.regime()`
(and the backtest `gen.regime()`) and mapped `VOLATILE/RISK_OFF/INFLATION_SHOCK → half size`. That
label is a **sim oracle**: `tradingCore.regime()` is *"non-null only in sim mode"* and returns `CALM`
on any live feed. So the risk-off shrink **only ever fired in sim** and was inert in production — the
model was *handed* the regime instead of sensing it, breaking sim=live=replay parity (invariant 8 /
ADR-0029), exactly the loophole ADR-0044 outlawed for algo *selection*, still open for *sizing*.

The system already senses volatility from prices elsewhere — vol-targeted sizing sets notional =
`riskBudgetDaily / σ_daily` off measured daily closes — so the label-driven scale was both leaky and
partly redundant. The trading path must **sense** the regime from market conditions, not read a
config/sim label.

## Decision

We will drive the risk-off sizing scale from a **deterministic, price-derived `VolatilityRegime`
detector**, and the trading path will **never** read `tradingCore.regime()` / `gen.regime()` for a
decision:

1. **Sensed from prices.** Per instrument, a sqrt-free relative-vol proxy — mean absolute step move
   over a rolling window ÷ current price (`(Σ|Δp|/N)/P`); the market reading is the mean across names
   with a full window, compared to its own slow **EWMA baseline**. `ELEVATED` when the ratio crosses
   the upper band, back to `CALM` under the lower band (**hysteresis**). Exact `BigDecimal`
   throughout (invariant 1); the proxy/ratio are dimensionless indicators, not money.
2. **Same computation everywhere.** The live strategy and the backtest use the *same* detector fed by
   the marks they observe, so risk-off sizing behaves identically in sim, live and replay. In sim the
   tape may still switch regimes (that's the lab) — the detector *senses* the resulting volatility; it
   never reads the label.
3. **The scale is unchanged, only its trigger.** `ELEVATED → jethro.strategy.regime-volatile-scale`
   (default half size); `CALM/UNKNOWN → 1×`. The sim's label stays sim-only (control panel /
   indicators / synthetic-news colouring) — display and content, never a trade decision.

## Alternatives considered

**Drop the risk-off scale entirely, rely on vol-targeting.** Vol-targeting already shrinks size as
measured σ rises, so the oracle could just be deleted. Reasonable and simplest, but it loses an
explicit, interpretable "stand down in turbulence" lever (and the ability to stand fully aside at
scale 0); kept the lever, sourced it from prices.

**Keep reading the sim label.** Zero work. Rejected — it only works in sim, is dead in production, and
is the parity-breaking oracle this ADR exists to remove.

**A full volatility model (GARCH/realized-vol term structure).** More faithful. Deferred — the
mean-absolute-move-vs-baseline proxy is cheap, allocation-light, and good enough to gate a sizing
scale; a richer estimator can replace it behind measured evidence.

## Consequences

- Positive: risk-off sizing works the same in sim and live (honest in production); no trading decision
  reads a sim oracle; consistent with ADR-0044's price-derived trend detector; the backtest now
  measures the same sizing behaviour the live path runs.
- Negative: a detector lags a genuine turn by its window and can sit near the band (hysteresis bounds
  but doesn't remove this); the window/factors/λ are a modelling choice to validate (mine, arbitrary),
  not a market convention; it partly overlaps vol-targeting (accepted — one is a continuous size,
  the other a regime gate).
- Follow-ups: expose the sensed regime + vol ratio on the UI/telemetry; validate the bands against
  measured OOS behaviour; a richer volatility estimator if evidence warrants; per-regime effectiveness
  telemetry (does the risk-off shrink earn its keep).

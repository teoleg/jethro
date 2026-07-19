# ADR-0043: Per-instrument strategy selection from out-of-sample results (no blind global algo)

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** strategy, evaluation, autonomy

## Context

The platform runs ONE signal algo globally (`jethro.strategy.algo` — momentum or mean-reversion for
every instrument). The 2026-07-19 post-mortem showed why that is wrong: momentum bled on the
mean-reverting sim tape (ALPHA −$1,494, every name losing) because it was applied blindly. Flipping
the global default to mean-reversion (shipped) only trades one blind default for another — a regime
or an instrument that trends would then bleed the mirror image. Momentum and mean-reversion cannot
both be right on the same instrument at the same horizon; which one is right is an empirical,
per-instrument question the platform already has the machinery to answer: `BacktestService`
runs multi-seed, cost-honest, out-of-sample backtests per algo and aggregates per instrument by
median (ADR-0027, `oosByInstrument`). It is measured but never fed back into what trades live.

## Decision

We will **select the signal algo per instrument from the OOS harness**, and trade nothing where
neither algo has a measured edge. A periodic selection job (default hourly, and at boot) runs
`oosByInstrument` for both `momentum` and `mean-reversion` over K disjoint seeds (K, ticks, cost
from the existing ADR-0027 config), and for each instrument picks the algo with the **higher median
net PnL**, requiring that median to be **> 0** — otherwise the instrument is **NO-TRADE** until a
later run earns it back. `StrategyLifecycle` reads this per-instrument map (algo + no-trade set);
an instrument absent from the map (not yet evaluated) falls back to the global `algo` default.
The selection is an event on the existing decision log (auditable), and the live/ADVISE panels show
each instrument's chosen algo and its OOS median so the call is legible, never a black box.

## Alternatives considered

**Keep a single global algo (status quo + the mean-reversion flip).** Simple, but provably wrong for
any instrument whose behaviour differs from the chosen default; the post-mortem is the evidence.
Rejected as the end state — kept only as the fallback for un-evaluated instruments.

**Run both algos as separate live sleeves and let P&L sort them out.** Doubles live trading cost to
learn what the OOS harness already measures without risking a cent, and muddies attribution.
Rejected — pay for the answer in simulation, not in the live book.

**Continuous per-tick algo switching.** Maximally adaptive, maximally overfit to noise, and churns
positions every time the estimate flaps. Rejected — the hourly cadence + the >0-median gate is the
hysteresis; a strategy edge does not change tick to tick.

**Blend/ensemble the two signals.** A weighted combination could in principle beat either; but the
weights are noise-sensitive and the per-trade rationale is opaque — the same objection as the
covariance-QP hedge overlay. Deferred behind a trigger: single-algo selection leaves measurable
edge on the table.

## Consequences

- Positive: each instrument trades the algo the evidence supports, or **does not trade** — directly
  attacks the post-mortem's biggest loss (momentum on a reverting tape) and the churn (no-edge names
  go quiet). Fully deterministic and auditable; reuses the ADR-0027 harness, no new backtest math.
- Negative: selection lags a genuine regime change by up to one cadence (a trender that flips to
  mean-reverting keeps the old algo for an hour) — bounded, and the firm breaker still backstops;
  more moving parts than one config line; OOS median > 0 in sim is necessary, not sufficient, for a
  real edge (sim ≠ market — the standing caveat).
- Follow-ups: make the cadence/K/ticks adaptive to how stable each instrument's selection is;
  fold the AI hypothesis sleeve's instruments into the same measured-edge discipline; revisit the
  ensemble behind its trigger.

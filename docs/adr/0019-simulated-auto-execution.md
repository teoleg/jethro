# ADR-0019: Simulated auto-execution — the deterministic strategy may auto-trade in sim, hard-gated off real brokers

- **Status:** Proposed
- **Date:** 2026-07-12
- **Deciders:** Oleg
- **Tags:** order, risk, ai, strategy

## Context

The toy strategy (step 8) proposes guardrailed suggestions that a human executes from the
ticket (ADR-0018). We want the option to let the strategy **execute automatically** —
to watch positions, risk, PnL and limit alarms evolve on their own, and to drive the
backtest loop (replay ticks → strategy → fills → measured PnL) without a human clicking.

This is only reasonable because execution today is **simulated**: `SimulatedExecutor`
fills against marks, there is no broker, and ADR-0015 forbids a real-money broker until
the order module is extracted to its own JVM. Auto-trading a *simulated* venue risks no
money. Two invariants still bind: the auto-trader is the **deterministic** strategy, not
a model (invariant 7 holds — no AI on the trade path), and it must still pass the
deterministic pre-trade guardrail (ADR-0018).

## Decision

We will allow the **deterministic strategy** to auto-submit orders through the normal
order path, behind `jethro.strategy.auto-execute` (**default OFF**), with a per-instrument
cooldown. Hard constraints:

1. **Simulated execution only.** Auto-execution is valid solely against `SimulatedExecutor`.
   Before any real-money broker is wired (ADR-0015), this flag must be removed or re-gated
   behind an explicit real-trading policy ADR — it must never ship enabled to a live venue.
2. **Guardrail always downstream.** Auto-orders go through `OrderService`, which re-runs
   the deterministic pre-trade guardrail; a breaching order is rejected, not filled.
3. **Deterministic source only.** Only the plain-code strategy may auto-execute. The
   AI/frontier tier's proposals remain human-in-loop (ADR-0018 unchanged) — a model output
   never auto-routes.
4. **Off by default, loud when on.** The flag defaults false; enabling it logs a startup
   banner naming it as simulated auto-trading.
5. **Cooldown + audit.** A per-instrument cooldown throttles submissions; the guardrail
   bounds accumulation at the book limit. Auto-orders are ordinary OrderEvents/fills — no
   special audit path, fully replayable.

## Alternatives considered

**Human-only execution (ADR-0018 as written).** Safest, but slow to see risk evolve and
gives the backtest loop no driver. Kept as the default; auto-execution is opt-in on top.

**Let the AI/frontier tier auto-execute too.** Rejected: puts a model output on the trade
path (invariant 7) with no human or real-trading controls — unacceptable even in sim as a
precedent. The deterministic/AI split is the point.

**A separate auto-trader service.** Overkill now; the strategy lifecycle submitting through
the existing order path reuses the guardrail and audit for free. Revisit at extraction time.

## Consequences

- Positive: positions build on their own in sim, so risk/PnL/exposure and limit-breach
  alarms become live without manual clicking; the backtest loop gains its execution driver;
  the guardrail and order path get exercised continuously.
- Negative: a new standing way to place orders — mitigated by default-off, sim-only scope,
  the guardrail, and the cooldown. The **must-re-gate-before-real-broker** constraint is a
  latent footgun if forgotten; hence this ADR and the startup banner.
- Follow-ups: when the order module is extracted for a real broker (ADR-0015), remove or
  re-gate this flag behind a real-trading-policy ADR; a kill-switch / max-orders-per-interval
  cap when auto-execution runs unattended for long periods.

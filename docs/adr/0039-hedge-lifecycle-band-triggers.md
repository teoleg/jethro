# ADR-0039: Hedge lifecycle — deterministic band triggers with hysteresis, advisory-first

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** risk, hedging, execution, ai

## Context

ADR-0038 fixes *what* hedges *what* and at *which ratio*. The remaining decision is *when*:
hedge too eagerly and the ADR-0025 cost model eats the book (every rebalance crosses a
spread and pays impact); hedge too lazily and the exposure the hedge exists for is realized
before the hedge is on. The trigger must be deterministic (invariant 7 — a protective
control can never wait on a model), idempotent under at-least-once delivery (invariant 6),
and must not fight the existing autonomy machinery (ADR-0019 sim gate, ADR-0022 envelope,
ADR-0027 firm breaker).

## Decision

We will rebalance hedges on **exposure bands with hysteresis**, per book and per exposure
axis (systematic-equity USD, per-bucket DV01, non-USD net):

1. **Bands.** Each axis has a configured cap. The advisor acts when |exposure| crosses
   **100%** of cap, hedging back to **50%** (not zero — the half-hedge leaves room to
   drift both ways); an existing hedge is unwound when the underlying exposure falls below
   **40%**. Enter (100) and exit (40) deliberately differ so the hedger cannot flap.
2. **Churn guards.** Per-axis cooldown (default 60 s) and minimum hedge notional (default
   $10k) — below either, the advisor stays quiet. All dials are per-book config.
3. **Advisory-first.** A triggered hedge is a **deterministic attention item** (ADR-0017
   floor — no model may suppress it) carrying instrument, side, exact quantity, expected
   variance reduction (`ρ²`), and a one-click order. **Auto-execution** of hedge orders is
   permitted only under the ADR-0019 sim gate and the ADR-0022 risk envelope, like any
   auto order.
4. **Breaker interaction.** When the ADR-0027 firm breaker is tripped, auto-hedging
   suspends with everything else; proposals keep firing at CRITICAL severity and manual
   execution stays open. Rationale: a tripped breaker means the automated layer has lost
   credibility — auto-hedging into an incident (e.g. off a broken feed) is how the hedge
   becomes the next incident.
5. **Execution & audit.** Hedge orders are ordinary orders (idempotency key
   `hedge:<book>:<axis>:<epoch>` — re-delivery cannot double-hedge; the ADV slicer and
   pre-trade gate apply unchanged). Every proposal, execution, and unwind is an event with
   the measured inputs (β̂/DV01, exposure, band state) — replayable arithmetic, not vibes.
6. **AI placement.** The hypothesis layer (ADR-0022) receives band state and proposals as
   structured facts and may narrate or propose *earlier* hedging as a suggestion; it can
   never veto, delay, or resize the deterministic floor (invariant 7).

## Alternatives considered

**Continuous rebalancing (re-hedge on every mark).** Minimizes tracking error, maximizes
cost: at ~1 Hz marks the spread/impact bill dwarfs the variance saved. Rejected — bands are
the standard practical answer (same intuition as options-desk delta bands).

**Static hedge at position entry, never rebalanced.** Cheapest, but β̂ and the position
drift, and exposure from *accumulated* positions is never caught. Rejected as the sole
policy; the band trigger naturally hedges at entry when the entry itself breaches the band.

**VaR-threshold trigger only** ("hedge when VaR95 > cap"). Couples the trigger to a slow,
daily-return estimate and hides *which* axis breached. VaR stays the monitor and backstop;
the acting triggers are the directly-measured axis exposures.

**Model-decided timing.** The SLM/frontier tier decides when to protect. Rejected outright:
invariant 7 — guardrails are deterministic code; the model proposes, code disposes.

## Consequences

- Positive: bounded hedging costs (hysteresis + cooldown put a hard floor under churn);
  every hedge decision is reproducible from persisted inputs; the attention feed gains
  a deterministic "you are unhedged" floor that no model mood can hide.
- Negative: inside the band the book runs deliberately unhedged (up to the cap — that is
  the accepted cost of not churning); band caps are new per-book config to keep honest;
  a fast breach between cooldown ticks is caught one tick late.
- Follow-ups: hedge-effectiveness telemetry (promised `ρ²` vs realized variance reduction,
  alert on decay); hedge P&L shown as its own line so strategy vs hedging performance
  never blur (extends ADR-0037's clean-P&L discipline); ADR-0038 follow-ups apply.

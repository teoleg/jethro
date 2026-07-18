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

We will rebalance hedges on a **target-flat deadband**, per book and per exposure axis
(systematic-equity USD, per-bucket DV01, non-USD net):

1. **Deadband, target flat.** Per axis with cap `C`: let `e` = the strategy's (unhedged)
   exposure, `h` = the current hedge, `net = e + h`. The advisor acts when **|net| > C**,
   sizing a hedge trade that brings `net → 0` (fully hedge the axis, ADR-0038 ratio). The
   full-cap-width deadband **is** the hysteresis: after flattening, `net` must travel a
   whole `C` in either direction to re-trigger, so the hedger cannot flap at the boundary
   and — because drift-to-retrigger grows with the square of the distance — target-flat
   churns *less* and trades ~half the daily notional of a hedge-to-half policy (fewer,
   larger trades; cheaper under the ADR-0025 spread/impact bill). Additionally, if `|e|`
   (the underlying the hedge exists for) falls below the min-notional floor, any residual
   hedge is **unwound** — no underlying, no hedge, so a fully-exited strategy never leaves
   a naked proxy leg lingering. Target-flat is a per-book dial (a beta-harvesting book can
   set a non-zero target later); flat is the default because it minimizes churn and gives
   the cleanest strategy-vs-hedge P&L attribution.
2. **Churn guards.** Per-axis cooldown (default 60 s) and minimum hedge notional (default
   $10k) — below either, the advisor stays quiet. All dials are per-book config.
3. **Mode ladder — OFF → ADVISE → AUTO, per book.** OFF: no hedging. ADVISE: triggered
   hedges surface as **deterministic attention items** (ADR-0017 floor — no model may
   suppress them) carrying instrument, side, exact quantity, expected variance reduction
   (`ρ²`), and a one-click order; a human executes. AUTO: the advisor also *submits* the
   hedge order itself — permitted only under the ADR-0019 sim gate and the ADR-0022 risk
   envelope, exactly like any other auto order (hard-off against real brokers regardless of
   the dial).
4. **Breaker interaction — one-shot de-risk, then freeze.** When the ADR-0027 firm breaker
   trips, an AUTO book fires **one deterministic protect action**: hedge every axis to flat
   immediately, then auto freezes — all further rebalancing needs a human until operator
   reset. The one-shot is bounded (its size is the known current exposure) and cannot loop,
   so a bad hedge can never chase its own consequences. Any axis whose proxy mark is stale
   or quarantined (ADR mark-jump guard) is **skipped and escalated at CRITICAL**, never
   traded on suspect data — that is the trap (flattening off a broken feed makes the hedge
   the second incident) fenced off. Proposals keep firing at CRITICAL and manual execution
   stays open throughout.
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

- Positive: bounded hedging costs (the full-width deadband + cooldown put a hard floor
  under churn, and target-flat trades fewer/larger legs than any partial target); every
  hedge decision — including the breaker one-shot — is reproducible from persisted inputs;
  the attention feed gains a deterministic "you are unhedged" floor no model mood can hide.
- Negative: inside the deadband the book runs deliberately unhedged (up to the cap — the
  accepted cost of not churning; average unhedged exposure ≈ half the cap over the
  sawtooth); axis caps and the OFF/ADVISE/AUTO mode are new per-book config to keep honest;
  a fast breach between cooldown ticks is caught one tick late.
- Follow-ups: hedge-effectiveness telemetry (promised `ρ²` vs realized variance reduction,
  alert on decay); hedge P&L shown as its own line so strategy vs hedging performance
  never blur (extends ADR-0037's clean-P&L discipline); ADR-0038 follow-ups apply.

# ADR-0039: Hedge lifecycle — always-flat deterministic hedging, advisory-first

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

We will keep every book **always hedged to flat**, per book and per exposure axis
(systematic-equity USD, per-bucket DV01, non-USD net):

1. **Always target flat — no exposure band.** Per axis: let `e` = the strategy's (unhedged)
   exposure, `h` = the current hedge, `net = e + h`. The advisor acts whenever `net` is
   non-zero (above a small anti-churn floor, item 2), sizing a hedge trade that brings
   `net → 0` (fully hedge the axis, ADR-0038 ratio). A live book always carries a hedge —
   there is **no exposure deadband inside which it runs deliberately unhedged**; that was
   rejected as a real-money risk (average unhedged exposure ≈ half a cap is exactly the
   exposure the hedge exists to remove). Churn is controlled at the *trade* level, not by
   leaving exposure open (item 2). If `|e|` (the underlying the hedge exists for) falls to
   zero, any residual hedge is **unwound** — no underlying, no hedge, so a fully-exited
   strategy never leaves a naked proxy leg lingering. Target-flat is a per-book dial (a
   beta-harvesting book can set a non-zero target later); flat is the default.
2. **Churn guards — on the trade, not the exposure.** Per-axis cooldown (default 60 s), a
   minimum hedge notional (default $10k — a hedge trade smaller than this is not worth its
   spread/impact under ADR-0025), and a small rebalance floor on `|net|` (default $0 =
   always hedge; raise it per book only to damp sub-noise re-hedging). Below any of these
   the advisor stays quiet. All dials are per-book config, and none of them lets net
   exposure sit open above the min-notional the market can actually trade.
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

- Positive: the book is always hedged — no window of deliberate naked exposure; churn is
  still bounded (cooldown + min-notional put a hard floor under trade frequency, and the
  hedge is only ever a whole, tradeable clip); every hedge decision — including the breaker
  one-shot — is reproducible from persisted inputs; the attention feed gains a deterministic
  "you are unhedged" floor no model mood can hide.
- Negative: hedging to flat trades more often than a wide-band policy would (the accepted
  cost of always being hedged — bounded by cooldown + min-notional, and cheap while the
  proxy is liquid); a residual up to the min-notional clip is left unhedged because it can't
  be traded smaller; the OFF/ADVISE/AUTO mode and the churn dials are new per-book config to
  keep honest; a fast move between cooldown ticks is hedged one tick late.
- Follow-ups: hedge-effectiveness telemetry (promised `ρ²` vs realized variance reduction,
  alert on decay); hedge P&L shown as its own line so strategy vs hedging performance
  never blur (extends ADR-0037's clean-P&L discipline); ADR-0038 follow-ups apply.

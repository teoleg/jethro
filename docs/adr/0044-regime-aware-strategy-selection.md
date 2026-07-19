# ADR-0044: Regime-aware strategy selection from a price-derived trend detector (no regime oracle)

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** strategy, evaluation, sim-parity

## Context

ADR-0043 selects momentum vs mean-reversion per instrument from the OOS harness — but it measures
on fresh sim seeds disjoint from the live tape, so it picks each algo's edge *averaged across all
regimes*. Re-running it converges to the same average answer; it **cannot switch when the live
regime turns** (a range that starts trending keeps the mean-reversion pick and bleeds, and vice
versa — exactly the +$230→−$2,072 whipsaw the post-mortem showed). The obvious shortcut — read the
sim's internal regime label (CALM/RISK_OFF/…) — is **rejected outright**: that label is an oracle
that does not exist in production, so using it would work in sim and silently break on real data,
violating sim=prod parity (invariant 8 / ADR-0029). The regime must be *inferred from observable
prices*, the identical computation in sim, live, and replay — the way a real desk actually does it.

## Decision

We will drive per-instrument algo selection from a **deterministic, price-derived trend detector**,
with the ADR-0043 measured-edge selector kept as the gate:

1. **Per-instrument trend strength** from that instrument's own price window — an efficiency-ratio
   (net move ÷ Σ|step moves|; ~1 trend, ~0 chop) and/or variance-ratio / return-autocorrelation
   read. Trending → momentum; choppy/mean-reverting → mean-reversion. No external input.
2. **Cross-sectional breadth** — the same score aggregated across the WHOLE universe: a market-wide
   "how trending is the tape" reading (real market breadth, computed, not looked up). It overlays
   the per-instrument call for the market regime; names still diverge (one trends while others chop).
3. **Hysteresis** — a regime flip requires the score to cross a band (not a single tick), so the
   detector doesn't whipsaw at boundaries.
4. **Measured-edge gate stays** — the OOS/walk-forward selector still says "even the trend-matched
   algo has no positive median after costs → NO-TRADE", so a detector false-positive can't force a
   losing trade. The detector decides *which* algo and *when to switch*; the gate decides *whether
   there's edge at all*.

No sim internals are read anywhere in this path (invariant 8).

## Alternatives considered

**Seed-average selection only (ADR-0043).** Regime-blind — the whole problem. Kept as the edge gate,
rejected as the thing that decides which algo.

**Read the sim's regime label.** Trivial and accurate in sim, but a production oracle that doesn't
exist — breaks sim=prod parity the moment real data replaces the sim. Rejected outright.

**Walk-forward on the recent *live* tape** instead of a detector. Adaptive and measured, but lags
the turn by its window and is heavier. Complementary — it becomes the measured-edge validator that
gates the detector; deferred as the richer gate, not the fast switch.

**Continuous per-tick algo switching.** Maximally adaptive, maximally overfit to noise, churns
positions. Rejected — the hysteresis band is the disciplined answer.

## Consequences

- Positive: the strategy switches when the regime *actually* turns, inferred from prices alone —
  honest in production, and it directly attacks the whipsaw that bled the book; the edge gate keeps
  a noisy detector from trading into a loss.
- Negative: a detector is an estimate — it lags a genuine turn by its window and can flap at regime
  boundaries (hysteresis bounds but doesn't eliminate this); more strategy state to keep honest;
  the trend metrics themselves are a modelling choice to validate (efficiency ratio vs variance
  ratio) against measured OOS behaviour, not asserted.
- Follow-ups: the walk-forward-on-live-tape gate; per-regime effectiveness telemetry (does the
  trend-matched algo actually earn its keep); fold the news overlay (ADR-0045) on top as context.

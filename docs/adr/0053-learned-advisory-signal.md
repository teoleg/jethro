# ADR-0053: Learned advisory signal — a model predicts a tradeable label, backtest-gated (no price-curve oracle)

- **Status:** Proposed
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** ai, strategy, data, risk

## Context

We now have several real feeds — ticks/marks, the news+social corroboration pipeline (ADR-0050),
volume/ADV, order-flow — and a recurring question: can a model learn patterns from them to anticipate
how price behaves? The honest finance answer bounds the ambition. Short-horizon prices are close to a
martingale (weak-form efficiency); any edge is small (think 51–53% net hit-rate, not 70%), regime-
dependent, and decays as it is used. The three ways such a project quietly fails are look-ahead
leakage, overfitting, and non-stationarity — each makes a backtest look brilliant and live trading
bleed (we already lived one: momentum bled on a mean-reverting tape).

Two platform constraints are non-negotiable and actually make this safe to attempt: a model output is
**never** a number that sizes a position or feeds PnL/risk (ADR-0016, invariant 1), and AI never sits
on the tick path or originates an order — every thesis is gated by the deterministic OOS backtest
(ADR-0049) and surfaced as advisory (ADR-0022). A learned signal must live entirely inside that
envelope. And critically: a model trained on the **sim** learns the sim's generator (ADR-0026), not
markets — the sim is for plumbing and validation, never ground truth.

## Decision

We will build a **learned advisory signal** that predicts a single **well-defined, cost-aware,
tradeable label** — e.g. `P(next-N-bar return ≥ +k·spread, net of fees)` (thresholded direction), or a
regime/vol class — **not** a price path or a number. It enters the system as one more signal source
behind the existing gates: model **proposes** a categorical/probabilistic signal → the deterministic
quant layer sizes it → the **ADR-0049 multi-seed OOS backtest hard-gate** admits or vetoes → it
surfaces on the attention feed; every decision is an event on `ai.decisions`. It never touches the
tick path (invariant 7) and no model number ever reaches positions/PnL/risk (ADR-0016).

Validation protocol is part of the decision (this is where the edge is won or lost): **real history
only** for ground truth; **purged, embargoed walk-forward CV** (no leakage across the label horizon);
**transaction-cost-aware** scoring using our spread/fee/TCA model; and it must beat two baselines OOS —
a coin-flip after costs AND our existing momentum/mean-reversion — or it does not ship. Start with a
**simple model** (gradient-boosted trees / logistic regression on engineered features: momentum,
realized vol, volume/ADV, news+social corroboration, order-flow imbalance). Deep sequence models
(LSTM/transformer) are deferred behind measured evidence the simple model has stable edge.

## Alternatives considered

- **Predict the price curve directly (regression on the path).** The intuitive ask, and rejected: it
  maximises look-ahead/overfitting exposure, has no natural cost-aware success metric, and forecasting
  a path is far harder than a thresholded label. We predict a decision, not a trajectory.
- **Deep sequence model first (LSTM/transformer over tick windows).** Most expressive; deferred, not
  rejected. It overfits financial data spectacularly and is hard to interpret/gate — revive only once a
  simple, cost-honest baseline shows durable OOS edge (the stated trigger).
- **Train/validate on the sim.** Zero data-sourcing work; rejected as ground truth — a model would
  learn the ADR-0026 generator and look great in-sim, dead live. Sim stays for pipeline tests only.
- **Frontier LLM reads the tape and predicts.** Reuses ADR-0010/0022; complementary, not this. An LLM
  narrates/triages (ADR-0016 — never parsed for a number into risk); a numeric label wants a calibrated
  supervised model with a proper loss and CV, not a text model.

## Consequences

- **Positive:** a measurable, cost-aware signal that composes with the deterministic strategy instead of
  replacing it; the feed we built (esp. corroborated event signals, plausibly less efficient than pure
  technicals) gets a principled consumer; all inside the existing risk envelope, so downside is bounded.
- **Negative:** real cost and risk of self-deception — the likeliest honest outcome is a *small* edge on
  event-driven names and *no* durable edge on pure price patterns; a training/feature pipeline, a
  label/feature store, and model artifacts are new operational surface (versioning, drift, retraining);
  a mis-specified label or leaky CV can manufacture a phantom edge that the OOS gate may not fully catch.
- **Follow-ups:** the offline feature-store + labeler (the boring 80%); model registry + drift/decay
  monitoring; per-regime effectiveness telemetry (does the signal earn its keep, cf. ADR-0044/0051);
  a superseding ADR if/when a deep model clears the trigger.

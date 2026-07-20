# ADR-0055: Signal fusion and a target portfolio — signals stop placing orders

- **Status:** Proposed
- **Date:** 2026-07-20
- **Deciders:** Oleg
- **Tags:** strategy, ai, risk, architecture

## Context

Jethro has grown many signal sources — deterministic momentum/mean-reversion (ADR-0043/44), AI
hypotheses (ADR-0022), corroborated news/social (ADR-0045/50), a learned advisory label (ADR-0053),
regime posture (ADR-0051), and a hedger (ADR-0038/39) — and several can act autonomously. Each decides
alone: strategy auto-exec, hypothesis autonomy, and (planned) hedge AUTO each submit orders directly.
ADR-0054 stopped same-event repeats *within* the hypothesis layer, but nothing reconciles *across*
sources: two subsystems can trade the same name independently, or against each other, paying spread
both ways. The institutional reference model (see `docs/architecture/institutional-decision-flow.md`)
is unanimous on the fix and it is exactly Oleg's ask — combine all sources **before** any order
decision: signals emit *forecasts*, forecasts combine into one number per instrument, that becomes a
*target position*, and orders are only the netted **delta** between the current book and the target
(Carver 2015; Grinold & Kahn; Gârleanu & Pedersen 2013; López de Prado meta-labeling as the ML form).

Doing nothing means every new source multiplies the coordination problem (N² pairwise dedup) and the
duplicate/wash-trade bug class recurs. All hard invariants stay binding: sizing is deterministic code,
no model number reaches PnL/risk (ADR-0016 / invariant 7), the OOS backtest is a hard order gate
(ADR-0049), autonomy is sim-only (ADR-0019).

## Decision

We will build a single **fusion layer** that all autonomous order flow routes through, and **retire
direct order submission from individual signal subsystems**. Concretely:

1. **Common forecast scale.** Every source is normalised to a dimensionless, capped forecast in
   **[−20, +20] with expected absolute value ≈ 10** (Carver's published convention — a cited market
   practice, not an invented number). Ordinal sources map coarsely (e.g. LOW/MED/HIGH conviction →
   ±5/±10/±15); the ADR-0053 label contributes only if its walk-forward gate says *ships*.
2. **Combination.** Per instrument: weighted average of forecasts × a diversification multiplier
   capped at 2.5 (Carver). Weights start **equal — a stated PLACEHOLDER, Oleg to revise on evidence**,
   re-estimated from per-signal telemetry (below) and the ADR-0053 harness; never hand-tuned silently.
3. **Target position.** Combined forecast × the existing deterministic vol-target scalar (ADR-0027
   vol targeting) → a target position per instrument per book. Regime posture (ADR-0051) scales the
   target down in risk-off; it is a multiplier on size, never a direction source.
4. **Partial adjustment + netting.** Trade only a fraction toward the target with a no-trade buffer
   (Gârleanu-Pedersen shape; buffer width `PLACEHOLDER — Oleg to set`), and net all sleeves' targets
   per instrument first — one order stream, so cross-sleeve churn is structurally impossible.
5. **Gates unchanged.** Delta orders pass the existing chain: ADR-0049 deterministic backtest gate,
   the autonomy envelope + cooldowns, firm breaker (ADR-0027), sim-only execution (ADR-0019).
6. **Per-signal health telemetry** (required by 2): rolling hit-rate / IC per source, persisted and
   surfaced, so weights are moved by evidence — and a decayed source is down-weighted, not debated.
7. **Execution scheduling stays deferred.** Almgren-Chriss parent/child slicing is out of scope until
   order sizes become a meaningful fraction of ADV or a real broker connection lands (ADR-0015 seam);
   the ADR-0025/0033 impact model already penalises size in sim.

## Alternatives considered

- **Keep independent subsystems, add pairwise coordination/dedup.** Rejected: N² interactions, and it
  preserves the bug class ADR-0054 just fought; netting never falls out of it.
- **Full mean-variance optimiser (Grinold-Kahn/Markowitz).** Deferred, not rejected: strictly more
  powerful, but needs alpha and covariance estimates at a quality we cannot yet evidence, and a solver
  where Carver's algebra is auditable line-by-line. Trigger: measured evidence the linear rule leaves
  material risk-adjusted return on the table.
- **ML combiner (meta-labeling) as the fusion function now.** Deferred: the right end-state for the
  gate/size step, but training a secondary model before per-signal telemetry exists would mean
  invented weights with extra steps. Trigger: telemetry live + ADR-0053 harness validates it OOS.
- **Fusion for prioritisation only; subsystems keep their own orders.** Rejected: preserves duplicate
  orders and cross-sleeve churn — the problem statement.

## Consequences

- **Positive:** one decision path (auditable end to end), duplicate orders and wash trades become
  structurally impossible, every source's contribution is measured, new sources plug in as one more
  forecast column, and the architecture matches the published institutional shape.
- **Negative:** a significant refactor — strategy auto-exec, hypothesis autonomy, and the hedge
  engine's order paths must be rerouted through the layer (the hedge advisor becomes an exposure-target
  contributor, reshaping parts of ADR-0038/39); the fusion layer is a single choke point where a bad
  config damps *all* flow; equal starting weights will likely underperform the best single source
  until evidence accumulates — accepted as the price of honesty.
- **Follow-ups:** phased build (telemetry → forecast normalisation → fusion/target/netting → reroute
  autonomy → retire direct paths); a superseding ADR if/when the optimiser or ML combiner clears its
  trigger; UI panel showing per-source forecasts, weights, and the combined target vs actual book.

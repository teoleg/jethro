# ADR-0062: Gate execution on positive *measured live* edge, not only sim-OOS

- **Status:** Proposed
- **Date:** 2026-07-24
- **Deciders:** Oleg
- **Tags:** strategy, risk, execution, ai

## Context

The order path is gated by a **simulator** out-of-sample backtest (ADR-0027/0049/0059): a name trades only
if a strategy showed positive median edge on multi-seed **sim** paths. The 2026-07-24 live Alpaca
post-mortem (`docs/reviews/2026-07-24-live-alpaca-postmortem.md`) showed that gate is **necessary but not
sufficient**: the sim medians for mean-reversion were positive, yet the **measured live** per-signal
returns were **negative** — mean-reversion −21.8 bps, momentum −104.6 bps — while the book turned over
~900 fills/day. The firm P&L only looked flat because a directional hedge won on an up day (see attribution).

This is the textbook **sim→live overfitting gap**: selecting the best rule on in-sample (sim) paths inflates
expected performance, and out-of-sample (live) reverts toward — or below — zero (Bailey, Borwein, López de
Prado & Zhu, "The Probability of Backtest Overfitting," *J. Comp. Finance* 2017; Harvey, Liu & Zhu, "…and
the Cross-Section of Expected Returns," *RFS* 2016 on the multiple-testing haircut; Lo, "The Adaptive
Markets Hypothesis," *JPM* 2004 on regime/time-varying edge). By Grinold's **Fundamental Law**
(IR ≈ IC·√breadth, *JPM* 1989), routing a *negative*-IC signal across high breadth scales the **loss** — so
"trade more" is exactly wrong here. The platform already **measures** live per-signal edge
(`signals_telemetry`, ADR-0055 phase 1) but does **not** gate routing on it.

## Decision

We will **add a live-edge gate to the sole order origin (fusion, ADR-0055/0059)**: a `(name, algo)` may be
routed only when its **measured live** edge — cost-adjusted per-signal expectancy over a rolling window,
from the existing signal telemetry — is **positive with a significance margin** (a minimum resolved-sample
count and a t-stat / Deflated-Sharpe-style hurdle, not a raw positive average). The sim-OOS gate remains as
an additional necessary condition (both must pass). When no `(name, algo)` clears the live gate, the correct
book is **flat**. Thresholds are owner-set and start conservative.

## Alternatives considered

- **Keep the sim-OOS gate as the only edge test.** Rejected — the post-mortem is direct evidence it admits
  live-negative-edge names; a simulator is an in-sample device (López de Prado 2018), not proof of live edge.
- **Raise sim rigor instead (more seeds / Deflated Sharpe on sim).** Deferred/insufficient alone: it hardens
  the *in-sample* test but still cannot certify *live* performance; do both, but the live gate is the
  binding one.
- **Size down rather than gate off.** Rejected as the primary lever: with negative IC, smaller size still
  loses (Fundamental Law); a gate to flat is the honest response, sizing is secondary.
- **Trust the AI/social sleeve to compensate.** Rejected: social had n=6 (not significant), and invariant 7
  forbids a model number sizing risk regardless.

## Consequences

- **Positive:** the platform stops trading signals that are measurably losing money live; turns the
  existing (currently ignored) live telemetry into a control; converts the paper-live phase (ADR-0061) into
  a genuine edge filter before any real capital.
- **Negative:** with today's data both deterministic algos fail the gate, so the book would trade **little
  or nothing** live until an algo earns positive live edge — correct, but it means "no trades" is the
  expected near-term state, not a bug; the significance hurdle needs a rolling-window + minimum-sample
  design so it neither over-fits to a lucky streak nor never fires.
- **Follow-ups:** recalibrate the turnover controls that already exist for the churn half of the problem
  (ADR-0055 no-trade band width, ADR-0059 conviction floor) against live cost; a per-regime live-edge view
  once the intraday regime-detector question (post-mortem §E) is resolved.

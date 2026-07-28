# ADR-0122: Exploration mode for the paper book — disable the edge gate so the models actually act

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** trading, fusion, risk, loop, operating-policy

## Context

The book has been **dormant** — `$0` gross, total PnL frozen at `$0.61` for six consecutive loop runs.
The cause is unambiguous in the live telemetry: `edgeGate.mayIncrease: false`. Every name is routed with
`deltaQty: 0` — the designed reduce-only behaviour of the ADR-0064 edge gate, which holds the WHOLE desk
in reduce-only until some source's measured expectancy beats measured execution cost **at ~97.7%
confidence**, read against Student's t on `cohorts − 1` degrees of freedom (ADR-0081).

On this universe that hurdle is structurally unbeatable in any reasonable time: a cross-sectional source
emits one cohort per measurement horizon, so `cohorts` sits at 2–3 for hours, where the Student-t hurdle
is effectively `t ≈ 4.5–14`, and no current source even has positive mean expectancy (`trend` measures
negative, `xsreversion` early-negative, `reversion` weakly positive but nowhere near significant).
**Re-weighting or adding sources cannot fix this** — they all sit behind the same gate. The recent ledger
is a wall of INCONCLUSIVE for exactly that reason.

On **real capital** the edge gate is correct discipline: don't pay turnover for unproven edge (Grinold's
Fundamental Law — spreading a zero/negative IC across breadth scales the *loss*). But Jethro is a **paper
book**, and the owner's stated purpose (2026-07-28) is to **watch the models act and perfect them** —
"the goal is not to stay dormant… it is all paper… perfect the models." A gate whose whole job is to
*suppress trading until edge is proven* is the wrong setting for a model-development book: it guarantees
there is nothing to watch, measure, or improve.

## Decision

**Set `jethro.fusion.edge-gate.enabled=false`** (exploration mode). The combined forecast now drives
positions directly. The code path supports this cleanly — `gateSupplier=null` leaves **every
deterministic floor standing**:

- **Conviction floor** (`jethro.fusion.min-forecast-to-route=5.0`) — only meaningful model views trade,
  not every wiggle, so this is high-conviction exploration, not noise-churn.
- **No-trade band** + partial-adjustment (ADR-0055/0080) — cost-aware position changes.
- **Firm & per-book exposure caps** — a HARD pre-trade block (ADR-0018), now monitored with live
  headroom against the caps (2026-07-28 loop change).
- **Firm-drawdown circuit breaker** (ADR-0027) — halts all auto-execution at the configured drawdown.
- **Evidence-based scorer** (ADR-0116) — auto-reverts any change that significantly drops risk-adjusted
  PnL over its window. If exploration mode itself bleeds PnL, the loop reverts *this* change.
- **Reduce-only always allowed** — the book can always flatten.

So the owner's two guardrails — **don't drop PnL, don't let exposure get to ridiculous numbers** — remain
enforced and monitored. Only the "prove edge before acting" suppressor is off. This is not an invented
money number; `min-sample`/`t-hurdle` are retained unchanged (they still gate the per-source combination
weights via `TelemetryWeights`) for when the gate is re-enabled.

## Consequences

- **Intended:** the book comes off dormant and puts on bounded, vol-targeted, high-conviction positions —
  visible actions the owner can watch and the loop can measure and improve. Real telemetry replaces a
  frozen screen.
- **Honest limitation — early actions are EXPLORATORY, not proven edge.** They are the models' *views*
  under live measurement, not signals that have cleared an OOS gate. Some will be noise and will pay
  spread. The scorer, caps and breaker bound the downside; the paper book bears the rest. This is
  deliberately an experiment, not a claim of edge.
- **The durable fix still stands:** build a signal with real, OOS-validated edge (diversified daily
  cross-sectional / time-series momentum through the ADR-0049 backtest) so the actions become *justified*
  rather than exploratory. Exploration mode buys visible activity now; validated edge is what earns it.
- **Possible next blocker:** with the edge gate off, `FusionExecutor.backtestSupported` (ADR-0049/0059)
  is the next gate a risk-adding order meets — a name the OOS selector marks NO-TRADE is still vetoed.
  If the book stays flat after this change, that gate (and the daily-history it needs) is the next target.
- **Fully reversible:** `jethro.fusion.edge-gate.enabled=true` restores ADR-0064 discipline exactly.

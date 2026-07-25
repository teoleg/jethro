# ADR-0065 — The target book spans held positions, and reducing risk needs no conviction

- **Status:** Proposed
- **Date:** 2026-07-25
- **Deciders:** continuous-improvement loop (ADR-0063); Oleg to ratify or reverse
- **Related:** ADR-0055 (fusion = sole order origin), ADR-0059 (fusion execution gate / conviction
  floor), ADR-0049 (backtest-support gate), ADR-0064 (cost-aware edge gate), ADR-0019 (hedger)

## Context

ADR-0055 made the fusion layer the sole order origin, and ADR-0059 stood the deterministic strategy
loop down to a *forecast source*. That handover moved entries to fusion. It did **not** move exits —
and nobody noticed, because an exit is invisible until it fails to happen.

Three facts compose into the bug:

1. `FusionPlanner.plan` iterated `ForecastRegistry.byInstrument(now)` — the cross-section of names
   with a **fresh** forecast. A name with no live view produced no target, so no delta, so no order.
2. `StrategyLifecycle.manageOpenPositions` — the stop-loss / take-profit / book-over-loss-cap
   de-risk pass — runs only when `autoExecuting()`, which is `false` whenever fusion routing is
   active. Correct as a de-duplication of order origins; but it retired the desk's only exit
   machinery and fusion never picked it up.
3. A forecast goes silent for ordinary reasons: the signal decays below threshold, or the ADR-0049
   OOS selector reclassifies the name as having no positive edge and the strategy stops emitting on
   it at all.

So the lifecycle of a position was: **opened on a view; orphaned when the view died; held forever.**
No stop, no take-profit, no target, no de-risk backstop, nothing that would ever revisit it. The
position most likely to be orphaned is the one whose signal stopped working — which is not a random
sample of the book.

The live book showed exactly this signature. The strategy book's two largest positions had no fresh
forecast and were its two largest losers; five of its nine names had been dropped by the OOS selector
as having no positive edge and were still held; and its realised P&L was deeply negative against a
large positive unrealised — the arithmetic fingerprint of closing names that still have a live signal
while accumulating the ones that don't. (Figures are in `reports/improvement-ledger.md` and the
scorer's snapshots — measured by `scripts/score-change.py`, never asserted here.)

Two controls then made it unfixable from the outside:

- The **ADR-0059 conviction floor** skips any delta whose combined forecast is weaker than
  `min-forecast-to-route`. An orphan's forecast is *zero*. The floor is what would block its exit.
- The **ADR-0049 backtest-support gate** vetoes any name the OOS selector has not blessed. A name it
  dropped is precisely one the desk wants out of; the gate would refuse to let it go.

Both are sound gates asking the same question — *is this view worth putting risk on?* — applied,
wrongly, to a trade that takes risk **off**.

## Decision

**1. The target book spans `{names with a view} ∪ {names we hold}`.** `FusionPlanner.plan` takes the
held set and plans every name in it. A held name with no fresh forecast has a combined forecast of
zero, hence — through the existing, unchanged `TargetPlanner.targetQuantity` — a target of **flat**.
No view, no position. It is then worked down by the same Gârleanu-Pedersen partial adjustment that
moves the book toward every other target.

*No new dial.* The unwind rate is the existing `jethro.fusion.adjustment-rate`; the no-trade band
around a zero target is zero by construction (already documented in `TargetPlanner`); sizing, the
guardrail and the breaker are untouched. This ADR introduces no number that gates money.

**2. The held set excludes the hedge book.** A hedge position is not a view — it is the ADR-0019
hedger's own target against the strategy books' residual. Fusion routes by asset class
(`StrategyProperties.bookFor`), so "unwinding" a hedge leg would open an offsetting leg in a
*strategy* book: two positions where there was one, gross exposure up, and the two loops fighting
each other every cycle. The hedger already shrinks its own leg as the strategy books flatten; that is
the correct direction of causality.

**3. Reducing risk is exempt from the controls that ask whether a view earns risk.** A delta is
risk-reducing when `|current + delta| < |current|` — pure sign-and-magnitude arithmetic
(`TargetPlanner.isRiskReducing`), exactly testable, with a sign flip that overshoots deliberately
*not* counting. Such a delta skips the ADR-0059 conviction floor and the ADR-0049 backtest-support
veto.

**The deterministic floor is not relaxed.** The firm drawdown breaker and the pre-trade guardrail run
unchanged on every order, reducing or not. This ADR only stops two *opinion* gates from vetoing an
exit; nothing that decides whether a trade is **safe** was touched.

## Consequences

- Positions now have a full lifecycle. Silence is an instruction to go flat, not a reason to hold.
- Composes with ADR-0064 rather than fighting it: when the edge gate is shut, `reduceOnly` passes a
  toward-flat delta through untouched, so "no source earns its cost" now means the book actually
  winds *down* instead of freezing at whatever exposure it happened to be carrying. Flat is a
  legitimate state and the loop can now reach it.
- Turnover rises briefly while the orphan backlog clears, then falls: the names being sold are ones
  no source is asking to re-buy, and while the edge gate is shut nothing may be re-bought at all.
- **Known residual:** partial adjustment approaches flat asymptotically and the order path will not
  submit a sub-unit clip, so a whole-unit instrument settles one unit short of flat. Bounded, never
  growing, and a pre-existing property of the whole-unit order path — tracked in the deferred
  register rather than fixed with an invented minimum-clip rule.
- A name is re-entered the moment a source has a view again and the edge gate allows it. Nothing here
  is sticky or stateful; every cycle re-derives the whole book from current measurements.

## Alternatives considered

- **Give fusion its own stop-loss / take-profit.** Re-implements the machinery ADR-0059 retired and
  needs two new money dials with no measured provenance. Rejected: the target-portfolio formulation
  already expresses "no view ⇒ flat" without a single new number.
- **Re-enable `manageOpenPositions` alongside fusion.** Two order origins for the same book —
  exactly what ADR-0055 exists to prevent.
- **Unwind only names the OOS selector dropped.** Narrower, but leaves the ordinary decayed-signal
  orphan — the more common case — untouched.
- **Flatten orphans in one clip.** Pays the full spread at once and needs a separate urgency dial.
  The partial-adjustment rate the desk already uses for every other target is the consistent choice.

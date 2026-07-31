# ADR-0134: An order records the trigger that originated it, not only why its status changed

- **Status:** Implemented
- **Date:** 2026-07-31
- **Deciders:** continuous-improvement loop (ADR-0063); Oleg to ratify
- **Tags:** backend, order, telemetry, observability

## Context

The improvement loop's procedure requires an order-level post-mortem every cycle: attribute the window's
PnL and exposure move to the specific triggers that opened the positions, fix the trigger behind a loser,
keep the one behind a winner. For four consecutive cycles that step produced nothing, and the register
recorded it as the top open defect on the grounds that the `reason` column was "not being populated".

Reading the write path shows that framing was wrong. `orders.reason` is a **status-transition** reason —
it answers *why did the status change*. `OrderService.routeApproveAndFill` therefore writes it only on the
branches that have a change worth explaining: `REJECTED` with the guardrail's text, `REJECTED` for
"no market data", `CANCELLED` for an unmarketable IOC, and `CANCELLED` for an ADR-0084 fusion re-plan. The
happy path `NEW → ROUTED → FILLED` has nothing to explain and passes a literal `null` at every step.

So the column is doing exactly what it was built to do, and it is structurally incapable of answering the
question the post-mortem asks. The live evidence is the shape you would predict from the code: in a
60-order window, every FILLED order and the one ROUTED order carry a null reason, while every CANCELLED
order carries text — and the same string each time. **The orders that never traded are the only ones that
are explained.** Doing nothing means the loop keeps guessing at mechanisms and then falsifying its own
guesses a cycle later, which is what the last several cycles actually spent themselves on.

The information exists; it is simply dropped. Every call site that decides to trade already holds a
sentence saying why — the strategy's `signal.rationale()`, the AI sleeve's `thesis()`, the hedge advisor's
`rationale()`, and, for the fusion planner that places most of the flow, the target's forecast plus whether
the delta is an entry, a reduce, an exit to flat, or an ADR-0086 trailing-stop cut. None of it is passed to
`submit`, so it cannot be recovered at the order layer afterwards.

## Decision

We will carry an **origination trigger** on the order command and persist it with the order row, as a
field distinct from the status reason. `NewOrder` gains a nullable `originReason`; `OrderStore.insertIfAbsent`
takes it and writes it into a new `orders.origin_reason` column **at insert**, before the order can reach any
status, and **nothing ever overwrites it** — no status transition touches the column. Child slices from the
ADV auto-slicer inherit the parent's trigger, because slicing changes how the desk gets the risk on, not why
it wanted it. Every production call site passes the sentence it already has; `FusionLifecycle` names four
triggers the post-mortem must distinguish (trailing risk cut, entry, reduce-toward-target, exit-to-flat) and
carries the code-computed forecast and source count alongside.

This is **telemetry only**. No decision, gate, or sizing path reads the field back, so a null origin can
never change what the desk trades — an unstated trigger degrades the post-mortem and nothing else.

## Alternatives considered

- **Overload the existing `reason` column, preserving it across transitions (write-if-null / COALESCE).**
  Cheaper — no migration, no signature changes. Rejected because it conflates two genuinely different
  questions and destroys information at exactly the moment it is most valuable: a REJECTED order would have
  to choose between recording why the desk wanted the trade and why the gate refused it, when the pair is
  the most useful record in the system. It also makes the column's meaning depend on status, which is the
  kind of implicit contract that gets misread later.
- **Reconstruct the trigger after the fact by joining orders to the fusion target book by timestamp.**
  No schema or code change at all. Rejected as unsound: the target book is recomputed every cycle and only
  the latest is retained, the join is a heuristic on time proximity rather than an identity, and it would
  produce a *plausible* attribution — which is worse than none, because the loop would act on it.
- **Put the trigger on the `Order` domain record rather than persistence-only.** Rejected for now: it would
  push a telemetry concern into `common-domain` and through the messaging contract (invariant 4) for no
  consumer that needs it. Revive it if a *decision* ever needs to read an order's origin — at which point it
  stops being telemetry and the domain is the right home.
- **Log the trigger instead of persisting it.** Rejected: logs are not queryable alongside fills and PnL,
  and the post-mortem's whole job is to cross triggers against per-name PnL.

## Consequences

- **Positive:** the orders that actually traded become attributable, which unblocks the order-level
  post-mortem the loop's procedure has required and been unable to perform. A rejected order now carries
  both the want and the refusal. The four fusion triggers are separable, so "the trailing stop cut it" and
  "the view decayed" stop looking identical in the data.
- **Negative:** a schema migration and a signature change on the order write path — the store port, both
  implementations, and six call sites — for a change that **moves no money**. It should be expected to score
  ⚠️ INCONCLUSIVE on the ADR-0116 window, and that is the correct outcome for buying evidence, not a
  failure. The trigger strings are also written by the call sites, so they are only as honest as the caller;
  a future refactor can silently leave one stale.
- **Follow-ups:** the operator's Orders view now receives `originReason` on `OrderRow` but does not yet
  render it. The register's next money item — ALPHA running negative net of its own fees while a frozen
  hedge masks it — is the intended first consumer of this evidence.

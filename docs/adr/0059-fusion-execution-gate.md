# ADR-0059: Fusion execution gate — trade only OOS-validated, convicted names

- **Status:** Proposed
- **Date:** 2026-07-23
- **Deciders:** Oleg
- **Tags:** strategy, fusion, cost, risk

## Context

The 2-day live/sim report exposed fusion routing (route-orders on) **churning**: one sim day did **919
fills / $770 fees**, the ALPHA book realised **−$7,911**, rescued only by the hedge book's directional
luck (+$7,517) — not edge, just cost-churn bailed out. Two holes in `FusionExecutor`'s backtest gate
(ADR-0049) caused it: (1) **`selection.isEmpty()` fails OPEN** — before the OOS selector's first run
(fresh sim) *every* forecast routes freely; (2) the selection map **includes `NO_TRADE` verdicts**
(a name that traded in the backtest and lost on both algos), and `containsKey` let those route — so
fusion traded names the selector had **explicitly vetoed** (the report shows `AAPL: none`). There is
also **no conviction floor**, so a combined forecast oscillating around zero flips the target every 30 s
cycle and pays spread each way. With a ~7 bps round trip, cost then dominates.

## Decision

We will make the fusion gate **fail-closed and edge-gated**. A fused delta routes only when: (1) the OOS
selector has a **positive-edge algo** for that name — an empty/absent selection *or* a `NO_TRADE` verdict
now **vetoes** (was: permitted); and (2) the combined forecast clears a **conviction floor**
(`jethro.fusion.min-forecast-to-route`, on the Carver [−20,+20] scale) so weak/oscillating signals don't
churn. Integer-lot sizing is unchanged (a futures delta < 1 contract already vetoes). Everything else in
the chain stays (sim-only ADR-0019, firm breaker, pre-trade guardrail).

## Alternatives considered

- **Keep fail-open until measured** ("never suppress on a no-measurement", as the direct strategy path
  does). Rejected for the SOLE-ORIGIN path: it *is* the churn — trading unvalidated names on a fresh run
  is exactly what bled the book. Fusion should wait for validation; the stakes are higher than one sleeve.
- **A cost-model expected-edge gate** (route only if forecast-implied edge > round-trip cost). Deferred,
  not rejected: needs a forecast→bps calibration we don't have yet; the conviction floor + the corrected
  backtest veto are the tractable first cut. Trigger: a measured forecast-to-return mapping exists.
- **Tighten the no-trade band only** (`buffer-fraction`). Rejected as the fix: it reduces re-trade
  frequency but still trades vetoed/unvalidated names — it treats the symptom, not the gate hole.

## Consequences

- **Positive:** fusion no longer churns unvalidated, vetoed, or weak-conviction names; the OOS backtest
  becomes a real veto for the order origin, not an advisory. Directly addresses the report's #1 finding.
- **Negative:** fusion routes **nothing** until the selector's first run validates names (a deliberate
  cost-averse tradeoff — silence beats bleeding); a genuinely edgey name below the conviction floor is
  skipped (bounded by the floor, tunable); a *validated* mean-reversion name can still trade actively —
  the floor and band temper churn, they don't eliminate an algo that legitimately trades often.

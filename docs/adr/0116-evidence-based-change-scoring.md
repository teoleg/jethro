# ADR-0116: Score a change by evidence over a window, not a single-cycle delta

- **Status:** Proposed
- **Date:** 2026-07-28
- **Deciders:** Oleg
- **Tags:** backend, loop, risk, methodology

## Context

The continuous-improvement loop (ADR-0063) scored each change on the PnL/exposure delta between its
baseline and the *next* cycle, against a fixed `$50` deadband. On the live paper book that is almost
entirely noise: gross is ~`$760`, so a 30-minute mark wiggle of ±`$1–2` is orders of magnitude below the
band. The result, all-time: **40 ⚠️ MIXED, 6 ✅ GOOD, 11 ❌ BAD** — 70% "no material change." The loop
cannot tell its good changes from its bad ones, gets **no learning signal**, and churns a new
micro-change every cycle. The owner's read was exactly right: *always MIXED, no real improvement.*

Two defects compound: (1) a **single-cycle** measurement is dominated by market noise, and (2) an
**absolute-dollar** deadband is meaningless on a small book.

## Decision

Judge a change by **evidence accumulated over an evaluation window**, in `scripts/score-change.py`:

- **Hold, then score.** After a change is deployed, it is left running and *not scored* until
  `MIN_CYCLES` (~6) heartbeats have accrued. Until then `score` reports `still accumulating evidence
  (n/MIN_CYCLES)` and leaves the pending baseline in place. The prompt tells the loop to **HOLD** — make
  no new change while one is under measurement — so the evidence is about *that* change.
- **Verdict from significance, not a deadband.** Over the window, per-cycle risk-adjusted return is
  `ΔPnL / scale`, `scale = max(GROSS_FLOOR, median|gross|)` (the floor stops a near-zero book amplifying
  a `$1` wiggle). With `t = mean·√n / stdev`:
  - **✅ GOOD** — `t ≥ +T_HURDLE` and exposure did not grow.
  - **❌ BAD** — `t ≤ −T_HURDLE`, or exposure grew with a non-positive mean → auto-reverted.
  - **⚠️ INCONCLUSIVE** — otherwise. **Kept, not reverted** — the honest "no measurable effect," which
    most micro-changes are.
- Deterministic, exact-decimal inputs, no model (invariants 1 & 7). `MIN_CYCLES`, `T_HURDLE`,
  `GROSS_FLOOR` are methodology knobs (PLACEHOLDER — Oleg to tune), env-overridable, and recorded in
  every snapshot so a verdict is recomputable.

Paired with a **prompt redirect** (same change): stop re-weighting sources with no measured edge — first
establish whether *any* signal predicts returns, and if not build/validate a **new** one through the OOS
gate; say so plainly when nothing has edge rather than tuning the combiner.

## Consequences

- **Intended:** the ledger stops being a wall of noise-MIXED; INCONCLUSIVE honestly marks "no effect,"
  GOOD/BAD carry statistical weight, and the loop holds a change long enough to actually measure it.
- **Honest limitation:** risk-adjusted PnL over a window is still **confounded by market direction** — a
  change held through a rally looks good regardless. Significance and the window reduce this but do not
  remove it; the analyst's qualitative "change vs market" attribution remains the backstop. A true
  counterfactual (A/B / shadow book) is the real fix and is deferred.
- **Slower cadence:** a change now occupies ~`MIN_CYCLES` cycles before the next — fewer, better-measured
  changes instead of constant churn. This is the point.
- The old single-cycle `classify()` is retained only for reference; the live path is `evaluate_window`.

ADR-0141 worked — the desk opened its first position in two days (gross $0 → $18,236.64) — so this cycle verifies and holds: that change is still under measurement and must not be disturbed.

*(Every figure below is read from `logs/report.md`, `reports/run-status.json` and the scorer's own
output. None is authored here — invariant 7 / ADR-0016.)*

## Situation — the live money, first

1. **Money.** Total PnL **-628.06833967 → -682.17363767**, Δ **-54.11** since last run and over the last
   3 runs. Underwater on cumulative PnL and off the +1%/3-iteration target (`on_track=False`,
   `stale=True`).
2. **Risk.** Gross **$18,236.64** = **1.2%** of the firm cap $1,500,000, headroom **$1,481,763**; net
   **-$18,236.64** = **1.8%** of the $1,000,000 net cap. `breaker.halted` **false**; `var95` **385.49**,
   `es95` **538.54** over **155** observations with `skippedExposure` **0.00**. The book has just come off
   DORMANT with enormous room — under the mission that is the goal, not a concern.
3. **Cause.** Last cycle's change (`a21177cea`, ADR-0141) is ✅ **VERIFIED at the defect level**. It
   repriced the no-trade band's average position at `min(TARGET_ABS, E|f|)`, and NQ — which Rule 380
   showed needed an unreachable `|aim|/|target|` of **0.8239** — walked its aim from **-0.003064** to
   **-0.047464**, crossed, and **FILLED** a SELL of **0.030886** at **13:58:29Z**. `orders_day.total`
   went **0 → 11**. The band no longer bans opening.
4. **Danger.** None. Nothing is near a cap or the drawdown breaker. UNDERWATER is a statement about
   cumulative PnL, not a live danger state.

## Attribution — 100% change, 0% market

The split is unusually clean because the book was empty at the baseline, so there were no untouched
positions for the market to move. Of the **-54.11**: **Δ unrealized -53.74164000** is entirely the new NQ
short (quantity **-0.030886**, `avgCost` **29435.50000000**, `mark` **29522.50000000** — the index rose
87 points against the entry) and **Δ realized -0.36365800** is that entry's cost. EQUITY
(**-595.62281205** realized, gross **$0.00**) and HEDGE (**+24.35397774**, gross **$0.00**) were both
unchanged. So the entire move is the direct impact of the change — and it is one position, minutes after
entry. One draw is not a verdict; the scorer owns that and has ~5 cycles left.

## Decision — verify and hold, no code change

`reports/.pending-baseline.json` exists for `a21177cea` (recorded 13:44:10Z, ~1 of 6 cycles), so per
ADR-0116 I make **no code change**: stacking a second change on top would destroy the evidence for the
one that just demonstrably unblocked the desk.

The next constraint is already identified and ranked #1 in `reports/must-fix.md`, and it is **not** the
band. `edgeGate` is **null**, so `PositionBuffer.mayIncrease` reduces to the ADR-0126 σ-cold veto, which
clamps the delta reduce-only and re-seeds the aim to the held position — exactly zero for a flat name.
Live: `aims` shows **all 18** equities at exactly **0.0** against real targets (NVDA `targetQty`
**-412.353151** on `combinedForecast` **-16.444129523306067**), `streamVolMeasuredNames` is **1**,
`insideBuffer` is **18** of **19**, and the log carries **15** `risk-cut σ sensor still cold` WARNs and
**0** warmed — the seed asking for **121** prices at a **30000ms** step but getting **17–42** before a
`GAP_BREAK`. I am deliberately **not** acting on it: the app restarted at 13:44Z with only ~15 minutes of
session marks, so this may simply be warm-up that self-heals ~60 minutes after the open, and "fixing" a
transient would be the classic overfit. The VERIFY-BY in the register settles it next cycle — if
`streamVolMeasuredNames` has climbed, it was warm-up and there is nothing to fix; if it is still ≤2 by
~15:00Z with >90 minutes of session, ADR-0138's seed repair is still broken across the overnight
boundary and that becomes the one change.

Every name in the book gets the same cash regardless of its volatility, and on this universe measured daily σ spans 7.4× — so the "23-name cross-section" was in risk terms a handful of names; the budget is now split by each name's own measured σ (ADR-0083).

## Situation (read from the live endpoints, not computed here)

1. **Money.** Total PnL is **−$826.06**, identical to the last run and identical across the last three
   (**+$0.00** over both windows). Not bleeding — **frozen**, for the twelfth consecutive cycle. The
   strategy book's realised **−$895.94** against **$140.73** of fees is closed history; the hedge's
   **+$69.88** is the only positive component. Against the owner target this is a clear miss:
   `on_track=false`, `stale=true`, `underwater=true`.
2. **Risk.** Gross and net exposure are both **$0.00**. VaR reads zero on "no positions", the drawdown
   breaker is untripped, and there is no exposure to cut. Nothing is at risk because nothing is held.
3. **Cause.** Last cycle's change (ADR-0082, the horizon ladder) scored **⚠️ MIXED — "no material change
   (within noise band)"**. That verdict is **unmeasured, not refuted**: it shipped ~11 minutes before the
   report, and its whole purpose was to make evidence accrue faster. It is visibly working — the new
   225s rung already shows `reversion` with 46 resolved observations against the 3600s rung's 92 built up
   over many hours, i.e. roughly 16× the cohort arrival rate. The gate is still shut, but for the first
   time the quantity blocking it is falling on its own.
4. **Danger.** **No.** Bleeding requires losses and exposure; there is neither. This is a stalled book,
   not a dangerous one, so the danger-state override does not apply and de-risking is not the move —
   there is nothing left to de-risk.
5. **Order-level post-mortem.** The window's only order is a single tiny HEDGE ES sell. No strategy
   orders were placed at all, so there is no losing trigger to fix and no winning one to strengthen.
   Every fusion target shows `deltaQty: 0` against `currentQty: 0` — the gate holds the whole book
   reduce-only, and reduce-only from flat is zero.
6. **Memory.** `docs/loop-findings.md` records five straight cycles spent refining the edge gate's
   *test* (ADR-0077/0079/0080/0081) while its *sample rate* was the binding constraint, and ADR-0082
   finally attacked the rate. The compounding lesson is explicit: do not spend a sixth cycle on the gate.
7. **Change vs. market.** Exactly separable and exactly zero on both sides: no position was opened,
   closed or resized this window, and no position was held to be marked. **0% market, 0% change.** The
   MIXED verdict reflects an untraded book, not a judgement on the code.

## Diagnosis and what I changed

With the gate self-repairing and nothing to de-risk, the highest-value work is making sure the book is
worth holding *when* it opens — and it is not. `jethro.fusion.unit-notional-usd` is one flat cash figure
applied to every instrument, so each name's risk contribution is proportional to its own volatility.
Measured over the stored daily closes that spans **0.435%/day (EURUSD) to 3.236%/day (NVDA) — 7.4×**.
ADR-0079 already prices the *correlation* half of this concentration problem and its own derivation
names the gap it leaves: the independence benchmark `σ_indep = √(Σ eᵢ² Σᵢᵢ)` is unequal across names
*precisely because* `Σᵢᵢ` is. It corrects for the names being one bet; nothing corrected for one name
being seven times the bet of another. That is a first-order tax on exactly the quantity this loop
optimises — PnL per unit of exposure — and it is invisible in gross notional, which looks evenly spread.

So the per-name budget is now split by each name's own **measured** daily σ, from the same EWMA
covariance that already prices parametric VaR, the hedge advisor and the ADR-0079 multiplier:
`kᵢ = σ_ref/σ̃ᵢ` with `σ_ref` the **harmonic mean** of the winsorised σ. The harmonic mean is not a
preference — it is the unique reference for which the covered budgets sum to exactly what they summed to
before, so **Oleg's `unit-notional-usd` is untouched and keeps its meaning** (the cash for a name of
*typical* volatility) and no new money number enters. Every covered name then contributes identical
standalone risk by construction. The book is additionally capped at the gross it replaced, so an
estimated σ can redistribute risk or shrink the book but can never lever it up; an uncovered name keeps
the flat dial, because no measurement means no claim. Deliberately **not** the gate, for the reason
memory records: ADR-0082 is mid-flight and stacking on it would make both unattributable.

Verified: 12 new worked-number tests (the 2%/1%/0.5% three-name example is asserted to the exact
quantity), full app suite green at 392 tests.

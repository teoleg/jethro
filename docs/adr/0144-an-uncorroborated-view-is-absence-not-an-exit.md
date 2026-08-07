# ADR-0144: An uncorroborated view is ABSENCE, not an exit

- **Status:** Implemented
- **Date:** 2026-08-07
- **Deciders:** Oleg
- **Tags:** fusion, execution, turnover, holding-period

## Context

ADR-0124 gives each name an **agreement scalar** — the share of the combined view's scale that is the
view rather than the disagreement around it — and returns **0** in one degenerate case: a name with a
single *effective* source. With zero residual degrees of freedom (`1 − Σŵᵢ² = 0`) the sources'
dispersion is **unestimable**, and the ADR is explicit that an untested view has earned no conviction.
That reasoning is correct, and it repaired a real ADR-0119 defect where uncorroborated names carried the
largest forecast in the cross-section.

**But the scalar multiplies the combined forecast, and the combined forecast is not only a sizing
input — it is also the exit trigger.** `TargetPlanner.targetQuantity` returns 0 for a zero forecast, and
ADR-0090 works a flat target **in full, this cycle**, deliberately bypassing the ADR-0080 partial
adjustment because a flat target is an exit. So a statement that means *"this name may not have risk put
ON it"* is executed as *"this name's view is zero — sell everything."* Absence of evidence is being
routed as evidence of zero.

**This is measured, not inferred.** Censusing every FILLED LIVE order since the 2026-08-06 boot by
origination trigger (ADR-0134) against the `sources=` count it carries:

| trigger | sources=1 | sources=2 | sources=3 | sources=4 |
|---|---|---|---|---|
| `fusion entry — target increase` | **0** | 5 | 15 | 5 |
| `fusion reduce toward a smaller target` | **0** | 1 | 56 | 7 |
| `fusion exit — target decayed to flat` | **6** | 0 | 0 | 0 |

Not one full exit was triggered by the intact ensemble reversing; every one was triggered by the
ensemble **collapsing to a single surviving source**. Entry reads source *content*; exit reads source
*availability*. The priced instance: BAC opened `19:08:52Z` at `forecast=-5.9863021173751845,
sources=3` and flattened `19:10:12Z` at `forecast=-0.0, sources=1` — lifetime **79 s**, cash **-3.03**,
fee **1.0321**, net **-$4.0621**, the shortest and most expensive round trip of the six completed since
boot. This run's own `fusion_targets` shows the mechanism standing still: EURUSD carries a single
contribution of `trend forecast=20.0` — the cap — and a `combinedForecast` of **0.0**.

**Why it costs money, in the desk's own measurements.** The fusion-weighted expectancy term structure is
monotone in horizon (**225 s -0.2473 bps**, **900 s -0.4562 bps**, **3600 s +1.0442 bps**), and the six
completed open→flat episodes rank by lifetime almost perfectly (Spearman **ρ = +0.943**, n=6, 5%
critical **0.886**): the money is in the long tail of the holding-period distribution and the losses are
in the short one. An exit rule keyed to source availability truncates that distribution from the left,
independently of anything the desk believes, and pays the equity round trip (**2.00 bps**) each time —
`totalFees` currently stands at **48.5%** of `|firmTotal|`.

**ADR-0140 is aimed at this collapse but is not reached on this path.** Its aging applies to a name
**absent from the target list**; an uncorroborated name is *present*, with an affirmative flat target,
so it never enters the absence clock. Establishing which of "the rule is unreached" and "the rule's
horizon is too short" holds was the prerequisite the must-fix register set before building. It is the
former.

## Decision

**A name whose combined view is uncorroborated is treated as ABSENT for the cycle, not flat.** In
`PositionBuffer.apply`, a target that is flat, held, carries **at most one contributing source**, and
was **not cut by ADR-0086 this cycle** routes no order and is left out of the aim snapshot — so its
ADR-0140 aim ages on exactly the clock any other absence uses, one full evidence horizon, after which
the intent is dropped as it is today.

`sources ≤ 1` is the *precise* characterisation of ADR-0124's unestimable case and needs no new state:
the scalar is zeroed when `1 − Σŵᵢ² ≤ 0`, and with every contributing weight strictly positive that
holds if and only if a single source carries all the weight. Zero sources is the same statement one step
further — the name is in the plan only because ADR-0065 plans over what the desk holds.

**No money, risk or exposure number is introduced, altered, or read** (invariant 7 / ADR-0016). This is
a branch on a source *count* the planner already computed and a set of instruments the risk cut already
named.

### What is deliberately untouched

- **Entry.** The agreement scalar still zeroes the target, so no risk can be put on a name without a
  second opinion. The census's `0 of 25` entries at `sources=1` stays `0 of 25` — this ADR changes the
  exit side of the asymmetry only, which is the side that was never justified.
- **A corroborated flat view.** Two or more sources netting to zero is a genuine, tested view of flat
  and still exits in full. That is the case ADR-0090 was written for.
- **The deterministic floor.** The branch runs *after* the ADR-0086 trailing risk cut and excludes every
  name that cut flattened — a stop is the desk's own affirmative word and outranks corroboration. The
  ADR-0064 edge gate, the ADR-0126 σ-cold veto, the pre-trade guardrail and the firm drawdown breaker
  are all upstream or downstream and unchanged.
- **One-way.** The branch can only ever *remove* an order, never create or enlarge one — the same
  property ADR-0076, ADR-0098 and ADR-0124 each carry.

## Consequences

- Holding period lengthens on exactly the names whose exits were never a decision, moving the realised
  distribution toward the horizons where measured expectancy is positive. Turnover and fees fall on the
  same names.
- Gross exposure rises, because positions that were being liquidated for a non-reason are retained. That
  is the intended direction under ADR-0132 (deploy inside the budget), and it is bounded by every
  existing cap: this ADR raises no ceiling.
- A position can now be held through a stretch with no corroborating view, for at most one evidence
  horizon. It is not unprotected during that stretch — the ADR-0086 trailing cut still prices and cuts
  it, and the risk floor is untouched. This is the accepted cost, and it is the same bet ADR-0140 made.

**Falsified if** the exit census still shows full exits concentrated at `sources=1` (the branch is not
reached, or something upstream flattens first), or gross rises while the scored vector deteriorates on
the retained names specifically (the positions were worth exiting and the corroboration collapse was
merely a coincident proxy for a real reversal — the answer would then be ADR-0086's cut distance, not
this rule).

**VERIFY-BY next run:** the same trigger × source-count census over the window — the share of
`fusion exit — target decayed to flat` fills carrying `sources=1` must fall **below 1.0**. Paired with
the stationary holding-period estimator (2 × time-average |inventory| ÷ one-way turnover rate over fixed
30-minute trailing windows), which must rise **without** gross falling.

## Alternatives not taken

- **Weaken ADR-0124's scalar** (e.g. return 1 at one effective source) — corrupts a correct measurement
  to fix its consumer, and re-opens the ADR-0119 defect where uncorroborated names carried the largest
  forecast in the cross-section. The measurement is right; its *interpretation downstream* was wrong.
- **Lengthen ADR-0140's retention window** — treats the symptom. The name never enters the absence clock
  at all, so no window length changes anything.
- **Buffer the flat target instead of exempting it** (drop ADR-0090's full-exit rule) — would slow every
  genuine exit, including the ones the desk means, to pay for one it does not.
- **Require corroboration to *exit* as well** (a symmetric gate) — would trap a position whose intact
  ensemble genuinely reversed behind a source that has gone quiet. Absence must not block risk coming
  off; that is the ADR-0065 lesson.

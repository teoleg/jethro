The desk had stopped being able to hold a position — it was round-tripping its holdings on every sign flip and never accumulating one, so the no-trade buffer now sits around the target it is derived about (ADR-0103).

*Every figure below is quoted from the live endpoints and the report; none is computed here. The
ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** Total PnL `$787.04` on `/api/risk` `.total` at report time, `$786.71` when I re-read the
endpoint mid-analysis. The SITUATION header puts the window at `+14.14` and the last three runs at
`+162.54`; `run-status.json` reads `on_track` true, `stale` false, `underwater` false. The book is not
bleeding — but it is no longer *making* anything either, and that is the story of this cycle.
Attribution splits it `ALPHA +1,055.35`, `MACRO +376.99` (unchanged to the cent for an eighth cycle —
still the stranded `0.000029` ES), `HEDGE −645.64`, fees `$431.57`.

**2. Risk.** Gross exposure `$7.90` at report time, `$1,393.88` live — down from `$26,684.11` a cycle
ago. That is not de-risking, it is a **flat book**: one name held (9 JNJ) against 23 non-zero fusion
targets, with `insideBuffer` reporting 17–21 of 23 names on every plan I sampled over four minutes.
Nowhere near the breaker; the problem is the opposite one.

**3. Cause — the change deployed last cycle.** ADR-0102 (the fusion aim clamped between flat and this
cycle's target) scored ⚠️ MIXED, and its ledger row flatters it: PnL up, gross `$26,684.11 → $7.90`,
"risk-adj improved 0.02876 → 99.59074". That ratio is an artifact of dividing by a book that no longer
exists. The clamp itself is not wrong — an intent on the opposite side of the current forecast was a
real, live defect — but a clamped aim was being read downstream as an **exit** and dumped at market,
unbuffered.

**4. Danger.** Not bleeding, exposure not rising — but **PnL is frozen and the desk cannot open a
position**, which fails the growth target permanently from here. Per the mandate that is the failure
state to attack this cycle, and it is what I attacked.

**5. Order-level post-mortem.** I polled `/api/fusion/targets` every 10 s for four minutes. GOOG sold 8
shares at a combined forecast of `−16.19` against a target of `−103.398`, then — the forecast having
flipped to `+2.00` — bought all 8 back at market four minutes later, aim `0.0`. A complete round trip,
spread paid twice, on a position that never reached 8% of its target. Every futures delta in the same
window (`ES −0.0074`, `ZN −0.063`, `NQ −0.012` contracts) rounded to zero at order scale and routed
nothing. That is the whole pattern: pay a round trip per sign flip, accumulate nothing.

**6. Memory.** `docs/loop-findings.md` Rule 2 from last cycle — "when the signal is mean-reverting,
every EWMA over it is a lagged inversion machine … check every remaining smoother against this" — is
exactly the thread I pulled, one layer further down the same call.

**7. Change vs. market.** The `+$14.14` on the window is almost entirely **market**: realised P&L on
positions the flattening closed, plus `MACRO`, which has not moved to the cent in eight cycles. The
exposure collapse is **100% the change** — ADR-0102's clamp is the only thing that flattened the book,
traceable order by order. I claim no PnL credit or blame for last cycle's change; its measurable effect
was to remove the desk's ability to carry risk.

## Diagnosis — a units mismatch, not a bad idea

ADR-0101 derives the no-trade band's width from `|g| > 2C/(λσ²)` where `g = a − h` and `a = μ/(λσ²)` is
the **frictionless optimum** — this planner's target. ADR-0094 hands the band `aim − held` instead, and
the aim is the ADR-0080 exponential path *toward* the target: at the live derived rate
`a = 1 − e^(−30/900) = 0.0328`, the band was being tested against roughly **3%** of the quantity its
width was sized for.

ADR-0094 made that move for a reason that has since expired. At Carver's `0.10` the band could not bind
around the target at all; putting it on the aim made it bind. ADR-0101 then *measured* this desk's
width — live it is `0.24` (AAPL) to `1.00` (capped), against `μ = 7.9748` bps and per-name round trips
of `0.42–20.11` bps — and a band that wide binds around the target perfectly well. The workaround
outlived its problem and became a brake with no release: from flat, the aim must accumulate a whole
band before the first order (GOOG at width `0.4944`: 30.5% of target, ~11 cycles, 5½ minutes of
unbroken agreement), while the only source that passes this desk's gate is mean-reverting at the
**900 s** rung and changes side well inside that. So the band was suppressing **accumulation toward the
target** and permitting **oscillation around the position** — the exact inverse of what a no-trade
region is for.

## The change (ADR-0103)

Test the gap to the **target**; take the aim's step. The band decides *whether* the position is far
enough from its optimum to be worth a round trip; the aim decides *how far* to move once it is, capped
so it never carries the position past the near edge. The settled position is unchanged at
`target − band·sgn` — one full cost-derived buffer inside the desk's own target. The flat-target exit
branch is now keyed on `target == 0` rather than `aim == 0`, the same condition every control that means
*get out* already sets, so the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0027 breaker
and the pre-trade guardrail keep their exact semantics — pinned as a test. No new dial and no new
number: the bound is the target and the band the planner already computed.

**Honest consequence:** this restores exposure off zero — roughly `$79k` of gross at the live fixed
point (9 of 23 names stay flat because `band > |target|`, 7 more are reduce-only on cost), rebuilt
gradually because the ADR-0080 step still rate-limits it. That is deliberate: the objective is PnL per
unit of exposure, not the absence of exposure, and a flat book earns nothing. If the measured vector is
worse, the scorer says so and this gets reverted.

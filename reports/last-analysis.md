# Last analysis — 2026-08-07 16:30Z

**A change the scorer condemned was still trading the book; I completed the revert it failed to land — and found that the verdict came from the exposure clause measuring a restart, not from the return test.**

## Situation (live numbers, read — never authored)

1. **Money.** Total PnL **-$946.92**, down **-$38.15** on the window and **-$95.23** across the last three
   runs. The book is bleeding, slowly. `pnl_growth_pct` reads **-5.65%** against the owner's **+1.0%**
   target — `on_track=False`, `stale=True`, `underwater=True`. Staleness here is the monitored failure,
   not a rest state.
2. **Risk.** Gross **$15,836.55** = **1.1%** of the firm gross cap, headroom **$1,484,163**; net
   **-$3,314.75** = **0.3%** of the net cap. Nowhere near a cap, breaker not halted. Gross fell
   **-$4,479.67** on the window. The book is far under its budget — undeployed capital, which under
   ADR-0132 is the failure to attack, not safety.
3. **Danger?** No. Bleeding, but at 1.1% of the cap with $1.48M of headroom this is not the
   bleeding-near-the-cap danger state. The live problem is the opposite one: the book is barely deployed.
4. **Cause.** `403a95ffd` (ADR-0144) closed its ADR-0116 window and scored **❌ BAD**. Critically, its
   auto-revert **conflicted on the loop's own report files and aborted** — the audited snapshot records
   `"revert": true` with `"revertApplied": false`. I verified this against the tree rather than trusting
   the note: `PositionBuffer.java`'s most recent commit was `403a95f` and four live `0144` references were
   still in the fusion package. **A change the scorer had condemned was still executing in the running
   book.** That is the loop's unclosed-loop failure mode, and under Step 0 it outranks every new idea.

## What I did

Completed the revert by hand over exactly three code paths — `FusionLifecycle.java`, `PositionBuffer.java`,
`PositionBufferTest.java` — restoring `PositionBuffer.apply` to its four-argument form and deleting the five
tests that asserted the corroboration-hold shape. The ADR, the ledger row and the findings are kept, with
ADR-0144 marked **Reverted** and its rationale recorded. `./gradlew -Pci test` is green. This is the
precedent set by the completed reverts of ADR-0136 and ADR-0139.

## The finding that matters more than the revert

**The BAD verdict did not come from the risk-adjusted return test.** From the scorer's own snapshot: over
`n = 7` cycles it measured `t = -0.591` against its own hurdle of `1.5` — comfortably inside the noise band,
which is the ⚠️ INCONCLUSIVE region, not the BAD one. The verdict came entirely from the exposure clause:
`grew: true`, on `gross_start: 0.0 → gross_end: 15835.17`.

That `gross_start` is the whole story. The baseline was sampled at `2026-08-07T13:45:27Z`, immediately after
the *previous* BAD-revert restarted the app — so **the book was flat when the baseline was taken**. Against
a flat baseline, any change that deploys capital at all reads as "exposure grew with no PnL gain". The
ledger shows the same shape recurring: `851082687` gross `0 → 956` BAD, `fb9273505` gross `0 → 43,624` BAD.

So this window graded **the restart, not the mechanism** — and the clause is currently condemning exactly
the behaviour ADR-0132 asks for. That is now must-fix **#1**, ranked above the entry-cancel ratchet
(re-confirmed unchanged this window, now #2), because it structurally blocks the loop from ever landing a
capital-deploying change. I did not act on it this cycle: the revert was the mandated one change, and one
coherent change per run is the rule that keeps attribution possible.

## Attribution — change vs market, honestly

The window's **-$38.15** and **-$4,479.67** of gross are credited to **nothing**. No code was deployed by me
during the window; the only intervention live in it was the condemned ADR-0144 branch, whose effect the
scorer already measured as statistically indistinguishable from noise (`t = -0.591`). The gross decline is
consistent with the entry-cancel ratchet grinding the book down — the build leg cancels, the cut leg always
fills — but that is a directional reading across cycles in which I changed nothing, which makes it a fact
about the environment, not evidence for my hypothesis.

**Verify next run:** `fusion exit — target decayed to flat` must reappear in `recent_orders`. It returned
**0 across all five held cycles** while ADR-0144's branch suppressed it, so its restoration is the direct
categorical observable that this revert landed — guarded by `grep -rn "0144"` over the fusion package
returning nothing, so a stale build cannot pass the test.

# Last analysis — 2026-08-07 17:00Z

**No change: last cycle's revert is ✅ VERIFIED — the exit-to-flat leg fired 6 times after five cycles of
zero — and the ADR-0116 window on it is open at 1/6, so the loop is frozen by its own rule.**

## Situation (live, read from this run's report — not authored)

1. **Money.** Total PnL **$-998.46**. Down **$53.09** since last run and **$79.72** across the last three —
   the book is bleeding, slowly and steadily, and is UNDERWATER. It is off the +1%/3-iteration target.
2. **Risk.** Gross **$18,300.23** = **1.2%** of the firm cap, headroom **$1,481,700**; net **$-1,863.71** =
   **0.2%** of the net cap. Gross rose **+$5,106.59** on the window. This is **not** danger — it is the
   book coming off dormant into an almost entirely unused budget, which is the ADR-0132 objective. No
   `NEAR FIRM CAP` flag, no breaker. The danger case (bleeding *at* the cap) does not apply.
3. **Cause.** Last cycle's change was the hand-completion of the failed auto-revert of `403a95ffd`
   (ADR-0144). It is **ungraded** — the scorer prints `still accumulating evidence (1/6 cycles)` and
   `reports/.pending-baseline.json` is present. Under the contract that freezes me: no new code change
   this cycle.
4. **Danger.** None. Underwater, but with 98.8% of the gross budget unused and no breaker in sight.

## Step 0 — did the revert land, and did it work?

✅ **VERIFIED**, on the categorical VERIFY-BY set last cycle (Rule 461 — a count, not a rate, so noise
cannot pass it). `grep -rn "0144"` over the fusion main and test packages returns nothing but an unrelated
decimal literal in `EffectiveSpreadCostTest`, so the build is not stale. And the observable I named came
back: **`fusion exit — target decayed to flat` appears 6 times** this window — `PFE BUY 63` and
`NQ BUY 0.000246`, each REJECTED with `no market data` in the seconds after the restart and then
**retried and FILLED** at 16:38:00 and 16:42:34. That trigger returned **0 for all five cycles** ADR-0144's
corroboration-hold branch was live. The suppressed path is executing again.

One honest qualification: every exit row falls inside 16:36–16:42, the first six minutes of a
**1,452-second** process. The path is demonstrably reachable; whether it fires in a warmed steady state is
not yet shown. Not a defect, just the limit of what this window proves.

## Attribution — the window's move is credited to nothing

I deployed no strategy logic this cycle; the only intervention was the revert, which *removed* code. The
**-$53.09** is market on positions I did not choose, and the **+$5,106.59** of gross is the fusion book
re-deploying as σ warms after a restart — the same clock effect Rules 433/459 already record. Neither is
mine to claim or be blamed for.

## Item #2 re-measured (nothing edited, so this is a free observation)

The entry-cancel ratchet persists but on a smaller sample: **12 entries CANCELLED / 8 FILLED**, against
**27 of 27 reduces FILLED** and **7 of 7 hedges FILLED**. Splitting the cancels by what superseded them —
**4 survivable** (same side, no-smaller size: a resting order would have done the job), **1** genuine side
flip, **1** size reduction. The asymmetry is unchanged in kind: cancels remain exclusively `fusion entry`
origin, and the cut and hedge legs still execute with certainty.

## Item #1 — its VERIFY-BY is already met by the open baseline, and that narrows the defect

I read `reports/.pending-baseline.json` rather than waiting for the verdict, and it changes the diagnosis.
The baseline for `3c43242ba`, stamped `2026-08-07T16:35:20Z`, records **`gross_exposure: 13194.49`** —
**non-zero**. Every prior graded change in the ledger opened at `gross_start: 0.0` (`851082687` 0→956,
`fb9273505` 0→43,624, `403a95ffd` 0→15,835), which is the whole basis on which item #1 argued the exposure
clause structurally condemns capital deployment.

So the flat baseline is **not** an invariant of the scorer — it is a consequence of *when* the sample lands
relative to a restart. This time it landed against a live book. That is item #1's stated VERIFY-BY, met,
and it demotes the item from "structural blocker" to "a timing race that sometimes fires". I am not
striking it yet: one observation is not the fix, and I did nothing to cause it. But the next verdict is now
a genuinely clean test — two live books compared — so I will read its clause before ranking item #1 again.

## What I am watching next cycle

`3c43242ba` reaching 6/6 and, more importantly, **which clause** decides it (Rule 465). With a non-zero
`gross_start` the exposure clause is no longer pre-loaded against the change, so for the first time in this
sequence the risk-adjusted return test should be the one that speaks.

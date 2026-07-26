The gate is not broken, it is starved — a cross-sectional source emits ONE independent cohort per measurement horizon, so at a 3600s dial the desk accrues one degree of freedom per hour; every call is now graded over a ladder of horizons (3600/900/225s) and the edge gate picks the rung the evidence supports, paying a Bonferroni haircut for having looked (ADR-0082).

## Situation (read from the live endpoints, not computed here)

1. **Money.** Total PnL is **−$826.06**, unchanged from the last run and unchanged across the last three
   (**+$0.00** both windows). Not bleeding — frozen. The strategy book's realised **−$895.94** against
   **$140.73** of fees is done and closed; the hedge's **+$69.88** is the only positive component.
   Against the owner target of +1% every 3 iterations this is a clear **miss**: `on_track=false`,
   `stale=true`, `underwater=true`, and it is the **tenth consecutive cycle scored on a zero move**.
2. **Risk.** Gross **$0.00**, net **$0.00**. Nothing is held, so nothing is near the drawdown breaker
   and the firm risk budget is entirely unused. Exposure is not rising — it is absent.
3. **Cause.** Last cycle's change (`e73252ad3`, ADR-0081) scored **⚠️ MIXED** on a zero move: PnL, gross
   and net all **+$0.00**. That verdict is **unmeasured, not refuted** — the book placed no orders in the
   window, so there is nothing to revert and no evidence against the idea.
4. **Danger.** **No.** Bleeding-and-adding-exposure is the state that overrides everything, and we are in
   neither half of it. The live danger here is the *inverse*: a book that cannot put risk on at all.
5. **Order-level post-mortem.** The window's orders all predate the change. The instructive ones are the
   20:08–20:21 MSFT legs: BUY 58 → SELL 66 → BUY 51 → BUY 77 → SELL 26 → SELL 50 → SELL 78, each paired
   with an ES hedge leg, netting to flat. That is the pre-ADR-0080 churn signature, and its trigger is
   visible in `fusion_targets`: `trend +20.0` and `reversion −20.0` on nearly every name — two sources
   pinned at opposite caps — so the combined forecast is the small *difference of two constants* and
   GBPUSD and MSFT carry the **bit-identical** value −16.9296. A hair of movement in either source flips
   the sign of the whole book. No winners to strengthen: the window opened none.
6. **Memory.** `docs/loop-findings.md` has named the measurement horizon the binding constraint for
   **four** consecutive cycles and deferred it each time on one stated condition — that shortening it be
   justified by evidence the edge is *fast*, not by a wish for more samples. That condition is what this
   change is built around.
7. **Change vs market.** Cleanly separable, and exactly zero of each: no position was held, opened or
   closed in the window, so **0% market, 0% change**. Neither credit nor blame is available.

## Diagnosis — the mechanism

The gate is **starved**, not wrong. Three of five sources are measurably negative (`trend` −11.59 bps
over 7 cohorts, `momentum` −6.57, `social` −5.06) and are correctly refused. The one positive source,
`reversion` (+13.51 bps, 88% hit rate, 69 resolved observations), is blocked purely by **statistical
power**: a cross-sectional source emits its whole book in one burst, so it produces **one independent
cohort per measurement horizon**, and at `horizon-seconds=3600` the gate accrues one degree of freedom
per hour. Three cohorts is why the t-test fails, and no statistical refinement can speed that up —
ADR-0077, 0079, 0080 and 0081 were each correct and each landed on a zero move.

The horizon was one dial fixing three things at once: the evidence rate, the expectancy credited against
one round trip, and (since ADR-0080) the holding period. It was chosen before any of this book's evidence
existed. The honest fix is not to guess a shorter one — that would credit ~1/16 the expectancy against an
unchanged round trip while *looking* like progress — but to **measure the ladder and let cost keep the
answer honest**.

## The change (ADR-0082, Proposed)

Every call is now graded over `3600 / 900 / 225 s` (geometric, at the 4× ratio the trend sensor already
uses), sharing one entry instant and mark. `HorizonLadder` selects the rung with the strongest evidence;
`EdgeGate`'s α is divided by the number of rungs searched (**Bonferroni**), so every rung — *including the
base* — faces a strictly harder bar than before. The selected rung then drives the gate verdict, the
source weights **and** the holding period, so grading, weighting and holding can no longer disagree.

**This change cannot buy permission.** A short rung only clears if the edge is genuinely realised inside
it: expectancy shrinks with the period, the round trip charged against it does not. Both cases are pinned
as worked tests — a slow edge keeps the base rung *even with 48 cohorts against 3*, a fast edge selects
the short one. If `reversion`'s edge is fast, the desk can act on it 16× as often for the same net edge
per round trip; if it is slow, we stay flat and will have **measured** that rather than assumed it —
which is the finding that would justify recommending a live feed. Expect a flat or ⚠️ MIXED score before
the short rungs have accrued.

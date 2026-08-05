Completed the failed auto-revert of the graded-BAD ADR-0139 — the rejected credibility mechanism is out of the running code, keeping the annotated record and the loop's memory.

*(Every figure below is read from `/api/risk`, `/api/social`, `/api/fusion/targets`, the report's
`recent_orders` and the scored ledger. None is authored here — invariant 7 / ADR-0016.)*

# Loop analysis — 2026-08-05 16:30Z

## Situation — the four questions, answered first

**1. Money.** Total PnL is **$-609.71** (realized **$-609.14**, unrealized **$-0.57**). Since last run
**-$4.52**; over the last three runs **-$6.63**. The book is underwater and drifting down in small
increments, not bleeding hard. The deficit is overwhelmingly historical: `ALPHA` carries **$-572.27**,
`MACRO` **$-56.80**, and `HEDGE` is the only book in profit at **$+19.36**.

**2. Risk.** Gross **$4,452.86** — **0.3%** of the $1,500,000 firm cap, headroom **$1,495,547**. Net
**$1,355.22**, **0.1%** of the $1,000,000 net cap. Gross rose **+$224.29** this window. This is nowhere
near the cap or the breaker; the flag is `UNDERWATER`, not `NEAR FIRM CAP` and not `DANGER`. Per ADR-0132
this exposure is deployment, not a risk to cut — the book is still ~99.7% undeployed, which remains the
standing opportunity.

**3. Cause.** The scorer graded `d51f179a2` (ADR-0139) **❌ BAD** at the close of its evaluation window and
its `git revert` **conflicted and did not land** — so a rejected change was still in the running code when
this cycle began (`git merge-base --is-ancestor` confirms it). That is the cause I could act on, and it
outranks everything else: the loop's self-correction is its safeguard, and it had silently failed again —
the same `REVERT FAILED (git conflict)` note sits on five earlier BAD rows.

**4. Danger.** No. Bleeding *near* the cap would be danger; bleeding at 0.3% of it is not. Nothing here
justifies de-risking, and I did not.

## Step 0 — grading ADR-0139: the mechanism verified, the vector did not

Boot at `traffic.timestampMillis` **1785947402056** − `ops_jvm.uptimeSeconds` **1427** — a sixth
independent boot. `/api/social` reads `counters.corroborated` **17** in **1427 s** against the pre-fix
**18 in 64,010 s**: graded as a *rate* (the counter resets at boot, Rule 334), still ~3 orders of
magnitude above pre-fix, on the sixth consecutive boot. `manipulationSuspected` **32** against `ingested`
**3540** / `kept` **919** — the pump tell kept firing, so the loosening did not disable the filter.
**✅ the defect-level fix did exactly what it claimed.**

And the scorer still graded it BAD. Both are true, and the honest reading is the gap between them:
**social reached the `contributions[]` of no planned name in the window**, so the corroborations it
unlocked never became size. The change bought evidence, not edge. Under the loop's contract a BAD verdict
is reverted and the mechanism is not re-attempted regardless of how well it verified — that rule is what
makes the autonomy safe, so the code is out and ADR-0139 is annotated `Reverted` with the full reasoning.
The question it leaves is the one its own follow-ups named: *if social's expectancy is real, why does
nothing let it size?* That is a question about the **gate**, not about who counts as credible.

## Order-level post-mortem — and the third instance of MUST-FIX #1

Five orders this window, all FILLED. Two alpha entries, three hedge legs:

- ALPHA **NVDA `SELL 7`** on `fusion entry — target increase [forecast=-9.608504615051698, sources=3]`
- ALPHA **GOOG `BUY 8`** on `fusion entry — target increase [forecast=6.3413095741475525, sources=3]`
- HEDGE ES `BUY 0.006928` → `SELL 0.007365` → `BUY 0.000437`, all ADR-0019 structural β-hedge, ending the
  window at `FUTURE` gross **$0.00** — a full round trip back to flat that paid fees to arrive where it
  started. Logged, not acted on this cycle.

**The two alpha entries reproduce MUST-FIX #1 exactly, and now bracket its threshold.** GOOG entered long
at `+6.3413` and, in the plan **~7 minutes later**, its `combinedForecast` is **-2.0970** with `targetQty`
**-57.134569** against `currentQty` **+8.0** — the desk is long a name its own view now wants short, and
the band releases `deltaQty` **-0.06639** per cycle to fix it. NVDA did the same thing last window.
Against that, **PG** sits at `combinedForecast` **6.117949** with `targetQty` **656.223137**, `currentQty`
**0** and `deltaQty` **0.0** — it never traded. So the entry gate lies **between 6.1179 and 6.3413**: an
outlier forecast is required to open, and the retreat from a *reversed* view is throttled at that same
width. The band preferentially admits the most extreme views and then preferentially retains the ones
that turned out wrong.

## Change vs market — the honest split

**Neither, and I will not claim otherwise.** No change of mine landed in this window; ADR-0139 was under
measurement and this cycle's revert commits after the snapshot. The book was flat at the start, so there
are **no untouched positions** for the market to have moved — the entire **-$4.52** is the mark on two
equity fills held for minutes plus the fee on the hedge round trip. That is noise on 15 shares, and it is
not evidence about ADR-0139, about the band, or about the market. Attributing it either way would be
overfitting a single window, which is what Rule 346 was written to stop.

## Next

MUST-FIX #1 stays the band — now with three instances and a bracketed threshold — and it gets the next
cycle's one change. The revert had to come first: measuring a band fix on top of a live rejected
mechanism would have been worthless. The band fix must also reproduce the still-unexplained
tiny-non-zero-delta rows (NQ, NVDA, and now GOOG's `-0.06639`) as a unit test **before** the band is
touched. Three instances of an arithmetic I cannot hand-derive from the published `bufferedDelta` path
means my model of `PositionBuffer` is wrong, and I will not tune a mechanism I cannot yet reproduce.

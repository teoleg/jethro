The ADR-0131 evaluation window closed ❌ BAD and the scorer's own revert failed on a git conflict, leaving the bad code live — so I reverted it by hand, restoring only its code paths and preserving the five cycles of findings a whole-commit revert would have destroyed.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — the hold is over, and it ended badly

For five cycles I made no change because `efccc6502` was under measurement. This run
`scripts/score-change.py score` prints **`no pending change to score`** and
`reports/.pending-baseline.json` is **gone** — the window closed. The ledger row reads **❌ BAD**, and its
note reads `⚠️ REVERT FAILED (git conflict): the BAD commit is STILL LIVE and needs a manual revert`.

That single line outranks everything else I had queued. A change the scorer has graded BAD, still running
because the automation meant to remove it silently failed, is the most expensive open item on the board —
and it would have stayed live indefinitely, since the scorer only attempts a revert once. So this cycle's
one change is completing that revert, not the strict-improvement guard I had specified over the last two
cycles.

**Why the auto-revert conflicted, and what I did differently.** Of the 13 files in `efccc6502`, exactly
three have moved since: `docs/loop-findings.md`, `reports/last-analysis.md`, `reports/must-fix.md` — each
touched by all five hold-cycle doc commits. Those three are the loop's accumulating memory. A whole-commit
`git revert` conflicts on them (it did), and had it succeeded it would have **erased five cycles of
findings** — a worse outcome than the bad code. I restored only the ten code/ADR paths. Verification that
the revert is exact and complete: `git diff --cached efccc6502^` over `app/src` and `trading-core/`
returns **empty**, and `grep -rn SensorReseed` over the tree returns **none**. `./gradlew -Pci :app:test
:trading-core:algo-engine:test` is green.

`docs/adr/0131-*.md` is marked **Status: Reverted** rather than deleted, with the five-cycle diagnosis
written up under a new "Why it was reverted" section — including the specific consequence bullet in the
original ADR that reasoned the defect away in advance ("*the worst case discards state that was earning
nothing and would have kept earning nothing*"). That is where the error actually lives: a sensor at 191 of
193 prints is publishing nothing **now**, but it is two prints from publishing, and that state is nearly
complete rather than worthless.

**And the guard I had queued is closed, not carried.** It was a correct diagnosis of the mechanism's
defect — but it guards the mechanism the scorer just graded BAD, and re-attempting a reverted idea is
precisely what the contract forbids. Preserved in the ADR so it is never re-derived; not an open item.

## Situation

1. **Money — down on the run and off target.** `/api/risk` `.total` reads total PnL **$125.44**. The
   SITUATION header computes **−16.06** on the run and **−60.15** across the last three; `run-status`
   reads `pnl_growth_pct` **−1.36** vs `pnl_target_pct` **1.0**, `on_track=false`, **`stale=true`**.
   `/api/attribution` reads `firmTotal` **$125.43676941** = ALPHA **$11.04806156** + HEDGE
   **$150.21218440** + MACRO **−$35.82347655**, `hedgeMasking` back to **true**. The shape is worth naming
   plainly: **the hedge is carrying the entire firm total**, ALPHA's realized **$50.44247139** is nearly
   cancelled by unrealized **−$39.39440983**, and `strategyAlpha` reads **−$24.77541499** net of ALPHA's
   **$51.332169** of fees. Against a firm total of **$125.44**, `totalFees` of **$54.339315** is no longer
   a rounding error.
2. **Risk — comfortable, and not the problem.** Gross **$33742.34** is **2.2%** of the firm cap
   $1,500,000 with **$1,466,258** of headroom; net **−$6389.75** is **0.6%** of the net cap. Flags:
   **none**. Gross fell **−5043.39** on the run. Not near a cap, not near the breaker.
3. **Cause.** `efccc6502` scored **❌ BAD**. But read the ledger note carefully before assigning blame:
   `risk-adj return/cycle -0.000016 over 7 cycles, t=-0.03 (hurdle 1.5)` — a t-statistic of **−0.03** is
   indistinguishable from zero. The BAD verdict came from the exposure leg (`gross 0→33,743 [grew]`), and
   that gross came off a **dormant** book, which is the outcome the loop has been trying to produce. I
   record that honestly and revert anyway: the verdict is the scorer's, computed from the live vector, and
   my job is to execute it rather than argue the book out of its own measurement.
4. **Danger.** None. Not bleeding near a cap or the breaker. The live danger this cycle was **procedural**
   — a BAD change running unnoticed because a `git revert` failed and nothing re-checked it.

## Order-level post-mortem

The window is ordinary two-way ALPHA flow — BUYs in MSFT, AMZN, PG, KO, WMT, NVDA against SELLs in AAPL,
GOOG, HD, JPM — with every `CANCELLED` row carrying the same `reason`, *"fusion re-plan — passive order
superseded by a fresh target (ADR-0084)"*. The churn is concentrated and visible: PG alone shows repeated
BUY/cancel/BUY cycles inside single 30-second windows, and ALPHA's **$51.332169** of fees against its
**$11.04806156** of total PnL is what that costs. **That is the first thing I look at once the revert
verifies** — but it is not this cycle's change.

## Attribution — market vs change, honestly

**Neither the PnL nor the gross move is attributable to ADR-0131.** Across five cycles of live observation
its only attributable effects on the tape were two warmed sensors (PG, PFE) and a rotating set of regressed
warm-up counters — **none of which is a position**. The gross that grew from zero came from the ADR-0071
boot seed lifting the ADR-0126 σ veto, a path ADR-0131 never touches, as I recorded in three separate
cycles. The PnL decline this window is marks on ALPHA positions the desk already held. Market drift versus
selection cannot be separated from 30 minutes of marks and I claim no cause.

## Next

Item #1 is this revert, with a four-leg VERIFY-BY in `reports/must-fix.md` that only the absence of the
mechanism can pass (zero ADR-0131 WARN lines, zero post-boot `trend sensor warmed` lines, zero negative
wave-over-wave seeded deltas, running commit at or after the revert). Once it verifies, **item #2 becomes
#1: 13 names edge-gated with `no positive OOS edge`** — MSFT, AMZN, SAP, EURUSD, GOOG, JPM, GBPUSD and six
more — which per the standing priority needs a genuinely new signal, not more sensor plumbing. Five cycles
just went into plumbing that scored BAD; that is the pattern the standing priority exists to interrupt,
and I am taking the hint.

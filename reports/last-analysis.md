Fixed the loop's broken self-correction: every change the scorer graded ❌ BAD since ADR-0116 is still running, because the auto-revert conflicted 9 times out of 9 on the loop's own mandated notes — never on code.

*(Every figure below is read from `logs/report.md`, `reports/improvement-ledger.md`, `git`, or the source
files named, or is arithmetic on those read fields. None is authored here — invariant 7 / ADR-0016.)*

## Situation

**Money.** Total PnL **$-821.79**, down **$8.19** since last run and **$28.68** over the last three.
UNDERWATER is cumulative; the book bleeds slowly rather than sharply.

**Risk.** Gross **$9,055.68** — **0.6%** of the $1,500,000 firm cap, headroom **$1,490,944**. Net
**$-5,101.84**, **0.5%** of the $1,000,000 net cap. `breaker.halted` false, `regime` CALM (`trend` CHOP),
`riskCuts` empty, `bookVolBrake` **1.0**, `var95` **83.04** / `es95` **139.33** on **154** observations with
`skippedExposure` **0.00**. Not a danger state — the opposite failure: a book at 0.6% of the owner's budget
is dormant, which CLAUDE.md calls a failure to attack.

**Cause.** Nothing I did. `git diff --name-only 39451ce..HEAD` touches only `docs/loop-findings.md` and
`reports/` — the sixth consecutive **0% change / 100% market** window, so the PnL and gross moves earn my
changes neither credit nor blame.

**Danger.** No. Not bleeding near a cap, nowhere near the breaker.

## Step 0 — and the finding that reordered the whole register

`39451ce71` (ADR-0142) scored, ending the freeze. Its own claim verified a sixth time: `uptimeSeconds`
**10120** against `traffic.timestampMillis` **1786037401687** puts the boot instant at **1786027281687**, the
same 14:41:21Z process as the five prior cycles — the self-inflicted restart is genuinely gone. But the
scorer graded the **vector** **❌ BAD** (risk-adjusted return/cycle **-0.001329** over 7 cycles, **t=-1.84**
vs the 1.5 hurdle) and the auto-revert **failed**, as it has every time.

I checked *why*, and the answer outranks everything previously on the register. Replaying all nine failed
reverts at their **exact scoring point** — the parent of each one's own `chore(ledger)` commit, not today's
HEAD, since the naive replay is confounded by later hand-completions — the whole-commit `git revert`
conflicted **9 of 9 times**, every one on precisely `docs/loop-findings.md`, `reports/last-analysis.md` and
`reports/must-fix.md`, **and nothing else**. Scoped to code paths, the same nine apply **clean, 9 of 9**.

The mechanism is a collision between two of the loop's own rules. `ops/improve-prompt.md` mandates rewriting
those three files **every cycle, change or not**. ADR-0116 then holds a change **6** cycles before grading
it. By scoring time they have been rewritten ~6 times on top of the commit; `git revert` tries to rewind
them, conflicts with certainty, and — being all-or-nothing — **aborts the whole revert including the code**.
ADR-0116 made the loop's judgment better and its enforcement impossible in the same stroke.

So the loop's only self-correction arm has been dead, silently. The book is trading under the accumulated
code of ADR-0133, ADR-0135, ADR-0136, ADR-0139 and ADR-0142 — all condemned, none pulled by the machine.
Every window since has measured a new change against the residue of every previously-rejected one instead of
a stable base, which is a sufficient explanation for a ledger that reads INCONCLUSIVE. Five were eventually
removed by burning a whole *later* cycle on a hand-revert; four never were.

## The change (ADR-0143)

`scripts/score-change.py` now reverts a BAD commit's **code**, never the loop's own record:
`REVERT_KEEP_PATHS = ('reports/', 'docs/adr/', 'docs/loop-findings.md', 'docs/loop-playbook.md')`. It lists
the commit's paths, drops the kept ones, and applies the reverse patch restricted to the rest. It **refuses
rather than destroys** — an unclean tree is untouched, a genuine conflict *in a code path* restores the tree
exactly and still reports failure, and `revertApplied` still records the actual git outcome, so a failure can
never masquerade as a revert. The rejected decision stays on the record (annotated ADR + ledger row +
finding), which is what the loop's five hand-completions already did by convention — the scorer just never
implemented it. Deliberately **not** ADR-0142's `NON_BINARY_PATHS`: that asks "can this reach the app
binary", this asks "is this the loop's own record", and `ops/` differs between them.

**No money, risk or exposure number is introduced, altered or read.** `classify_verdict`, `evaluate_window`,
`T_HURDLE`, `MIN_CYCLES`, the deadbands and the ledger vector are untouched — this repairs how a decision is
*enforced*, not how it is *made*. Nothing inside the trading app changes; the deterministic floor is not in
scope.

**I did not hand-revert ADR-0142**, and the reason is recorded rather than assumed: its verdict is
confounded. The restart it removed had been flattening the book to **$0.00** gross cycle after cycle (three
consecutive scored rows read `→ $0.00`), so the loss its window measured is largely the book finally being
allowed to hold a position long enough to lose money on an aim whose expectancy is negative. Rewinding it
restores restart-induced dormancy — a failure state, not safety. That is now a decision the machine can take
on its own evidence.

## What I re-measured while I was in there

Item #2 (the old #1) confirmed a third time, and its shares swung a third time: the mean-reverting pair is
**46.85%** of the aim now, against **61.90%** and **51.0%** on the two prior windows, with weights unchanged
— exactly the instability Rule 412 warned against. The stable statistic is the aggregate: aim-weighted 3600s
expectancy **+0.0536 bps gross → -1.9464 net** of the **2.00 bps** round trip, against **-1.7898** and
**-1.60** before. Same sign, three windows. `xsreversion` carries the only |t| > 2 in the 15-row table for a
**fourth** consecutive window (900s **t = -2.44**), still short of Bonferroni's **2.94** — the evidence is
sign-consistency, not the p-value. `social` is still the only source clearing cost (**+3.430** gross →
**+1.430** net, hit **0.593**) and is still **0.0%** of every aim; ADR-0139 already tried that gate and was
graded ❌ BAD, so it stays untouched.

## Verification

`python3 scripts/test-score-change.py` green — a synthetic repo reproducing the exact shape of all nine
failures (code + ADR commit, then `MIN_CYCLES` of mandated record rewrites on top), asserting the whole-commit
revert conflicts on precisely those three files, the scoped revert applies, the bad code is gone, and the ADR,
findings and register survive at their latest content; plus record-only commits, a genuine code conflict
leaving nothing half-applied, and a dirty tree being refused. `./gradlew -Pci test` **BUILD SUCCESSFUL** (no
Java changed). Honest caveat: the Python test is **not** run by `-Pci test` — wiring it into the wrapper is a
named follow-up, not smuggled into this change.

**Graded next run by:** the next ❌ BAD row carrying `reverted (...)` instead of `⚠️ REVERT FAILED`, and a
`Revert "..."` commit appearing with no agent action. That only fires on the next BAD verdict, which may be
several cycles out; until then the standing evidence is the 9-of-9 replay.

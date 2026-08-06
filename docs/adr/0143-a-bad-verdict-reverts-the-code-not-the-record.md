# ADR-0143: A BAD verdict reverts the CODE, never the loop's own record

- **Status:** Implemented
- **Date:** 2026-08-06
- **Deciders:** Oleg
- **Tags:** ops, improvement-loop, safety

## Context

ADR-0063's improvement loop is a closed feedback circuit: the agent makes one change, the scorer holds
it for an ADR-0116 evaluation window and grades it, and a change graded **❌ BAD** is **automatically
reverted** so a losing idea cannot stay in the running book. The revert is the *only* self-correction
arm the loop has — there is no human in the loop to pull a bad change.

**That arm has been dead, and its failure was silent by construction.** Every BAD row in
`reports/improvement-ledger.md` since the must-fix register was introduced carries the note
`⚠️ REVERT FAILED (git conflict): the BAD commit is STILL LIVE and needs a manual revert`. Replaying
each one at its exact scoring point (the parent of its own `chore(ledger)` commit) shows the failure is
not incidental — it is deterministic and total:

| graded-BAD commit | whole-commit `git revert` | conflicted on | scoped to code |
| --- | --- | --- | --- |
| `efccc6502` `e61c7f5aa` `74a47adee` `c20fb0b70` `e3b33679d` `026cda49d` `d51f179a2` `e956dcf46` `39451ce71` | CONFLICT — **9 of 9** | `docs/loop-findings.md`, `reports/last-analysis.md`, `reports/must-fix.md` — **the same three files every time, and nothing else** | **CLEAN — 9 of 9** |

The mechanism is a collision between two of the loop's own rules. `ops/improve-prompt.md` **mandates**
that the agent rewrite `reports/last-analysis.md` and `reports/must-fix.md` and append to
`docs/loop-findings.md` **every cycle, change or not** — that is the compounding memory the loop needs
to survive a cold start. ADR-0116 then holds a change for `MIN_CYCLES` (6) before grading it. So by the
time the scorer runs `git revert`, those three files have been rewritten about six times on top of the
commit being reverted. `git revert` tries to rewind them too, conflicts with certainty, and — because
it is all-or-nothing — **aborts the entire revert, including the code**. The longer and more rigorous
the evaluation window, the more certain the failure: ADR-0116 made the loop's judgment better and its
enforcement impossible in the same stroke.

The cost is not one bad change. It is that **every** change the scorer condemned is still executing.
The book currently trades under the accumulated code of ADR-0133, ADR-0135, ADR-0136, ADR-0139 and
ADR-0142, all graded BAD, none pulled by the machine. Five of those were eventually removed by the
agent spending an entire *subsequent* cycle hand-completing the revert — cycles that produced no new
idea and were themselves graded ❌ BAD or ⚠️ INCONCLUSIVE. And each hand-completion, without exception,
reverted **only the code paths** and left the ADR and findings in place ("keeping the annotated record
and the loop's memory"). The correct behaviour was already known and practised by hand; the scorer
simply did not implement it.

## Decision

We will scope the BAD-verdict revert to the paths that can reach the running app, and **never** rewind
the loop's own append-only record:

```python
REVERT_KEEP_PATHS = ("reports/", "docs/adr/", "docs/loop-findings.md", "docs/loop-playbook.md")
```

`scripts/score-change.py` no longer calls `git revert <sha>`. It lists the commit's paths, drops the
kept ones, and applies the reverse patch restricted to the remainder via `git apply --3way --index`,
then commits it as `Revert "<subject>"` naming the graded-BAD sha and the paths pulled.

Three properties make this safe to run unattended:

- **It refuses rather than destroys.** A working tree with uncommitted changes is not touched at all.
  A genuine conflict *in a code path* restores the tree exactly (`reset --hard` + `clean` scoped to the
  patch's own paths) and reports `False`, so the existing "STILL LIVE, needs a manual revert" note
  still fires. A revert that fails can never masquerade as one that worked — `revertApplied` in the
  audited snapshot continues to record the actual git outcome, distinct from what the verdict asked for.
- **A record-only commit reports success**, because nothing of it was ever in the running code. This is
  a real case: a no-change cycle's finding commit can be the scored sha.
- **The rejected decision stays on the record.** The ADR survives (annotated, for a human to ratify or
  reverse), the ledger row survives, and the finding survives — so the loop cannot relearn a lesson it
  has already paid for. That is the same reason the register exists.

Note this predicate is deliberately **not** ADR-0142's `NON_BINARY_PATHS='^(reports|docs|ops)/'`. They
answer different questions. ADR-0142 asks *can this path change the app binary* (so: should we
rebuild?) and therefore excludes `ops/`, which cron re-reads and never compiles. This ADR asks *is this
path the loop's own record* (so: must a revert preserve it?) — and `ops/` is loop machinery, i.e. code:
a bad change to `ops/improve-loop.sh` must be revertable. Conflating the two would have made the
wrapper permanently unrevertable, which is exactly the class of defect this ADR closes.

**No money, risk or exposure number is introduced, altered or read by this change** (invariant 7 /
ADR-0016). The verdict logic — `classify_verdict`, `evaluate_window`, `T_HURDLE`, `MIN_CYCLES`, the
deadbands, the ledger vector — is untouched, and deliberately so: this ADR repairs how a decision is
*enforced*, not how it is *made*. Nothing that runs inside the trading app is modified, and the
deterministic floor (pre-trade guardrail, firm drawdown breaker, ADR-0016 gates) is not in scope.

## Alternatives considered

**Move the mandated artifacts out of git.** If `last-analysis.md`, `must-fix.md` and `loop-findings.md`
were not versioned, nothing would conflict. They lose their audit trail, the Improve page reads them
from the branch, and `loop-findings.md` derives its whole value from being a committed, reviewable
history. Rejected.

**Keep `git revert` and auto-resolve the record files with `--ours`.** Equivalent in effect but
expressed as conflict resolution rather than intent, and it depends on `git revert`'s all-or-nothing
sequencer state, which is what has to be worked around. A scoped patch says what it means and fails in
one place.

**Have the agent hand-complete failed reverts.** This is the status quo, and it is measurably bad: it
costs a full cycle that produces no new idea, it happened five times, and it silently *did not* happen
the other four. Correcting a losing change must not depend on the model noticing.

**Stop reverting; let the next change supersede the bad one.** This is what has actually been happening
by accident, and it is why the running code is a stack of condemned changes whose interactions no
window can attribute. It defeats ADR-0063's premise that each change is attributable and revertable.

**Squash the record writes into the scored commit.** Would remove the drift, but destroys the
per-cycle heartbeat the UI and the growth target read, and makes no-change cycles unrepresentable.

## Consequences

- **Positive:** the loop's self-correction works again — the first BAD verdict after this lands should
  pull its own code with no manual step. Bad changes stop accumulating in the running book, so an
  evaluation window measures one change against a stable base rather than against the residue of every
  previously-condemned one. Cycles previously spent hand-completing reverts return to doing work.
- **Positive:** the honest failure path is preserved and now distinguishes a *real* code conflict from
  the record collision, so `REVERT FAILED` in the ledger becomes meaningful again rather than constant.
- **Negative:** a revert can now succeed where it previously failed loudly, which means BAD changes will
  actually disappear from the tree between cycles. That is the intent, but it makes the running code
  move without an agent decision — the revert commit and its ledger note are the audit trail for that.
- **Negative:** the reverse patch is scoped by path, so a BAD commit whose code depends on a *later*
  commit's code can still conflict. It fails honestly and asks for a manual revert, as before.
- **Follow-ups:** `scripts/test-score-change.py` is stdlib-only and is **not** run by `./gradlew -Pci
  test` (the scorer is Python, outside the Gradle build). It must be run explicitly; wiring it into the
  loop wrapper's pre-commit check is a separate, small change.
- **Not done here, deliberately:** ADR-0142's own outstanding BAD revert is left for the machine. Its
  verdict is confounded — ADR-0142 stopped the loop restarting the app every cycle, and the restart had
  been flattening the book to `$0.00` gross, so the "loss" the window measured is largely the book
  finally being allowed to hold a position long enough to lose money on an aim whose measured
  expectancy is negative. Hand-reverting it would restore restart-induced dormancy, which CLAUDE.md
  names as a failure state, not a safety. Rewinding it is a decision on its own evidence, not a
  side effect of repairing this mechanism.

## Verification

`python3 scripts/test-score-change.py` — green. It builds a synthetic repo reproducing the exact shape
of all nine failures (a code+ADR commit, then `MIN_CYCLES` of mandated record rewrites on top) and
asserts that the whole-commit revert conflicts on precisely those three record files, that the scoped
revert applies, that the bad code is gone, and that the ADR, the findings and the register survive at
their latest content. It also covers the record-only commit, a genuine code conflict leaving nothing
half-applied, and a dirty tree being refused. `./gradlew -Pci test` green (no Java changed).

**VERIFY-BY next run:** the ledger's next `❌ BAD` row carries a `reverted (...)` note instead of
`⚠️ REVERT FAILED`, and a `Revert "..."` commit appears on `claude/auto-improve` with no agent action.
**FALSIFIED IF** a BAD verdict still reports `REVERT FAILED` naming a record path, or a revert commit
rewinds `docs/loop-findings.md`.

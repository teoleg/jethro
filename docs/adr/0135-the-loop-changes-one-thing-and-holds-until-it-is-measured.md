# ADR-0135: The loop changes one thing and holds until it is measured

- **Status:** Proposed
- **Date:** 2026-08-03
- **Deciders:** Oleg
- **Tags:** loop, methodology, scoring
- **Amends:** ADR-0116 (scoring mechanics), ADR-0063 (cycle shape)

## Context

Week of 2026-07-28 → 08-01, from `reports/run-status.json` + the ledger (all figures code-computed):
the loop shipped **35 changes in 4 trading days** (nearly one per 30-min cycle), scored **0 GOOD /
24 INCONCLUSIVE / 10 MIXED / 18 BAD**, and **12 of the 18 BAD auto-reverts FAILED** on git conflicts —
measured-bad code stayed live for the rest of each session. Week result: **+$32.51 PnL kept against
$288.00 fees paid**. Friday peaked at $740.67 and closed at $32.51, the collapse coinciding with the
six revert-failed cycles. The one clearly positive event was **+$509 earned overnight holding** a $51k
book — which the next day's churn spent.

Four mechanical causes, each visible in the artifacts:

1. **The ADR-0116 hold is prompt-enforced only**, and it leaked: nothing in code stops a new baseline
   while one is pending, and `window_since` counts **market-closed heartbeats** (frozen tape, zero
   delta) as evidence — so a change baselined late in the day had its window filled overnight and
   cleared by the open with a verdict made of nothing.
2. **`git revert <sha>` conflicts whenever later commits touched the same code** — which the every-cycle
   cadence guarantees. The only safety mechanism failed exactly when stacking made it matter.
3. **Churn is invisible to the verdict**: PnL is net of fees, but a change that earns ~zero *while*
   burning fees scores INCONCLUSIVE → kept. Fee burn with nothing to show for it is measured harm.
4. **Holding is treated as idleness**: a no-change cycle has no success state, so the model's bias is
   to act every cycle.

## Decision

We will make the one-change-at-a-time discipline **mechanical, not behavioural**, in
`scripts/score-change.py` + `ops/improve-loop.sh`:

- **Exclusive window (enforced twice).** `baseline` REFUSES to record while a pending baseline exists;
  and the loop wrapper skips the analysis/change step entirely (no model call) while one is pending,
  writing a distinct `holding` heartbeat instead. One change in flight, ever.
- **Only open-market evidence counts.** `window_since` drops `market-closed` heartbeats; `MIN_CYCLES`
  is counted in open-session cycles. Default raised 6 → **12** (~one full US session at the 30-min
  cadence; the week's 6–7-cycle windows produced zero decisive verdicts). Still `PLACEHOLDER — Oleg
  to tune`, env-overridable.
- **Revert cannot fail.** `git revert` stays the first attempt; on conflict the scorer falls back to a
  **path restore**: check out the change's files (excluding `reports/`) at `sha^`, commit. A forced
  restore has no conflict path, so a BAD change is always out of the code.
- **Fee-churn verdict.** The window carries cumulative fees; if the mean per-cycle return is ≤ 0 and
  the change burned more than `FEE_DEADBAND` (default **$25**/window, `PLACEHOLDER — Oleg to tune`,
  env `JETHRO_SCORE_FEE_DEADBAND_USD`) it scores **BAD** — paid to churn, earned nothing.
- **Holding is a first-class state.** The `holding` action/heartbeat says what is being measured and
  how far along (`n/MIN_CYCLES`); the prompt contract states plainly that a deployed book left alone
  while evidence accrues is the loop working, not the loop idle.

## Alternatives

- **Keep prompt-level HOLD, just tune it** — rejected: it already failed in production; a rule the
  model can skip under KPI pressure is not a control (same lesson as invariant 7).
- **Serialize by locking the repo between baseline and score** — rejected: blocks ledger/heartbeat
  writes and maintainer pushes; the exclusivity we need is per *scored change*, not per commit.
- **`git reset --hard` to the baseline commit on BAD** — rejected: destroys ledger rows, heartbeats
  and maintainer commits made since; the path restore reverts only the change's own files.
- **A/B shadow book as the counterfactual** — deferred (already in ADR-0116's register): the real fix
  for market-direction confounding, but it needs a second book and is orthogonal to cadence.

## Consequences

- At most ~1 change per session-day (12 open cycles ≈ 6h). This is the point: the week proved the old
  cadence produces only noise verdicts and fee burn.
- **Honest negative:** learning slows by ~10× in wall-clock terms, and a genuinely bad-but-not-BAD
  change now sits live for a whole session before the next lever can be tried. Mitigated by: BAD (incl.
  fee-churn BAD) still reverts the moment the window closes.
- **Honest negative:** the path-restore fallback can clobber a maintainer edit made to the *same files*
  during the window — restoring to known-good beats leaving measured-BAD live, and exclusivity makes
  the overlap rare; the restore commit names the files so the loss is visible.

## Related

ADR-0063 (loop), ADR-0110/0123 (deploy verification), ADR-0116 (evidence window — amended here),
ADR-0132 (deploy-capital objective the fee rule serves).

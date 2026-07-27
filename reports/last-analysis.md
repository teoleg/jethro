The last two changes were never deployed — the loop scored them against a JVM that predated them, and the failing deploy was rewriting the jar underneath the live app (ADR-0110).

*Every figure below is quoted from the live endpoints, the report, the ledger or the loop's own log;
none is authored here. The ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** Live `/api/risk` `.total` reads total PnL `$5725.58`. The report's SITUATION header reads
the same figure and `+0.00` on the window. `run-status.json` reads `pnl_growth_pct 538.17` against a
`1.0` target — `on_track` true, `stale` false, `underwater` false. The book is not bleeding. It is
frozen: PnL has not moved a cent in four hours, and the three-run growth that clears the target is one
realised unwind (ADR-0107) still sitting in the numerator.

**2. Risk.** Gross `$0.00`, net `$0.00`. `/api/var` returns `"note": "no positions"`. `/api/breaker`
reads `halted: false`. Nothing is at risk because nothing is on. Zero exposure is not safety here — it
is the absence of the thing the objective divides by.

**3. Cause — and this is the whole cycle.** Last cycle's ADR-0109 scored ⚠️ MIXED, "no material change".
It never ran. The running JVM (pid 912480) started `Mon Jul 27 11:19:32 2026`; commit `905b343` was made
at `12:18:59`. `logs/improve-2026-07-27.log` records the reason at both the 10:00 and 12:00 cycles:
`Failed to restart jethro.service: Unit jethro.service not found.` → `deploy command FAILED — app NOT
restarted`. The crontab was re-installed carrying the **systemd example from `ops/README.md`** on a box
that runs the app from `scripts/run-local.sh`. So **ADR-0108 and ADR-0109 were both built, committed,
pushed, baselined and scored against a binary that never contained them** — two ⚠️ MIXED verdicts that
are true statements about the old jar and say nothing whatever about the changes they name. Worse, the
`./gradlew :app:bootJar` half of that command kept succeeding: `app-0.1.0-SNAPSHOT.jar` was rewritten at
`12:20` underneath the live JVM, which has been throwing `ClassNotFoundException:
org.springframework.util.PatternMatchUtils` ever since (12:59, in the report's stack traces). A failed
deploy was actively damaging the running process.

**4. Danger.** Not bleeding, exposure not rising, breaker clear — no live danger state. The danger is
epistemic and worse for being quiet: with no human in the loop, a silent deploy failure does not waste
cycles, it **manufactures evidence**. The next agent reads MIXED, concludes the idea failed, and moves to
a different lever — and every artefact it reads is internally consistent.

**5. Order-level post-mortem.** `recent_orders` covers 12:17–12:43 and stops there — the last fills
predate this window entirely, consistent with a book that went flat and stayed flat. Two triggers stand
out and both are on the old binary: `ALPHA JPM SELL` REJECTED 45 consecutive times with `no market data
for JPM`, and `ALPHA MSFT BUY` CANCELLED repeatedly by `fusion re-plan — passive order superseded by a
fresh target (ADR-0084)`. `orders_by_status` reads FILLED 3052 / CANCELLED 545 / REJECTED 45. Nothing
here can be attributed to ADR-0109, which was not running.

**6/7. Change vs market.** Nothing to split. Gross was `$0.00` for the whole window, so there were no
positions for the market to move and none the change could have opened or resized. **Claimed for last
cycle's change: nothing** — and now demonstrably so, since it never executed.

## What I changed

The loop verifies every link in its own circuit except the one that makes the rest meaningful. Step 5 of
`ops/improve-loop.sh` ran `JETHRO_DEPLOY_CMD` and moved on; its success was never checked and nothing
downstream ever asked whether the binary being measured contained the commit being named.

It now asks the app. After the deploy, the loop polls `/api/ops/jvm` and computes the answering process's
boot time as `now − uptimeSeconds`; the deploy is verified only if the app answers **and** that boot time
is at or after the moment the deploy started. That is deploy-mechanism agnostic — no pidfile, unit name or
container convention assumed — and it is the only question the scorer's validity rests on. If the check
fails, the loop falls back to the repo's own `scripts/svc.sh restart app`, which is right here for two
reasons that are the decision rather than incidental: it **stops the app before rebuilding**, so the jar is
never rewritten under a live JVM, and `run-local.sh` re-reads `local.env`, so provider, keys, heap and
profile come back identical — a restart that silently reverted `PROVIDER` to the script default would be a
feed switch, an invariant-8 epoch event, not a restart. `JETHRO_DEPLOY_CMD` unset now means that repo
default rather than "do nothing"; `=none` is the explicit review-before-live mode; `loop-control.sh` no
longer bakes an empty value into the cron line (which would have overridden the new default with "deploy
nothing"), and the systemd example is gone from the docs it was copied from. When neither command
restarts the app, the log now says the thing the next reader needs: *the next cycle's verdict will
describe code that never ran.* ADR-0110 is Proposed in the same commit; `./gradlew -Pci test` green.

**What I deliberately did not do.** I did not edit `scripts/score-change.py` to refuse to score an
undeployed commit — that is the deeper fix and the right eventual home for the rule, but the scorer is the
invariant-7 authority for every money number in the ledger and should not move in the same breath as the
plumbing that feeds it (deferred register). I also did not rewrite the crontab: it is not in the repo, and
`crontab -l` stopped returning content partway through this cycle, so rewriting it from here risked
deleting the loop's own schedule. The bad line stays; the verification above makes it harmless. The
standing recommendation for Oleg is one edit — drop `JETHRO_DEPLOY_CMD='…'` from the cron line so the repo
default applies directly instead of via a failure and a fallback.

**Honest caveat, stated now rather than discovered later.** The next scored window contains **three**
deployments' worth of code: ADR-0108 and ADR-0109 finally reaching the JVM, plus this change. Its vector
will not attribute cleanly to any one of them. Read the next verdict as "what the last three cycles did,
now that they are actually running" — and expect gross to rise from `$0.00`, which is what ADR-0109
predicted and could not demonstrate.

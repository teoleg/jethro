# ADR-0110: A change that never reached the JVM is not a change — the loop verifies its own deploy

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** ops, improvement-loop, deployment, adr-0063

## Context

The ADR-0063 improvement loop is a closed feedback circuit: report the live app → change one thing →
commit → **rebuild and restart** → next cycle scores the live app against the recorded baseline and
reverts the commit if the vector got worse. Every link is checked except one. Step 5 runs
`JETHRO_DEPLOY_CMD` and moves on. Its success is never verified, and nothing downstream ever asks
whether the binary the scorer is measuring contains the commit the scorer is naming.

That link broke on this box. The crontab was re-installed carrying the **example** deploy string from
`ops/README.md` — `./gradlew :app:bootJar -x test && sudo systemctl restart jethro` — on a box that
runs the app from `scripts/run-local.sh`, where no `jethro.service` exists. Since then every cycle has
logged `Failed to restart jethro.service: Unit jethro.service not found` / `deploy command FAILED`,
and the loop carried on regardless. Two commits (ADR-0108, ADR-0109) were built, committed, pushed,
baselined and **scored** against a JVM that started before either of them existed. Both scored
⚠️ MIXED, "no material change" — a true statement about a binary that never ran the change, recorded in
the ledger as if it were a finding about the change. Worse: the `bootJar` half of that command kept
*succeeding*, rewriting `app/build/libs/app-0.1.0-SNAPSHOT.jar` underneath the **live** JVM, which then
threw `ClassNotFoundException: org.springframework.util.PatternMatchUtils` on every class Spring Boot
had not yet loaded lazily. A failed deploy was actively damaging the running process.

There is no human in this loop, so a silent deploy failure does not merely waste cycles — it
manufactures evidence. The next agent reads a MIXED verdict, concludes the idea was wrong, and moves on
to a different lever. The failure mode is self-reinforcing and invisible, and no amount of better
diagnosis upstream can detect it, because every artefact the agent reads is internally consistent.

## Decision

**We will verify the deploy against the running process rather than against the deploy command's exit
status**, and make the repo's own restart the default and the fallback.

- After running the deploy, `improve-loop.sh` polls `/api/ops/jvm` and computes the epoch second the
  answering process booted (`now − uptimeSeconds`). The deploy is verified only if the app answers **and**
  that boot time is at or after the moment the deploy started. This is deploy-mechanism agnostic — no
  pidfile, unit name or container convention is assumed — and it asks the only question that matters:
  *is a process that started after this deploy serving the endpoints the scorer will read?*
- If it is not, the loop falls back to `scripts/svc.sh restart app`, which is always correct on this
  box: it **stops the app before rebuilding**, so the jar is never rewritten under a live JVM, and
  `run-local.sh` re-reads `local.env`, so provider, keys, heap and profile come back **identical** —
  a restart that silently reverted `PROVIDER` to the script default would be a feed switch, which is an
  invariant-8 epoch event, not a restart.
- `JETHRO_DEPLOY_CMD` **unset** now means that repo default rather than "do nothing"; `=none` is the
  explicit review-before-live mode. `loop-control.sh` no longer bakes an empty value into the cron line
  (which would have overridden the default with "deploy nothing"), and the systemd example is removed
  from the docs that were copied from.
- When neither the configured command nor the fallback restarts the app, the loop says so in the words
  the next reader needs: *the next cycle's verdict will describe code that never ran.*

## Alternatives considered

**Compare a build identity (git sha baked into the jar) with the running app's `/actuator/info`.** This
is the strictly correct check — it verifies the *code*, not merely that *a* restart happened — and it
would also catch a restart onto a stale jar. Rejected for now only because it needs a build-info
plumbing change in `app/`; the uptime check catches the failure that actually occurs (no restart at all)
with no build changes. Revive it if a deploy is ever seen to restart onto the wrong jar.

**Make the scorer refuse to score a commit it cannot prove was deployed.** This is the deeper fix and
the right eventual home for the rule — a verdict on undeployed code is not a measurement. Deferred, not
rejected: `score-change.py` is the invariant-7 authority for every money number in the ledger and
should not be changed in the same breath as the plumbing that feeds it. Tracked in the deferred
register; the trigger to build it is the first deploy failure that survives the fallback above.

**Just fix the crontab.** It removes today's symptom and leaves the box one bad copy-paste from the same
silence. The loop is unattended; it has to detect this itself. The crontab is deliberately **not**
touched by this change — it is not in the repo, and rewriting it from an agent that cannot reliably read
it back risks deleting the loop's own schedule. The line still carries
`JETHRO_DEPLOY_CMD='… && sudo systemctl restart jethro'`; the verification above makes that harmless
(one failed command, then the fallback), and the standing recommendation is to drop the assignment from
the cron line so the repo default applies directly.

**Fail the whole cycle on an unverified deploy.** Attractive — loud failure beats quiet corruption — but
it would also throw away the report, the score and the heartbeat, which are correct and are exactly what
a human debugging the box needs. The loop continues and records the doubt instead.

## Consequences

- **Positive:** every future verdict describes code that was actually running; a bad `JETHRO_DEPLOY_CMD`
  degrades to a working deploy plus a loud log line instead of weeks of fabricated MIXED verdicts; the
  jar is no longer rewritten under a live JVM; the loop works out of the box with nothing configured.
- **Negative — and this one is real:** the fallback restarts the app with a command the operator did not
  choose. On a box where `svc.sh` is *not* how Jethro should be started, that is an unwanted restart. It
  is bounded (the repo's own script, same config, `=none` opts out entirely) and it is the conservative
  side of the trade: an unexpected restart is visible and recoverable, a change that silently never ran
  is neither.
- A verified deploy now costs up to `JETHRO_DEPLOY_TIMEOUT` (default 300 s) of polling before the cycle
  ends, and a failed one costs that twice — a slower cycle tail on an already 2-hourly schedule.
- The next scored window contains **three** deployments' worth of code — ADR-0108 and ADR-0109 finally
  reaching the JVM, plus this change — so the vector move will not attribute cleanly to any one of them.
  Stated here rather than discovered later.

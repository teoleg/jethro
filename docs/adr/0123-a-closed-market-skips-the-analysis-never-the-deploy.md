# ADR-0123: A closed market skips the ANALYSIS, never the DEPLOY

- **Status:** Proposed
- **Date:** 2026-07-29
- **Deciders:** Oleg
- **Tags:** backend, ops, loop

## Context

ADR-0110 established the rule the improvement loop's whole feedback circuit rests on: *a change
that never reached the JVM is not a change.* It hardened step 5 — run `JETHRO_DEPLOY_CMD`, then
verify against the app's own uptime that a process started by this deploy is the one serving the
endpoints the next cycle will read. That closed the door it was watching. It did not notice there
was a second door.

ADR-0115's market-hours gate skips a cycle when the US session is closed: no report, no Opus call.
That branch still does useful git work — it fetches and fast-forwards the branch to origin, so a
change pushed to `claude/auto-improve` overnight lands in the working tree — and then `exit 0`s
before any deploy. Worse, it captured its `BEFORE` sha *after* the fast-forward. This is exactly
the bug §2 of the open-market path already fixed for itself on 2026-07-28, left standing in the
closed path. The consequence is not a delay, it is a permanent loss: by the time the next
open-market cycle takes its own `BEFORE`, the commit is already an ancestor of HEAD, so no later
`git diff BEFORE AFTER` can ever see it and `CODE_CHANGED` is empty forever. **A change that
arrives while the market is closed is undeployable in principle.**

It has already cost real money-making time. The owner's ADR-0122 exploration-mode directive — turn
off the edge gate and the backtest veto so the paper book acts — was committed at 22:24 UTC on
2026-07-28, after the 20:00 close. Twenty-odd market-closed cycles fast-forwarded it into the tree
and deployed nothing. At the 2026-07-29 open the JVM was still the process that booted 15:35 UTC
the previous day: `/api/fusion/targets` reported `edgeGate.mayIncrease:false` with a populated
`sources` list — i.e. `gateSupplier != null`, the running config's gate ENABLED — while the repo
said `jethro.fusion.edge-gate.enabled=false`. Ten minutes into the session every `deltaQty` was
exactly `0` and gross exposure was `$0.00`. The desk was dormant for a reason no amount of signal
work could have fixed, and the ledger meanwhile scored `68ff73eb2` ⚠️ INCONCLUSIVE over 38 cycles
of a book that placed zero orders — ADR-0110's manufactured-evidence failure, arriving by the other
door.

## Decision

**We will deploy on any cycle that pulls in a code change, including a market-closed one.** The
market gate suppresses the *analysis* — the report and the model call — and nothing else. Two
specifics: the closed branch takes its `BEFORE` sha **before** the fast-forward, matching §2; and
the deploy-and-verify block (old steps 4b + 5) becomes one function, `deploy_if_code_changed`,
defined once and called from both branches, so the two paths cannot drift apart again. The
`reports/`-only filter is unchanged, so a heartbeat or ledger write still bounces nothing.

A closed market is in fact the *cheapest* time to restart: no tape to miss, no live measurement to
wipe, and hours of warm-up before the open — strictly better than the mid-session restart that is
the only alternative once a change is stranded.

## Alternatives considered

**Skip the whole cycle when closed, and let the operator pull and restart by hand.** This is the
status quo dressed as a policy, and it contradicts the loop's premise: there is no human in the
loop (ADR-0063). It also failed silently rather than loudly, which is the property that made it
expensive — nothing in the log said "a change is stranded".

**Make the open-market `BEFORE` reach further back — e.g. diff against the sha the running jar was
built from.** This is the *better* fix and it is deferred, not rejected: it would also catch a
restart onto a stale jar, and ADR-0110 already registered build-identity verification (git sha in
the jar vs `/actuator/info`) as deferred work. It loses here only because it needs a build-stamping
change to ship first, and the book is dormant now. Revive it when the jar carries its sha.

**Teach `scripts/score-change.py` to refuse to score a commit it cannot prove was deployed.** The
right eventual home for the rule and already in the deferred register from ADR-0110. Still out of
scope for the same reason it was then: the scorer is the invariant-7 authority for every money
number in the ledger and should not move in the same breath as the plumbing that feeds it. This
ADR makes the stranding *rarer*; that one makes its verdicts *honest*. Both are wanted.

## Consequences

- **Positive:** an owner directive pushed after the close is live by the next cycle instead of
  never. Restarts move off the trading session onto closed hours, where they cost no measurement.
  One deploy implementation instead of two, so the paths cannot diverge again.
- **Negative, and it is real:** the loop may now restart the app unattended overnight, when nobody
  is watching and the report that would have caught a bad boot is not generated. A change that
  crashes on startup takes the app down until the next open-market cycle notices. The uptime
  verification detects the failure and logs it, but nothing rolls back. Accepted because the
  failure it replaces — running the wrong binary for a full session, silently — is worse and has
  now happened.
- **Negative:** this cycle's scored window contains two deployments' worth of behaviour (this fix,
  plus ADR-0122 finally reaching the JVM), and the second will dominate the vector. That window's
  verdict does not attribute cleanly to either; it is recorded in `docs/loop-findings.md` so the
  next reader does not mistake it for evidence about this change.
- **Follow-ups:** build-identity verification (ADR-0110's deferred row) and a scorer that refuses
  to score an undeployed commit. Both stay in `docs/deferred-register.md`.

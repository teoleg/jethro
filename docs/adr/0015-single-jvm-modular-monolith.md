# ADR-0015: Single-JVM modular monolith — one app now, extraction seams preserved

- **Status:** Proposed (amends the deployment topology of ADR-0003/0014; module architecture unchanged)
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** architecture, deployment, cost

## Context

After ADR-0014 the design still had five JVMs (trading-core, order, reference-data,
ui-gateway, finops). For a solo developer in the build-out phase, five processes means
five deploy units, five heaps to size on a small dev node (ADR-0013), and five things to
restart while iterating — buying failure isolation that matters little while execution
is *simulated* and no real money is at risk. The owner wants one process now with a
clean path back to many. The danger of monolith-first is boundary erosion: modules that
quietly grow into each other until extraction becomes a rewrite.

## Decision

We will ship **one deployable JVM — `app`** — assembling all modules, with the seams
kept real by two rules and one hard trigger:

1. **Module isolation is enforced, not hoped for.** Every module (market-data,
   algo-engine, risk-pnl, order, reference-data, ui-gateway, finops) is its own Gradle
   module; modules may depend only on `common-domain`, `common-messaging`, and each
   other's published interfaces — never internals. Verified by Gradle dependency
   constraints + an ArchUnit rule set that fails the build on violations.
2. **Cross-domain flow goes through Redpanda topics even in-process.** Order flow,
   `ai.decisions`, `risk.snapshots`, `md.marks` are produced/consumed via the log exactly
   as if the modules were separate processes (contracts, idempotency, and replay
   semantics are exercised from day one). The ADR-0014 market path is the one exception:
   ticks stay on the in-process ring buffer inside the trading-core module cluster.
   Extraction of any module = give it its own `main()` + compose entry; zero contract
   changes.
3. **Hard trigger (non-negotiable): the order module moves to its own JVM before any
   live-broker / real-money connection.** Simulated fills may share the process; real
   orders may not die with a strategy bug. Soft triggers for other modules: measured GC
   interference with the market path, WebSocket fan-out load, or a second developer.

Deployment becomes: `app` + Redpanda + Postgres — three containers locally and on the
dev node. The dev instance can start at t4g.medium (~$25/month), one size down from
ADR-0013's estimate.

## Alternatives considered

**Keep five JVMs (ADR-0014 topology).** Correct end-state and real failure isolation —
but paid now, during the phase where every process boundary slows iteration and nothing
real is at stake. Deferred per module behind the triggers above; this ADR is a topology
change, not an architecture change.

**Three JVMs (trading-core / order / ui-gateway+rest).** The compromise previously
suggested; keeps the order boundary hot. Rejected in favor of one: while execution is
simulated the order boundary protects nothing, and the single hard trigger reinstates
it exactly when it starts protecting something.

**True monolith (single Gradle module, direct method calls, no log in the middle).**
Simplest possible start, but cross-domain calls would bypass the event contracts —
idempotency and replay would be retrofitted later under pressure, and extraction becomes
a rewrite. Rejected; the log-in-the-middle tax is small and is the whole escape plan.

## Consequences

- Positive: one artifact to build, run, debug; fastest possible iteration loop; smaller
  dev node; contracts and duplicate-delivery tests exercised from the first commit;
  extraction is mechanical (new `main()` + compose entry).
- Negative: **zero process-failure isolation for now** — any module bug or OOM takes
  down everything including (simulated) order handling; one shared heap means GC
  discipline on the market path matters even more (chatty modules can trigger pauses
  that hit tick processing); boundary erosion remains a risk if the ArchUnit rules are
  weakened "temporarily"; in-process Kafka round-trips add a few ms to flows that could
  be method calls — accepted as the price of real seams.
- Follow-ups: ArchUnit rule set lands with the first module (build-order step 1–2);
  overview/build order updated to the single-app topology; the order-module extraction
  trigger recorded in the Risk section of any future live-trading ADR.

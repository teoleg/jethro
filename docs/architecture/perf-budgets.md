# Performance budgets and guardrail tests

Status: living document. The budgets here back the perf guardrail tests that run in the
ordinary unit-test task on every build. Related: [`overview.md`](overview.md) (market-path
design, ADR-0014), the hot-path conventions in `CLAUDE.md`.

## Philosophy: guardrails, not benchmarks

These tests exist to catch **order-of-magnitude regressions** — an accidental allocation
per tick, a lock or O(n) scan slipped onto a per-tick path, BigDecimal creeping into the
ring-buffer consumers — not to rank nanoseconds. Two consequences:

1. **Floors sit 20–50× below healthy measurements.** CI runs on 2-vCPU shared runners
   with unpredictable neighbours; a floor near the real rate would flake. A floor 20–50×
   under it never fires on scheduler noise but still fails loudly on the regressions that
   matter, which are 10–1000×.
2. **Measured rates are printed** (`[perf] ...` lines in test output) so trends are
   visible in CI logs even while assertions stay green. Watch them drift before they trip.

JMH is the right tool for real benchmarking and is deliberately **not** used: the offline
build cannot resolve new dependencies, and these guards must run inside `gradle build` on
every commit. Plain JUnit with explicit warmup loops is accurate enough for
order-of-magnitude guards.

Reference hardware for the budgets below: the Raspberry Pi 5 dev target (4× Cortex-A76 @
2.4 GHz) — the weakest machine the full stack must run on — and the 2-vCPU GitHub-hosted
CI runner. A typical x86 dev box is faster than both.

## Allocation budgets (hot path)

Per ADR-0014 and the hot-path convention, the tick path — feed adapter → ring buffer →
mark cache → algo/risk consumers — allocates **zero bytes per tick** (one holder
allocation per instrument lifetime). Verified with the JVM's precise per-thread TLAB
accounting (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`) after a warmup
pass, with a fixed 128 KB slack over 1,000,000 ops (~0.1 B/op) for JIT/deopt noise. A
single real allocation per op is ≥16 MB over the run — the guard cannot miss it.

| Path | Test | Budget |
|---|---|---|
| `TickRingBuffer.offer` + `poll` | `HotPathPerfTest.ringBufferOfferPollAllocatesNothingPerTick` | 0 B/op (+slack) |
| `MarkCache.update`, accepted, jump guard ON | `HotPathPerfTest.markCacheAcceptedUpdateAllocatesNothingPerTick` | 0 B/op (+slack) |
| `MarkCache.update`, quarantined reject | `HotPathPerfTest.markCacheQuarantineRejectPathAllocatesNothingPerTick` | 0 B/op (+slack) |

The quarantine reject path is guarded separately because it is exactly the path that runs
hot during an incident (a quarantined instrument keeps ticking at full feed rate until an
operator clears it) — an allocation there would turn a data problem into a GC problem.

## Throughput floors

| Path | Test | Floor (asserted) | Healthy (indicative) | Production need |
|---|---|---|---|---|
| Ring buffer offer+poll pair | `HotPathPerfTest` | 1,000,000 pairs/s | ~175M/s | ~500/s (25 instr @ 50 ms + quotes) |
| `MarkCache.update` (guard on) | `HotPathPerfTest` | 1,000,000 ops/s | ~45M/s | ~500/s |
| Correlated sim tape, 25 instruments + bid/ask synthesis | `SimThroughputPerfTest` | 2,000 ticks/s | ~1.7M ticks/s | 20 ticks/s |
| `RiskProjection.applyFill` | `RiskSnapshotPerfTest` | 2,000 fills/s | ~300k/s | bursts of ~10/s |
| Order submit→fill orchestration (in-memory store) | `OrderPathPerfTest` | 500 orders/s | ~180k/s (5.6 µs/order) | ~1/s |

"Healthy" columns are the rates measured on the x86 dev container at the time the guards
were written (2026-07); expect the Pi 5 to land 3–10× lower and CI runners anywhere in
between — all still far above every floor.

Notes:

- **Sim tape:** one "tick" is the full per-tick cost — regime transition, Cholesky factor
  draw, shared Student-t scale (ν gaussians), per-instrument idio + `exp`, and scaled-long
  bid/ask synthesis for all 25 instruments. The floor is 100× the production 50 ms tape,
  so time-compressed replays (and a future faster tape) have headroom by construction.
- **Order path:** production is Postgres-bound (~1 ms/round trip); the in-memory
  measurement isolates orchestration (state machine, CAS, arrival-price capture, simulated
  execution, publish), which must stay negligible next to one DB round trip. The realistic
  regression is an O(all-orders) scan per submit as books grow — that is what the floor
  catches. `UUID.randomUUID` (SecureRandom) is part of the measured path on purpose: it is
  part of the real submit cost.

## Latency budgets (boundary calls)

| Path | Test | Budget | Healthy (indicative) |
|---|---|---|---|
| `RiskProjection.snapshot`, 240 positions / 4 books | `RiskSnapshotPerfTest` | 50 ms avg | ~0.4 ms |

`snapshot()` is BigDecimal by design (invariant 1 — it is a boundary, not the tick path)
and feeds the UI poll, the pre-trade guardrail and EOD; 240 positions is ~15× the seeded
universe. The budget keeps it interactive; the realistic regression is per-snapshot
re-pricing or O(n²) grouping.

## What is deliberately not covered here

- **End-to-end tick→UI latency** needs the full stack and belongs to the CI integration
  job (FullStackIT already bounds it indirectly via its await windows), not unit-scale
  guards.
- **Ollama/AI throughput** is model- and host-bound and explicitly off the tick path
  (invariant 7); the IT asserts real generation happens, nothing asserts how fast.
- **Postgres/Kafka round trips** are environment-bound; the compose stack and CI cover
  them functionally. If they need budgets later, that is a soak test on the Pi — tracked
  in [`docs/deferred-register.md`](../deferred-register.md).

## Updating budgets

Budgets change via PR like any other contract: if a legitimate feature makes a path
slower (e.g. a richer jump guard), adjust the floor **in the same PR** with the measured
before/after in the description. Never delete a guard to make a build green.

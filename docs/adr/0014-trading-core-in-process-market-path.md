# ADR-0014: Fuse the market path into trading-core — in-process ticks, durable log for transactions only

- **Status:** Accepted (amends ADR-0003: market-path services fused; order/reference/UI services unchanged)
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** architecture, latency, storage, cost

## Context

ADR-0003 ran every tick through the broker between separate services (gateway → algo →
risk). On review that hop buys nothing the platform needs: ticks are ephemeral
last-value data whose consumers all want them *now*; the broker added 1–5ms per hop and
put the highest-volume stream on a single Redpanda node (ADR-0012/0013). The owner's
"shift left" instinct — process off the feed immediately — is the conventional trading
architecture. What must survive the reshape: backtesting and incident replay need tick
history (ADR-0005/0010), risk/PnL must recover across restarts, "what did the algo see"
must stay answerable (AI audit, ADR-0010), and the transactional order flow keeps its
durable at-least-once + idempotent contract (ADR-0012). The critical distinction:
**ticks are ephemeral, transactions are sacred.**

## Decision

We will fuse the market path into **one JVM process, `trading-core`**, and keep the
durable log for transactional flow and snapshots only:

1. **`trading-core`** contains three modules (separate Gradle modules, same process):
   market-data gateway (SPI + adapters, ADR-0009 unchanged), algo engine (ADR-0010
   unchanged), risk-pnl. They communicate over an **in-process ring buffer** with an
   explicit conflation/drop policy (keep-latest per instrument; drops counted and
   exposed as metrics — never silent). **Exactly one feed session, inside this process.**
2. **Durable topics (Redpanda):** `orders.*` + `fills` (unchanged transactional
   contract; fills remain the source of truth for positions), `ai.decisions` (each event
   **embeds the market-context snapshot/hash it decided on** — the decision log carries
   its own evidence), `risk.snapshots` (~1/sec conflated — restart/PnL history + UI),
   `md.marks` (~1/sec last-value per instrument — UI and any future consumer).
3. **Ticks: archived, not brokered.** A write-behind archiver module batches raw ticks
   directly to S3 Parquet — off the hot path, drop-tolerant under pressure, gaps counted.
   Backtest/replay = a **replay adapter behind the same feed SPI** playing Parquet
   through the identical pipeline.
4. **LMDB (via lmdbjava) on local EBS** as trading-core's embedded state store: the
   `eventId` dedupe table (invariant 6) and warm-restart caches (last marks with
   timestamps, flagged stale until the feed refreshes). **Derived-state-only rule:
   losing the LMDB file may cost restart time, never data** — anything whose loss would
   lose data belongs in Postgres or on the log. Optional scheduled `mdb_env_copy`
   snapshot → S3. **LMDB on S3-mounted filesystems is rejected outright** (no random
   writes/mmap/lock semantics on s3fs/Mountpoint — corruption, not slowness).
5. **Amended invariant:** events are the only *inter-process* data path; inside
   trading-core the ring buffer rules, and module boundaries (symbology containment,
   invariant 2) are enforced in code review.

Recovery contract: positions from `fills`, marks from feed reconnect (LMDB warm cache
until then), PnL history from `risk.snapshots`. Nothing depends on per-tick persistence.

## Alternatives considered

**Keep the ADR-0003 service split with ticks on the broker.** Process isolation and
independent scaling, but 1–5ms/hop and heavy load on a single-node broker for fan-out we
can do in-process. Rejected now; the ring-buffer interface is deliberately shaped so a
module can be split back out over Redpanda if one ever needs independent scaling — the
escape hatch is the interface, not a rewrite.

**No tick persistence at all (original proposal).** Simplest, but kills backtesting,
incident replay, and post-fix PnL verification the platform is built around. Rejected;
the async archiver keeps persistence without the hot-path cost.

**Ticks on Redpanda with short retention instead of direct-to-S3 archive.** Buys a
replayable live topic, but puts full tick volume back on the single node and still
needs an S3 sink for history. Rejected; `md.marks` at 1Hz covers live consumers.

**RocksDB instead of LMDB.** Stronger write throughput, but LSM compaction stalls add
jitter to the hot path; LMDB's read-optimized COW tree and single-writer model match a
single fused process exactly. Rejected (Chronicle Map noted as the JNI-free fallback).

## Consequences

- Positive: tick-to-decision path drops from milliseconds to microseconds; broker
  carries only low-rate transactional/snapshot traffic (single node lasts longer);
  backtest = same code + replay adapter; warm restarts; dedupe survives restarts.
- Negative: shared JVM — a strategy bug or GC pause hits risk calc too (mitigation:
  deterministic guardrails stay trivial, hot-path allocation discipline per ADR-0002);
  single-machine ceiling for the market path; new raw-tick consumers must join the
  process or accept 1Hz `md.marks`; archiver gaps are possible under pressure (counted,
  visible); one more embedded technology (LMDB) to learn.
- Follow-ups: mark ADR-0003 amended; design the ring-buffer/bus interface early (it is
  the future seam); `ai.decisions` schema gains the context-snapshot field; drop/gap
  metrics wired into the monitoring UI.

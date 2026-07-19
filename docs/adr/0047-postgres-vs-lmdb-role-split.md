# ADR-0047: Keep PostgreSQL as the system-of-record; LMDB stays derived-only

- **Status:** Proposed
- **Date:** 2026-07-19
- **Deciders:** Oleg
- **Tags:** data, architecture, cost

## Context

We run two stores and the question is fair: do we need both? The argument for LMDB-only is that
in the dev stack Postgres and the LMDB file sit on the **same** node's filesystem, so they share the
same crash-durability; LMDB is faster (mmap COW B+tree, single-writer, no LSM stalls per ADR-0014);
its one drawback is "no SQL." If that were the whole picture, one embedded store would win.

But the "same filesystem, same resilience" equivalence is a **dev-only artifact**. ADR-0013 makes
dev = the compose stack on one EC2 node (everything on one disk *on purpose*, to be cheap), while
the **production shape is Aurora** — managed, multi-AZ replicated, automated backup + point-in-time
recovery. LMDB is a single memory-mapped file on one node's local EBS: crash-safe locally, but
single-AZ, single-node, no replication, no PITR, no managed backup. Collapsing the two only *looks*
free because dev deliberately co-locates them; it discards the production durability posture.

The two also hold categorically different things. **LMDB** (invariant 9, derived-only): the
`eventId` dedupe table and the warm-restart mark cache — losing the file costs restart time, never
data. **Postgres** (32 Flyway migrations): the `fills` ledger that is the source of truth for
positions (invariant 3), orders/slices/TIF/fees, hypothesis records + scored outcomes, TCA, daily
closes + firm/book equity, swap trades, mark-quarantine, reference data, and the chat audit trail.
That is a relational, multi-entity, ad-hoc-queried, versioned-migration system-of-record.

## Decision

We will **keep PostgreSQL (Aurora in prod) as the relational system-of-record and keep LMDB as the
embedded, derived hot-state store** — reaffirming ADR-0005 and ADR-0014; they are complementary,
not redundant. LMDB-only is **rejected**. Three reasons, in order of weight:

1. **Service extraction needs a network database (decisive).** ADR-0015 requires the `order` module
   to move to its own JVM before any real-money broker connection, and ADR-0013's prod shape runs
   services as separate Fargate tasks on separate hosts. LMDB is **embedded, single-writer,
   same-host** — it cannot be the shared transactional store two processes on two hosts read and
   write. Postgres/Aurora is a network database built for exactly that. LMDB-only would wall us into
   a permanent single-process monolith, killing the extraction path and the real-money story.
2. **The domain is relational, not "SQL as a nicety."** books↔positions↔instruments↔orders↔fills↔
   hypotheses↔outcomes want ACID multi-row transactions, foreign keys, secondary indexes,
   aggregations, Flyway schema evolution, and ad-hoc risk/audit queries (the diagnostics export runs
   straight SQL). On an ordered KV store you re-implement every index, join, migration, and
   cross-table transaction by hand — a bespoke RDBMS, in the money path, for a financial ledger.
3. **Durability/compliance of the record.** A trading platform needs a backed-up, PITR-capable,
   forensically queryable store for fills, orders, and audit. A single local mmap file gives none of
   replication, managed backup, or PITR — and by invariant 9 it was never meant to.

The valid kernel — a Postgres container is real weight in single-node dev — is already answered by
the existing **persistence-off mode** (`PersistenceConfig`): a pure-sim demo runs with no Postgres,
degrading gracefully (no order ledger, no VaR history, no audit). That is the lightweight path, not
amputating the system-of-record.

## Alternatives considered

**LMDB-only (the proposal).** Fewer moving parts and faster local reads. Rejected: it is embedded
and same-host, so it cannot back the ADR-0015 service split or a multi-AZ prod durability posture,
and it forces a hand-rolled relational/transaction/migration layer over a KV store for the money
ledger. It also inverts invariant 9 — making a derived, losable store the source of truth.

**Postgres-only (drop LMDB).** One store, fully relational. Rejected for the hot path: per-tick
dedupe and warm-restart lookups want an in-process, allocation-light, single-writer local store —
a network round-trip per tick to Postgres is the latency ADR-0014 fused the market path to avoid.
LMDB earns its place precisely by being derived, embedded, and off the wire.

**Swap Postgres for another embedded SQL engine (SQLite/DuckDB) to lose the container.** Keeps SQL,
drops the daemon. Rejected: still embedded/same-host (same service-split wall as LMDB), and it trades
Aurora's managed replication/backup for a file — re-litigating ADR-0005 to save a dev container the
persistence-off mode already saves.

## Consequences

- Positive: the record survives node loss and is reachable by the services we intend to split out;
  the hot path keeps its embedded, off-wire dedupe/restart store; no re-implementation of relational
  machinery; the question is settled with reasons, so it stops recurring.
- Negative: we carry two stores and keep the log↔projection consistency discipline (ADR-0005's known
  cost: the projector is the only writer). Dev runs a Postgres container unless you choose
  persistence-off. LMDB's speed advantage is deliberately confined to derived state, not exploited
  for the system-of-record.
- Follow-ups: if a *measured* dev-ergonomics pain remains, make persistence-off the default for the
  pure-sim demo profile; revisit only if ADR-0015 is ever formally abandoned (which would remove
  reason #1) — capture that in a superseding ADR, not by drift.

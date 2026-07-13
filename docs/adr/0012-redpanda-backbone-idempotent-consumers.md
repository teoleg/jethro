# ADR-0012: Redpanda as the Kafka-API backbone; at-least-once + idempotent consumers

- **Status:** Accepted (supersedes ADR-0004)
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** messaging, aws, cost

## Context

ADR-0004 chose Kafka with MSK Serverless in AWS and cited exactly-once semantics as an
ecosystem advantage. Two things changed on review. **(1) Trust in exactly-once:** the
owner (rightly) does not want correctness resting on broker EOS. Kafka's exactly-once
covers only Kafka-in→Kafka-out processing, never external side effects (DB writes, order
placement), and adds fragile failure modes (zombie producers, transaction timeouts).
Trading systems conventionally treat the broker as at-least-once and make consumers
idempotent. **(2) Cost:** MSK Serverless is ~$0.75/cluster-hour ≈ **$550/month before
the first message**; smallest provisioned MSK is still ~$70–90/month — the largest
single line in the dev budget (ADR-0011), buying durability guarantees dev doesn't need.

## Decision

We will keep the log-based, Kafka-**API** architecture of ADR-0003/0004 but change the
broker and the delivery contract:

1. **Redpanda everywhere.** Docker locally (unchanged); in AWS, **one self-hosted
   Redpanda node** (Community Edition — source-available BSL, free to self-host) on the
   dev host (ADR-0013), ~$0 marginal cost. Redpanda's built-in Kafka-compatible **schema
   registry** replaces a separate registry component for our Avro schemas. Managed Kafka
   (MSK or Redpanda Cloud) is deferred behind explicit triggers: live trading with real
   money, a multi-AZ durability requirement, or sustained throughput beyond one node
   (>~50K msg/s sustained).
2. **Delivery contract: at-least-once, never exactly-once.** No Kafka transactions, no
   read-committed, no correctness dependent on broker EOS (idempotent *producer* config
   stays on — it's free and reduces duplicates without being relied upon).
3. **New hard invariant — idempotent consumers:** every event schema carries a stable
   `eventId` (and sequence per key where ordering matters); applying the same event
   twice must leave state unchanged (dedupe or upsert). Every service ships a
   duplicate-delivery test. Order commands carry idempotency keys end-to-end.

Topic naming, partition keys, Avro-in-`common-messaging`, and compacted-vs-retained
conventions from ADR-0004 carry over unchanged.

## Alternatives considered

**Keep MSK Serverless (ADR-0004 as written).** Zero broker ops and multi-AZ durability,
but ~$550/month is ~10× the rest of the dev bill for guarantees Postgres projections +
S3 archive (ADR-0005) already back-stop. Superseded; returns as the managed option when
the production triggers above fire.

**NATS + JetStream.** Genuinely free (Apache 2.0), excellent latency and ops. Rejected
for the backbone: different client API (lock-in without the Kafka ecosystem), weaker
keyed-partitioning/ordering model for per-instrument streams, thinner tooling (schema
registry, console, connectors). Noted as a candidate transport for the future C++ hot
path (with Aeron, per ADR-0002).

**Queue broker (SQS/RabbitMQ) for "process-once" semantics.** Queues deliver to one
consumer and destroy the message — wrong shape for our fan-out (same tick → algo, risk,
UI) and no replay, which backtesting (ADR-0005, ADR-0010) depends on. The desired
"once" behavior comes from consumer idempotency, not the broker. Rejected.

## Consequences

- Positive: ~$500+/month removed from the dev floor; identical Kafka API means client
  code, tooling, and a later MSK migration are config-level changes; correctness no
  longer depends on the broker's hardest feature; built-in schema registry.
- Negative: a single Redpanda node has **no replication** — disk loss loses unarchived
  in-flight history (acceptable in dev: positions rebuild from Postgres, ticks from S3
  archive); we own broker upgrades/monitoring (one binary, but ours); the idempotency
  invariant is a real engineering tax on every consumer — deliberately so.
- Follow-ups: mark ADR-0004 superseded; add `eventId`/sequence to the base event schema
  (`common-messaging`); duplicate-delivery test helper in the shared test kit; wire the
  production triggers into the FinOps view (ADR-0011) so the "move to managed" signal
  is measured.

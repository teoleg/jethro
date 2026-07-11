# ADR-0004: Kafka (Amazon MSK) as the messaging backbone

- **Status:** Proposed
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** messaging, aws

## Context

ADR-0003 requires a durable, replayable event log with multiple independent consumers,
ordered per key (per instrument for ticks, per book for risk), sustaining tens of
thousands of messages/sec with room to grow. Runtime is AWS (ADR-0007). Local development
must be cheap and offline-capable.

## Decision

We will use **Apache Kafka** as the backbone — **Amazon MSK Serverless** in AWS to start
(scale-to-usage, no broker sizing), moving to provisioned MSK if throughput/cost profile
justifies it. Locally, **Redpanda in Docker Compose** (Kafka-API-compatible, single
binary, fast startup).

Conventions:
- Topic naming `domain.entity` (`md.quotes`, `orders.events`, `risk.snapshots`).
- Partition keys: instrument ID for market data, book ID for risk, order ID for order events.
- Serialization: **Avro** with a schema registry; schemas live in `common-messaging` and
  are the contract between services.
- Compacted topics for latest-value streams (marks, risk snapshots); time-retained topics
  for event history.

## Alternatives considered

**Amazon Kinesis.** Fully managed and cheap to start, but weaker ordering/consumer-group
semantics, 1MB/s per-shard write ceilings to manage, poorer local-dev story, and lock-in.
Kafka's ecosystem (exactly-once, streams API, tooling) wins. Rejected.

**Redis Streams / ElastiCache.** Great latency, weak durability/replay semantics at our
retention needs; Redis stays as a cache, not the backbone. Rejected.

**Aeron / raw multicast.** The low-latency answer, and the natural transport if a C++
execution path (ADR-0002) ever demands it — but heavy ops burden and unnecessary for
current latency goals. Deferred.

## Consequences

- Positive: replay for backtesting and recovery; consumer groups give free scaling;
  identical API locally and in AWS.
- Negative: MSK is the priciest line item in the dev AWS bill — mitigate with MSK
  Serverless and aggressive retention limits in dev.
- Follow-ups: define Avro schemas for `md.*`, `orders.*`, `fills`, `risk.*` as the first
  implementation task; wire schema-compatibility checks into CI.

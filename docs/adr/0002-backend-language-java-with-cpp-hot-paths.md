# ADR-0002: Backend in Java 21, C++ reserved for latency-critical hot paths

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** backend, language

## Context

The owner is strong in both Java and C++. The platform's first mission is monitoring and
analytics: ingest realtime data, run algorithms, compute risk/PnL per book, and stream it
to a UI. This is throughput- and correctness-sensitive but not (yet) an ultra-low-latency
execution system — end-to-end tick-to-screen latency in the tens of milliseconds is
acceptable. Development speed, library ecosystem (Kafka clients, AWS SDK, JSON/Avro,
testing), and operational simplicity on AWS matter more than shaving microseconds.

## Decision

We will build all services in **Java 21 (LTS)** using **Gradle** in a single monorepo
with one module per service plus shared libraries (`common-domain`, `common-messaging`).

- **Spring Boot 3** for service scaffolding (config, health checks, metrics, REST/WebSocket).
- **Virtual threads** for I/O-heavy paths (provider connections, fan-out).
- Hot computation paths (tick processing, PnL recalc) written as plain-Java, allocation-
  conscious code — no framework in the data path.

**C++ enters only when a measured latency requirement demands it** (e.g. a future
co-located execution gateway or a heavy options-pricing kernel). The boundary will be a
separate process speaking Kafka or a defined IPC protocol — never JNI embedded in
services — so it can be added without restructuring.

## Alternatives considered

**All C++.** Best raw latency, but far slower to build: weaker ecosystem for Kafka/AWS/
web APIs, slower iteration, harder ops. Overkill for a monitoring-first platform. Rejected
for the general case; reserved for hot paths.

**Mixed from day one (Java services + C++ calc engine).** Two build systems, two
deployment pipelines, and an IPC boundary to maintain before any latency requirement
exists. Premature. Rejected.

**Kotlin.** Nice language, but adds a dialect on top of a stack the owner already knows
cold, for little gain here. Rejected.

## Consequences

- Positive: fastest path to a working platform; one toolchain; excellent Kafka/AWS support;
  hiring/AI-assistance friendly.
- Negative: GC pauses are a fact of life — mitigated with ZGC and allocation discipline in
  hot paths; if we ever need single-digit-microsecond latency, that work lands in C++.
- Follow-ups: ADR when/if a C++ component is introduced; benchmark harness for the tick →
  PnL path early so the latency budget is measured, not assumed.

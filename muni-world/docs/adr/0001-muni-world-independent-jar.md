# ADR-0001: muni-world is an independent jar reusing jethro's shared libraries

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** structure, build, boundaries

## Context

A new subproject, **muni-world** (municipal-bond domain), starts in the jethro repo. It should benefit
from the machinery jethro already has — exact-decimal money types, Avro messaging, the LMDB/Postgres/Kafka
stack — without becoming entangled in jethro's trading app or bloating its startup. It needs room to evolve
its own domain, decisions and UI at its own pace.

## Decision

muni-world is a **separate Gradle module that produces its own Spring Boot jar**, in the same repo:

- **Its own deployable.** `./gradlew :muni-world:bootJar` builds `muni-world.jar`; it runs on its **own
  port** (default 8090), a separate process from the jethro `app` (8080). `app` does **not** depend on
  muni-world, and muni-world is not in the `app` assembly.
- **Reuse, one-directional.** muni-world depends only on the **shared, low-level** jethro libraries
  (`common-domain`, `common-messaging`) — "runs on top of" them. It must never depend on jethro's
  trading/app modules, and jethro must never depend on muni-world. The dependency arrow points one way.
- **Own namespaces for shared infra.** Postgres: muni-world's **own `muni` schema**, Flyway-managed under
  `muni-world/db/migration`, never touching jethro tables. Kafka/Redpanda: the same broker, but muni-world
  owns only `muni.*` topics. LMDB: its **own env/file**, derived-data only — the two subprojects never
  share an LMDB file.
- **Own governance.** muni-world keeps its **own ADR flow** (`muni-world/docs/adr/`) and **README**;
  jethro's ADR index does not absorb muni-world's decisions.
- **Boots offline.** The jar starts and serves its UI with no live Postgres/Kafka (Hikari never probes at
  boot, Flyway/Kafka default off, LMDB is a local file) — so it is runnable and testable standalone.
- **UI reachable from jethro.** The jethro landing page carries a separate *Muni World* tile that opens
  muni-world's UI (its own, visually distinct menu) on the same host's :8090.

## Consequences

- Clean blast radius: muni-world can iterate, break and redeploy without touching the trading app; a
  muni-world outage never takes jethro down (separate process).
- Shared code is reused without coupling — the one-way dependency is build-enforced by what
  `muni-world/build.gradle.kts` declares.
- Two processes to run on one host (8080 + 8090); acceptable now, and a reverse-proxy/single-origin setup
  can come later behind a concrete trigger.
- Same money/correctness disciplines as jethro apply here (exact decimal, provenance on risk/money dials,
  design-first ADRs) — inherited by convention, recorded in this module's own flow.

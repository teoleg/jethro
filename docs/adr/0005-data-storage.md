# ADR-0005: Aurora PostgreSQL for state, Kafka + S3 for tick history

- **Status:** Proposed
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** data, aws

## Context

Distinct storage needs:
1. **Reference/transactional state** — instruments, books, orders, positions: relational,
   modest volume, strong consistency.
2. **Tick history** — high-volume time series, used for replay, backtesting, and charts.
3. **Hot lookups** — latest marks, current positions for fast UI snapshots.

## Decision

- **Aurora PostgreSQL (Serverless v2)** is the system of record for reference data,
  orders, fills, positions, and end-of-day snapshots. One schema per owning service
  (ADR-0003: each service owns its state).
- **Tick history stays in Kafka** (retention: days) for operational replay; a sink job
  archives ticks to **S3 as Parquet**, partitioned by date/instrument, queryable with
  Athena/DuckDB for backtesting and analytics.
- **ElastiCache Redis** for hot state where measured latency requires it (latest mark per
  instrument, UI snapshot caches). Introduced on evidence, not by default.

## Alternatives considered

**Dedicated time-series DB (Timestream, ClickHouse, kdb+).** Superb for tick analytics,
but a new operational component before we have analytic query load. Parquet-on-S3 covers
backtesting at near-zero cost; a columnar store can be added behind the same archive
later. Deferred.

**DynamoDB for everything.** Scales beautifully, but the domain is relational (books ↔
positions ↔ instruments ↔ orders) and ad-hoc risk queries want SQL. Rejected.

**Keep positions only in memory (event-sourced from Kafka).** Elegant, and we *do*
rebuild from the log on restart — but a Postgres projection gives ops-friendly inspection
and survives retention windows. Both, not either.

## Consequences

- Positive: boring, cheap, SQL for the domain; S3/Parquet makes historical data
  essentially free to keep forever.
- Negative: two representations of positions (log + projection) must be kept consistent —
  the projector is the only writer.
- Follow-ups: schema migration tooling (Flyway) from the first table; define the Parquet
  archive layout before the market-data gateway ships.

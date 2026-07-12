# Jethro — Multi-Asset Trading Platform

Jethro is a trading platform for multiple security types. It pulls realtime market
data, runs trading algorithms, and calculates risk and PnL across multiple books,
each holding positions in various instruments.

## Capabilities (target)

- **Realtime market data** — pluggable provider adapters, normalized internal tick schema
- **Algo engine** — pluggable strategies consuming normalized market data, emitting orders
- **Books & instruments** — multiple books, multi-asset (equities, futures, options, FX)
- **Risk & PnL** — positions, realized/unrealized PnL, exposures per book, live recalc on ticks
- **UI** — landing page with tiles, each opening a dedicated view:
  - Market Monitor (market moves)
  - Order View (order blotter & lifecycle)
  - Book Structure (book → positions → instruments)
  - Risk & PnL per book
  - Costs (FinOps: AWS spend vs budget, live AI token spend)

## Documentation

- [`docs/architecture/overview.md`](docs/architecture/overview.md) — system architecture
- [`docs/adr/`](docs/adr/) — Architecture Decision Records (start at the [index](docs/adr/README.md))
- [`CLAUDE.md`](CLAUDE.md) — working conventions for AI-assisted development

## Building & running

Requires Java 21 (Gradle toolchain) and Docker.

```bash
./gradlew build            # compile everything + domain/serde/ArchUnit tests
docker compose up -d       # local infra: Redpanda (Kafka API + schema registry) + Postgres
./gradlew :app:bootRun     # the single-JVM app (ADR-0015)

# or run the app as a container too:
docker compose --profile app up --build
```

## Status

Build-out step 1 complete: Gradle monorepo, `common-domain` (decimal money types,
average-cost position math with exact-value tests), `common-messaging` (Avro event
contracts), `app` shell with ArchUnit-enforced module boundaries, local compose
topology. Next: step 2 — trading-core ring buffer + sim feed adapter.

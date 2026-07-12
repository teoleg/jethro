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

## CI

GitHub Actions (`.github/workflows/ci.yml`) runs two jobs on every push:

1. **build** — compile + all unit/module tests (`./gradlew build -Pci`; a plain
   `./gradlew build` skips tests so constrained hardware can still produce a jar).
2. **integration** — the no-stubs job: starts Redpanda + Postgres + Ollama via the
   repo's own compose file, pulls a real model, then runs `./gradlew :app:integrationTest`
   (`FullStackIT`): marks must arrive through the real broker, and an `AiDecision` with
   real generation latency and token counts must land on `ai.decisions` and surface as
   an attention card. Locally: `docker compose up -d && ./gradlew :app:integrationTest`.

## Building & running

Requires Java 21 (Gradle toolchain) and Docker.

```bash
./gradlew build            # compile + package (tests skipped; add -Pci to run them, as CI does)
#   ./scripts/run-local.sh   # build + start Docker infra + run the app in one command
docker compose up -d       # local infra: Redpanda (Kafka API + schema registry) + Postgres
./gradlew :app:bootRun     # the single-JVM app (ADR-0015)

# or run the app as a container too:
docker compose --profile app up --build
```

## Status

Build-out step 5 complete: reference data — Postgres schema via Flyway (books tree,
instruments, symbology), seeded to match the sim feed; `/api/books` + `/api/instruments`
(exact decimals as strings); Book Structure drill-down at `/books.html`. CI's FullStackIT
now also proves migration + seeded queries against real Postgres. The attention feed
(step 4, ADR-0017) is the landing page at `http://localhost:8080`: conflated marks on
`md.marks`, AI decisions on `ai.decisions`, SSE to the browser, deterministic triggers
always surfacing. Next: step 6 — order module (simulated fills) + Order View.

Full local run:

```bash
docker compose up -d                                  # Redpanda + Postgres + Ollama
docker exec jethro-ollama-1 ollama pull qwen2.5:3b    # once, ~2GB
./gradlew :app:bootRun
# open http://localhost:8080 — live marks strip + attention feed
```

Without the broker/model running, the app degrades gracefully (rate-limited warnings,
empty feed) — the market path never depends on either.

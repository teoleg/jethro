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

Build-out step 3 complete: AI is in the loop — model-inference SPI (ADR-0010) with an
Ollama adapter (local SLM, ADR-0016) and the first agentic element, a risk commentator
that narrates computed market state on a cadence and records every run as an
`AiDecision` audit event (model, latency, tokens, context snapshot + hash). Ollama runs
in compose; the model narrates, deterministic code computes every number. Next: step 4
— `ui-gateway` + the attention feed (ADR-0017), AiDecision events onto the broker.

To see AI commentary locally:

```bash
docker compose up -d ollama
docker exec jethro-ollama-1 ollama pull qwen2.5:3b   # once, ~2GB
./gradlew :app:bootRun                                # commentary logged every 60s
```

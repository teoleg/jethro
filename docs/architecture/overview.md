# Jethro — Architecture Overview

Status: design phase. Decisions referenced as ADR-XXXX live in [`docs/adr/`](../adr/README.md).

## System at a glance

The market path is **one process** — `trading-core` (ADR-0014): feed adapters, the
AI-driven algo engine, and risk/PnL communicate over an in-process ring buffer;
microseconds from tick to decision. The **durable log** (Kafka API via Redpanda,
ADR-0012) carries only transactional flow and snapshots: orders, fills, AI decisions,
conflated marks and risk snapshots — at-least-once, idempotent consumers. Ticks are
archived write-behind to S3 Parquet; backtests replay the archive through the same
pipeline via the feed SPI (ADR-0009). UI is a React/TypeScript SPA (ADR-0006) streaming
from `ui-gateway` over WebSocket. Dev runs the whole stack on one EC2 node; ECS
Fargate/Aurora/ALB are the production shape (ADR-0007/0013).

```mermaid
flowchart LR
    subgraph ext[Market Data Providers]
        P1[sim adapter]
        P2[real provider]
    end

    subgraph core[trading-core — one JVM, in-proc ring buffer]
        MDG[market-data module<br/>provider SPI]
        ALGO[algo module<br/>model-inference SPI]
        RISK[risk-pnl module]
        ARCH[tick archiver]
        LMDB[(LMDB: dedupe,<br/>warm cache)]
    end

    P1 --> MDG
    P2 --> MDG
    MDG --> ALGO
    MDG --> RISK
    MDG --> ARCH

    S3[(S3 Parquet<br/>tick archive)]
    ARCH -. write-behind .-> S3

    subgraph log[Redpanda - transactional + snapshots]
        T2([orders.new / orders.events / fills])
        T3([risk.snapshots / md.marks / ai.decisions])
    end

    ALGO --> T2
    T2 --> ORD[order-service]
    ORD --> T2
    T2 --> RISK
    ALGO --> T3
    RISK --> T3

    REF[reference-data-service] -.REST.- core

    T2 --> UIG[ui-gateway]
    T3 --> UIG
    REF -.REST.- UIG

    PG[(PostgreSQL)]
    ORD --> PG
    RISK --> PG
    REF --> PG

    UIG -- WebSocket + REST --> SPA[React SPA<br/>S3 + CloudFront]
```

## Deployables

| Deployable | Role | Key ADRs |
|---|---|---|
| `trading-core` | Fused market path: feed adapters (provider SPI), AI algo engine (model-inference SPI), risk/PnL, tick archiver, LMDB local state. One feed session; conflation with counted drops | 0009, 0010, 0014 |
| `order-service` | Order lifecycle; simulated execution until a broker is wired | 0003, 0008, 0012 |
| `reference-data-service` | Instruments, symbology, book tree | 0008 |
| `ui-gateway` | BFF: REST snapshots + WebSocket streaming, per-view subscriptions | 0006 |
| `finops-service` | Cost telemetry: Cost Explorer polling, real-time LLM token pricing from `ai.decisions`, budget alerts | 0011 |

## UI views

Landing page of tiles (`/`), each tile opening a view:

| Tile / route | View | Primary data |
|---|---|---|
| Market Monitor `/market` | live quotes/trades, movers, mini-charts | `md.marks` (1Hz conflated) |
| Order View `/orders` | order blotter with lifecycle states, fills | `orders.events`, `fills` |
| Book Structure `/books` | book tree → positions → instrument details | reference data + positions |
| Risk & PnL `/risk/:bookId` | per-book PnL (realized/unrealized), exposures, shocks | `risk.snapshots` |
| Costs `/costs` | month-to-date spend by service vs budget, burn rate, live LLM token spend, running-resources panel | `cost.snapshots` (infra ~24h lag; LLM spend live) |

## Data flow invariants

1. External symbology never crosses the market-data module boundary (ADR-0009); enforced
   in review now that it is a module, not a process (ADR-0014).
2. `fills` is the source of truth for positions; the risk-pnl module owns the projection
   (ADR-0005, ADR-0008).
3. Events are the only **inter-process** data path; inside `trading-core` the ring buffer
   rules (ADR-0014). Anything crossing a process boundary is an event on the log.
4. Every event carries provider + ingest timestamps; staleness is always measurable.
   Marks restored from LMDB are flagged stale until the feed refreshes them.
5. No binary floating point for money — decimals end to end (ADR-0008).
6. Everything runs locally via Docker Compose (Redpanda + Postgres + sim market data)
   with no AWS dependency (ADR-0007/0013).
7. AI never sits on the tick path; risk guardrails are deterministic Java code; every AI
   decision is an event on `ai.decisions` **embedding the market-context snapshot it
   decided on** — replay/backtests consume recorded decisions (ADR-0010, 0014).
8. Delivery is at-least-once; consumers are idempotent (stable `eventId`, dedupe/upsert,
   duplicate-delivery test per service). Never rely on broker exactly-once (ADR-0012).
9. Embedded local state (LMDB) holds **derived data only** — losing it may cost restart
   time, never data (ADR-0014). Ticks/marks are droppable under pressure, but drops and
   archive gaps are always counted and exposed as metrics — never silent.

## Repository layout (planned)

```
jethro/
├── docs/                    # ADRs, architecture
├── common-domain/           # shared types: Instrument, Book, Position... (ADR-0008)
├── common-messaging/        # Avro schemas + serde for all topics (ADR-0012)
├── trading-core/            # fused market path (ADR-0014)
│   ├── market-data/         #   provider SPI + adapters (sim first)
│   ├── algo-engine/         #   strategies + model-inference SPI (ADR-0010)
│   ├── risk-pnl/            #   positions, PnL, exposures
│   └── runtime/             #   ring buffer, LMDB state, tick archiver, wiring
├── services/
│   ├── order-service/
│   ├── reference-data-service/
│   ├── ui-gateway/
│   └── finops-service/
├── ui/                      # React + TypeScript SPA (ADR-0006)
├── infra/                   # AWS CDK in Java (ADR-0007/0013)
└── docker-compose.yml       # local topology
```

## Build order (proposed)

1. `common-domain` + `common-messaging` (types and schemas first, incl. `eventId` base
   and the `ai.decisions` context-snapshot field)
2. `trading-core` skeleton: ring buffer + sim adapter behind the feed SPI → ticks
   flowing in-process; LMDB dedupe/warm-cache wiring
3. `ui-gateway` + UI skeleton (landing tiles + Market Monitor) fed by `md.marks`
4. `reference-data-service` (instruments, books) + Book Structure view
5. `order-service` (simulated fills) + Order View
6. risk-pnl module in `trading-core` + `risk.snapshots` + Risk & PnL view
7. algo module with one toy strategy behind the model-inference SPI; tick archiver +
   replay adapter (backtest loop closes here)
8. `infra/` CDK + first AWS deploy: single dev node running the compose stack, tagging,
   AWS Budgets backstop, stop-when-idle schedule (ADR-0013)
9. `finops-service` + Costs view (LLM token pricing can land earlier, with step 7)

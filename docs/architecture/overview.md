# Jethro — Architecture Overview

**Status: built and running** (single-JVM modular monolith). This document is the *current*
architecture — reconciled against the code on 2026-07-24, not the original design. Decisions live in
[`docs/adr/`](../adr/README.md) (through ADR-0060); the runtime resource/contention picture and the
freeze analysis live in [`system-footprint-analysis.md`](system-footprint-analysis.md); the analytics
north star in [`quant-engine.md`](quant-engine.md).

> **Maintenance rule:** when you change a seam (a module boundary, a shared singleton, a transport, a
> lifecycle), update this file in the *same* change. A stale architecture doc is worse than none — it
> misleads the next reader (human or agent) with confidence. This map is only useful if it's true.

## The three transport planes (read this first)

The single most common confusion is stacking three *separate* transports into one. They are distinct:

1. **Browser ↔ backend (UI plane) — pure HTTP.** The UI is a thin, read-only client: ~58 polled
   `/api/*` REST endpoints **plus one SSE stream** (`/api/stream`, `EventSource`) that pushes the
   attention feed. **No websocket, no Redpanda touches the browser.** (ADR-0028; ADR-0017 attention-first.)
2. **Backend ↔ external providers (ingest plane).** Exactly **one websocket** in the whole system —
   the inbound Finnhub trade feed (`wss://ws.finnhub.io`, only on the Finnhub feed). Everything else is
   REST out: Tiingo (history), Yahoo (indicators), RSS (news), Ollama (local LLM), StockTwits/Telegram.
3. **Inside the backend (internal plane) — invisible to the browser.** Hot ticks flow over an
   **in-process ring buffer** (never brokered, ADR-0014). Cross-module events flow over **Redpanda**
   topics (`fills`, `md.marks`, `risk.snapshots`, `ai.decisions`, `orders.*`, `cost.snapshots`) — all
   inside one JVM today, so most are the app messaging itself (see the "do we still need the broker?"
   open question, ADR-0012). Durable state: **Postgres** (system of record) + **LMDB** (warm-restart,
   derived-only).

```mermaid
flowchart TB
    subgraph browser[Browser — thin read-only client]
        UI[13 static pages: Overview, Signals, Ops, Markets, Rates, Books, Orders, Backtest, Strategy, Social, Discover, Config, Sim]
    end
    subgraph jvm[app — ONE JVM, all modules - ADR-0015]
        EDGE[:8080 edge — REST controllers + SSE /api/stream]
        subgraph coreproc[trading-core — in-proc ring buffer, ADR-0014]
            MDG[market-data<br/>provider SPI]
            ALGO[algo-engine<br/>strategies + model-inference SPI]
            RISK[risk-pnl<br/>RiskProjection — the shared lock]
        end
        FUSION[fusion — combine all sources → one target/name, ADR-0055]
        AISVC[ai / hypothesis / chat / social / discovery / signal / training]
        ORD[order — sim execution, extraction seam ADR-0015]
        LOG([Redpanda topics — internal event bus])
    end
    ext[Finnhub WS in · Tiingo/Yahoo/RSS/Ollama REST]
    PG[(PostgreSQL — source of truth)]
    LMDB[(LMDB — warm-restart, derived only)]

    ext --> MDG
    MDG --> ALGO
    MDG --> RISK
    ALGO --> FUSION
    AISVC --> FUSION
    FUSION -->|sole order origin when routing| ORD
    ORD --> LOG
    LOG --> RISK
    RISK --> PG
    ORD --> PG
    RISK -. warm state .-> LMDB
    EDGE -->|REST poll + SSE push| UI
    RISK --> EDGE
```

## Modules (one deployable JVM — ADR-0015)

| Module | Role | Key ADRs | Extraction trigger |
|---|---|---|---|
| `common-domain` | Dependency-free shared types (`Instrument`, `Book`, `Side`, scaled-long `Decimals`) | 0008 | — |
| `common-messaging` | Avro schemas + serde + `Topics` + `Provenance` (feedMode/epoch) | 0012, 0029, 0030 | — |
| `trading-core:{market-data, algo-engine, risk-pnl, runtime}` | Fused market path: feed adapters (provider SPI), algo engine (model-inference SPI), risk/PnL, ring buffer + LMDB + tick archiver | 0009, 0010, 0014 | measured GC interference |
| `modules:order` | Order lifecycle + simulated execution (spread/fee/impact) | 0003, 0025 | **hard: before any real-money broker (ADR-0015)** |
| `modules:reference-data` | Instruments, symbology, book tree, attributes | 0008 | on need |
| `modules:ui-gateway` | REST snapshots + SSE + the static UI pages | 0028 | streaming fan-out load |
| `modules:finops` | **Not built** — empty shell (no Java). Planned: cost telemetry from `ai.decisions` + Cost Explorer | 0011 | on need |
| `app` | Single-JVM assembly + the `:8080` edge + all the subsystems below | 0015 | — |

Isolation is build-enforced (Gradle constraints + ArchUnit): modules depend only on `common-*` and
published interfaces.

## Subsystems in `app` (purpose + what each depends on)

`app` wires 21 subsystems. Each is an independent lifecycle on its own cadence — see
[`system-footprint-analysis.md`](system-footprint-analysis.md) for the full thread/scheduler inventory
and why so many of them funnelling through `RiskProjection` is the freeze hazard.

| Package | Purpose | Main runtime + cadence | Reads / depends on | ADRs |
|---|---|---|---|---|
| `trading` | The fused market path host: feed adapters (sim/Yahoo/Finnhub), ring buffer, `MarkCache` — the source of marks for everyone | `TradingCoreLifecycle` (tick loop) | provider feeds; publishes `md.marks` | 0009, 0014, 0023, 0024, 0032 |
| `risk` | **The shared risk core.** `RiskProjection` (positions/PnL/exposure — ONE synchronized lock, ~23 callers) + monitors (limit, firm breaker, scenario), VaR, DV01, mark quarantine | `RiskDataConsumer`, `RiskLimitMonitor`, `FirmBreakerMonitor`, `ScenarioMonitor`, `RiskSnapshotPublisher` (~2–5s) | `fills` (source of truth), marks | 0005, 0008, 0020, 0027, 0041 |
| `strategy` | Deterministic momentum/mean-reversion; hourly OOS algo selection; price-derived vol regime; live tuning | `StrategyLifecycle` (5s), `StrategySelector` (60min, heap-guarded) | marks, risk, guardrail, order | 0019, 0043, 0044, 0051, 0052 |
| `hypothesis` | LLM thesis layer: narrative → structured theses → quant sizes/gates; RAG memory; event-keyed dedup | `HypothesisLifecycle` (20s) | Ollama, marks, risk, backtest, narrative feed | 0022, 0035, 0049, 0054 |
| `fusion` | **The decision layer (ADR-0055):** combine every source's forecast → one target/name → netted delta; sole order origin when routing (sim-gated) | `FusionLifecycle` (30s) + `ForecastRegistry` + `FusionExecutor` | forecasts from strategy/hypothesis/social/learned, marks, risk snapshot | 0055 |
| `signal` | Per-signal health telemetry: score each source's live call by realised forward return | `SignalTelemetryResolver` (60s) | marks; DB `signal_observations` | 0055 |
| `training` | Learned advisory signal: Tiingo training bars → features/labels → purged walk-forward gate | `TrainingBarsLoader`, `LearnedSignalService` | Tiingo, DB | 0053 |
| `social` | Adversarial social pipeline: spam/credibility/corroboration → advisory signals | `SocialLifecycle` (60s) | StockTwits/Telegram/news feeds, refdata | 0050 |
| `discovery` | Universe discovery from RSS/social **+ the dynamic-universe promotion gate**: a daily controller promotes sustained/corroborated/feed-covered candidates into a bounded **monitor-only** tracked set via a runtime refdata write path (provisional adv/spread, `source=discovered`), stalest-eviction at the cap; audited to `universe_promotion` | `DiscoveryLifecycle` (300s), `UniversePromotionLifecycle` (daily) | RSS, refdata (read+write) | 0045, 0050, 0060 |
| `ai` | Local-SLM risk commentary onto the attention feed; Ollama warmup; inference metrics | `RiskCommentatorLifecycle` (60s) | Ollama, risk | 0016 |
| `chat` | Operational chat: SLM parses the question, deterministic code answers, every turn audited | `ChatResponder` (on demand) | Ollama, risk, refdata | 0021 |
| `hedge` | Minimum-variance proxy hedge advisor (equity axis built; DV01/FX deferred) | `HedgeLifecycle` | risk, marks, correlations | 0038–0042 |
| `order` (wiring) | Wires the `order` module — sim execution, the pre-real-broker extraction seam | `OrderConfig` | — | 0015, 0025 |
| `session` | Session calendar + EOD day boundary; session-day supplier for valuation | `EodService` | risk, calendar | 0027 |
| `indicators` | Top-strip market indicators (delayed Yahoo indices, or sim) | `IndicatorsService` (300s) | Yahoo (live) / sim | 0023 |
| `backtest` | Multi-seed OOS backtest harness (the ADR-0049 hard gate) | `BacktestService` (on demand) | algo engine, sim | 0027, 0043, 0049 |
| `export` | One-click diagnostics `.xlsx` (P&L, positions, fills, TCA, AI outcomes, fusion, telemetry) | `DiagnosticsExportController` | risk, DB, strategy | — |
| `kafka` | The internal event bus impl: publishers + consumers over Redpanda | `KafkaEventPublisher`, consumers | Redpanda | 0012, 0030 |
| `persistence` | Hikari datasource (**pool = 8**) + Flyway migrations | `PersistenceConfig` | Postgres | 0005 |
| `refdata` (wiring) | Wires reference-data | `RefDataConfig` | Postgres | 0008 |
| `ops` | JVM/heap/GC + heap-guard status for the Ops page | `SystemOpsController` | JVM MX beans | — |

## Shared singletons / choke points

- **`RiskProjection`** — one bean, 10 `synchronized` methods, ~23 callers (most lifecycles + every UI
  risk poll). `snapshot()` re-marks all positions *under the lock*, so a slow holder (a GC pause, a
  large book) parks everyone → looks like a whole-server freeze. **This is the leading freeze
  suspect, not the heap alone.** Planned fix: publish an immutable snapshot to a `volatile`; readers go
  lock-free (footprint §7.1).
- **`MarkCache`** (in `trading-core` runtime) — the live mark source, read by strategy, hypothesis,
  fusion, signal, indicators, risk. Concurrent, cheap reads.
- **Hikari pool = 8** shared across ~28 background threads + web threads — a contention risk under load.

## UI pages (attention-first — ADR-0017)

Landing (`/`) is the attention feed + the **fused "combined view"** (every source → one target/name).
Detail pages drill down; each has a "show everything" mode.

| Route | View | Primary data |
|---|---|---|
| `/` | attention feed + fusion combined view + hedging + P&L/orders | `ui.attention`, `/api/fusion/targets`, risk |
| `/signals.html` | **AI/strategy drill-down**: fused target book + AI hypotheses + strategy actions | fusion, hypotheses, strategy |
| `/markets.html` `/rates.html` | live marks/movers; swap book + curve DV01 | `md.marks`, rates |
| `/books.html` `/orders.html` | book tree → positions; order blotter + fills | refdata, positions, `fills` |
| `/backtest.html` `/strategy.html` | OOS selection; strategy signals/tuning/regime | backtest, strategy |
| `/social.html` `/social-sources.html` `/discovery.html` | social signals, source health, universe discovery | social, discovery |
| `/ollama.html` (Ops) | LLM load, JVM/heap guard, RAG, training, learned-signal gate, signal health, fusion book | ops, training, signal, fusion |
| `/config.html` `/sim.html` | config; sim control panel (ADR-0031) | config, sim |

## Data flow invariants (violations are bugs)

1. External symbology never crosses the `market-data` boundary; internal code keys on `instrumentId` (0009).
2. `fills` is the source of truth for positions; only `risk-pnl` writes the projection (0005/0008).
3. Hot ticks flow over the in-process ring buffer; cross-module flow is a Redpanda event even in-process,
   so extraction stays mechanical (0014). (One JVM today — most events are intra-process.)
4. Every event carries provider + ingest timestamps; marks restored from LMDB are flagged stale (0014).
5. No binary floating point for money — `BigDecimal`/Avro decimal/`NUMERIC` at boundaries, scaled-long
   fixed-point in the hot path (invariant 1).
6. Everything runs locally via Docker Compose (Redpanda + Postgres + sim feed), no AWS dependency (0013).
7. AI never sits on the tick path; risk guardrails are deterministic code; every AI decision is an event
   on `ai.decisions` embedding its context snapshot (0010/0016). No model number reaches PnL/risk.
8. Delivery is at-least-once; consumers are idempotent (stable `eventId`, duplicate-delivery test) (0012).
9. LMDB holds derived data only — losing it costs restart time, never data (0014). Dropped ticks/marks
   are counted and exposed, never silent.
10. Sim/live/replay never aggregate across modes: every event carries `feedMode` + `sessionEpoch`; a
    sim↔live switch rolls a new epoch/namespace (0029). Serde resolves the writer schema (0030).
11. **Signals stop placing orders (ADR-0055):** when fusion routing is on (sim-only), the fusion layer
    is the *sole* order origin — strategy auto-exec and hypothesis autonomy stand down; orders are the
    netted delta between the combined target and the current book, through the ADR-0049 gate chain.
12. **Monitor-only growth (ADR-0060):** a discovery-promoted name is written to refdata as
    `universe_status=MONITOR_ONLY` — it flows into marks/indicators/signals but the fusion order gate
    vetoes it, so it cannot trade until its ADV is measured from our own tape. A provisional (flagged)
    adv/spread can never size a real order. Growth never bleeds risk.

## Repository layout (actual)

```
jethro/
├── docs/{adr, architecture}         # decisions + this map + footprint analysis
├── common-domain/                   # dependency-free shared types (ADR-0008)
├── common-messaging/                # Avro schemas + serde + Topics + Provenance (0012/0029/0030)
├── trading-core/{market-data, algo-engine, risk-pnl, runtime}   # fused market path (ADR-0014)
├── modules/{order, reference-data, ui-gateway, finops(shell)}
├── app/                             # single-JVM assembly + 21 subsystems + :8080 edge (ADR-0015)
├── infra/  infra/packer/            # AWS CDK + AMI bake (ADR-0007/0013)
├── deploy/                          # compose rollout alternatives
└── docker-compose.yml               # local topology
```

## What is NOT built / partial

`finops` (empty shell, ADR-0011) · the S3 Parquet tick archiver (ADR-0014) · the external frontier AI
tier (ADR-0010, behind cost triggers) · hedge DV01/FX axes + AUTO submission (ADR-0038/0039) · the
runtime feed-switch endpoint (ADR-0029, restart-to-switch today) · `daily_closes`/`mark_quarantine`
feed-mode scoping (ADR-0029) · the ADR-0060 **measured-ADV → tradable graduation** (a monitored name
stays monitor-only until its ADV is measured from our own tape) and mid-session hot-subscribe (a
promoted name gets marks at the next session, since feed symbol maps are read at boot). See
[`deferred-register.md`](../deferred-register.md) and the ADR index Implementation column for the full list.

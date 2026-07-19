# Jethro — Multi-Asset Trading Platform

Jethro is a multi-asset trading platform: realtime market data → algos → orders → risk/PnL per
book, with an attention-first monitoring UI and a bounded AI layer on the side. It runs end to end
on one machine via Docker Compose — no cloud or paid data feed required — and scales to AWS behind
explicit triggers.

Everything below is **built and running**, not aspirational. Money is exact decimal end to end
(never binary floating point); simulation, live and replay data are never co-mingled; and the AI
never sits on the trading path.

---

## Financial capabilities

**Instruments & books.** Equities, index/Treasury **futures**, **FX**, interest-rate **swaps**
(pay/receive fixed, quoted off a curve), and Treasury/rates. Multiple books; positions are a
projection of the `fills` source of truth (only the risk service writes them).

**Positions & PnL.** Realized/unrealized PnL per book and firm-wide, recomputed on every mark.
Positions rebuild from the durable Postgres `fills` table at boot (the topic only carries live
increments), so history never truncates.

**Risk.**
- Gross / net / per-instrument **exposure limits**, per book with overrides and a firm aggregate;
  **pre-trade checks** reject or size-reduce before an order routes.
- **VaR** two ways — **historical** (full-revaluation over the return window) and **parametric**
  (EWMA covariance) — with covariance-aware position sizing.
- **Rates risk** — bucketed **DV01 per book** as first-class risk state; swap legs enter VaR as
  seasoned-DV01 × Δbp; dynamic bond-future DV01 with a CTD/conversion-factor model.
- **Full-revaluation scenarios** (shock the curve/spot, reprice the book).
- **Firm drawdown breaker** — a deterministic halt switch that stops new risk when tripped.

**Execution (simulated, ADR-0025).** A realistic cost model: per-name/per-class **spread**, **fee**
as a separate cash line, and **square-root market impact** (σ·√(order/ADV)); an **ADV participation
cap** rejects oversized orders. **ADV is now measured live** from the market path's own volume
(ADR-0032), not a static constant. Working-order matching, cancel/TIF, and **TCA** (slippage vs
arrival price) are all modelled. The `order` module is isolated for extraction to its own JVM
before any real-money broker (ADR-0015).

**Sizing.** Volatility-targeted and covariance-aware; conviction never multiplies size (the AI
proposes direction only — numbers are the quant layer's).

**Hedging (ADR-0038/0039, advisory v1).** A deterministic **minimum-variance proxy hedge**: it
sums the book's single-name equity exposure and, when it breaches a cap (the target-flat deadband),
sizes the index-future (ES) hedge that removes the most variance — `h* = Cov(book, r_F)/Var(r_F)`
off the EWMA covariance — carrying its measured **effectiveness ρ²**, so a weak proxy is flagged
*reduce, don't hedge* rather than dressed up. Surfaced on the landing page with the worked math
(exposure vs cap, sized hedge, daily σ before→after). DV01-neutral rates + FX axes, AUTO
execution, and the firm-breaker one-shot de-risk are the next increments.

**Session & corporate actions.** A tick-counted sim calendar and a live-feed session calendar
(exchange holidays, 17:00-ET futures roll, closing-auction marks); a **corporate-action / bad-print
guard** quarantines implausible mark jumps until an operator clears them (survives restart).

**Correctness discipline.** Every PnL/risk calculation has exact-value tests; money is
`BigDecimal` / Avro decimal / Postgres `NUMERIC` at boundaries and **scaled-long fixed-point** in
the allocation-free hot path. Analytics substrate is **OpenGamma Strata** with an exact money
ledger on top (ADR-0020).

---

## Simulation capabilities

The sim is a first-class **model-testing lab**, not a toy — it's the one market source that is
fully ours, deterministic (seedable), and controllable.

- **Correlated cross-asset factor engine** (ADR-0026, default) — one joint draw per tick over
  global factors [EQUITY, RATES level/slope, USD] with **multivariate Student-t** (fat tails that
  hit every market together), **regime switching** (CALM / VOLATILE / RISK_OFF / INFLATION_SHOCK via
  a seeded Markov chain, with the real stock-bond correlation sign flips), and correlated
  **overnight gaps**. Single names ride the equity factor, FX rides USD, and Treasury futures/swaps
  price off the same rates factors — so everything moves in concert.
- **History-anchored bootstrap engine** (ADR-0032, `sim-engine=historical`) — a **stationary block
  bootstrap** over a real OHLCV snapshot's return+volume vectors: real fat tails, vol-clustering and
  cross-asset correlations, resampled into novel, seedable paths. Ships a labelled *synthetic* seed
  so it runs offline; a Yahoo history pull turns it into genuine dynamics.
- **Live control panel** (ADR-0031, `/sim.html`) — sim-only dials the tick generator reads each
  tick: global **speed / pause / reseed / regime override**, per-instrument **price nudge / drift /
  idiosyncratic-vol / volume** — stage a shock and watch risk/PnL/AI react without a restart. Hard-
  gated to `feedMode == SIM`; untouched, the sim reproduces its seeded tape bit-for-bit.
- **Real volume through the pipeline** (ADR-0032) — traded volume drives a live measured ADV and a
  relative-volume signal, surfaced on `/sim.html` and the Ops screen and consumed by execution.
  Volume **surges with the regime and clusters with volatility** (a stress regime trades ~3× the
  calm baseline and a big-move tick prints heavier), so a regime change visibly changes the *shape
  of traffic*, not just price variance — swaps trade with real volume too, not a frozen lot.
- **Provider abstraction** (ADR-0009) — the identical pipeline runs the **sim**, **Yahoo** (free,
  delayed, dev/demo), and **Finnhub** (free real-time equities WS) feeds.
- **Hard mode separation** (ADR-0029) — every event carries `feedMode` (SIM/LIVE/REPLAY) +
  `sessionEpoch`; topics, projections and the archive are namespaced by mode, so sim and live
  numbers are never aggregated.

---

## AI layer (bounded, off the trading path)

Two tiers behind a model-inference SPI (ADR-0010/0016): a **local SLM via Ollama** for
narration/triage/hypothesis, and an external frontier tier behind measured cost/latency triggers.
Hard rule: **a model output is never parsed into a number that feeds positions/PnL/risk**
(invariant 7); every AI decision is an audited event on `ai.decisions`.

- **Hypothesis layer** (ADR-0022) — the model synthesises marks + news + portfolio into structured,
  **number-free** theses (instrument + direction + ordinal conviction); a deterministic quant
  evaluator sizes and guardrails them; admissible ones surface on the attention feed.
- **Bounded autonomy** (ADR-0019/0022) — admissible, backtest-supported theses inside a
  deterministic **risk envelope** may auto-submit **simulated** orders (never a real broker);
  outcomes are scored mark-to-mark at horizon expiry and feed a measured track record.
- **Trigger idempotency** — the same news never re-fires a hypothesis (deterministic news-id/text
  guard + the live calls fed back to the model).
- **Attention-first UI** (ADR-0017) — the landing page is an agent-curated feed with a
  **deterministic floor** (models may never suppress a triggered alert); grids are drill-down
  evidence.
- **Operational chat** (ADR-0021) — the SLM parses the question, deterministic code answers, every
  turn audited to Postgres.
- **Retrieval-augmented context** (ADR-0035, in progress) — local embeddings + pgvector for semantic
  news dedup and past-outcome memory.

---

## Tech stack

| Layer | Choice |
|-------|--------|
| Language / build | **Java 21**, Gradle monorepo; **Spring Boot 3** for scaffolding, plain allocation-conscious Java in the data path |
| Money | Exact decimals at boundaries (`BigDecimal` / Avro decimal / Postgres `NUMERIC`); **scaled-long fixed-point** in the hot path — no `double` on money, ever |
| Market path | Fused in-process **`trading-core`**: feed adapter → lock-free **ring buffer** → mark cache / algo / risk; ticks archived write-behind to **S3 Parquet**, warm/dedupe state in **LMDB** |
| Messaging | **Kafka API via Redpanda** (Docker locally); **Avro** schemas in `common-messaging` with Redpanda's schema registry and writer-schema resolution (ADR-0030); at-least-once + idempotent consumers |
| Storage | **PostgreSQL** (Flyway migrations); S3 Parquet tick archive |
| Analytics | **OpenGamma Strata** for curve/DV01/swap pricing, exact money ledger on top |
| AI | Model-inference SPI; local **Ollama** SLM; external frontier tier behind triggers |
| UI | Server-served **static HTML** from module resources, polling REST + SSE — **no Node toolchain** (ADR-0028) |
| Infra | **AWS CDK in Java**; dev = the compose stack on one EC2 node (stop-when-idle), Fargate/ALB/Aurora as the production shape (ADR-0013) |

**Architecture** — a single-JVM **modular monolith** (ADR-0015): modules isolated by Gradle
constraints + **ArchUnit**, cross-domain flow over Redpanda topics even in-process, with extraction
seams preserved (the `order` module leaves first for real-money trading). Package root
`io.jethro.<module>`; `common-domain` is framework-free.

**Modules:** `common-domain`, `common-messaging`, `trading-core:{market-data, algo-engine, risk-pnl,
runtime}`, `modules:{order, reference-data, ui-gateway, finops}`, `app` (assembly), `infra` (CDK).

**UI pages** (`http://localhost:8080`): Overview (attention feed) · Markets · Rates · Books · Orders
· Config · **Sim** (control panel) · Backtest · Ops.

---

## Building & running

Requires Java 21 (Gradle toolchain) and Docker.

**One command** builds the app, starts infra, pulls the models (chat + RAG embedding), and runs it:

```bash
./scripts/run-local.sh        # build + start Redpanda/Postgres/Ollama + pull models + run the app
# open http://localhost:8080  ·  logs: logs/jethro-app.log
```

Manage the stack **per service** — bounce the app without disturbing the model or the DB:

```bash
scripts/svc.sh restart app         # rebuild + restart just the app; LLM + DB keep running
scripts/svc.sh stop  app           # stop the app, leave everything else up
scripts/svc.sh stop  ollama        # stop one container (its volume is kept — no re-pull)
scripts/svc.sh status              # what's running
scripts/stop-local.sh              # stop the app + infra (data volumes KEPT; --volumes to wipe)
```

Set your defaults once instead of typing them each start — `cp local.env.example local.env` and
edit (`PROVIDER=sim`, `AUTONOMY=off`, `MODEL=…`); it's gitignored, and command-line env still wins
(`PROVIDER=yahoo ./scripts/svc.sh restart app`). Full knob list is in `local.env.example`.

> **Profiles override `application.properties`.** `run-local.sh` defaults to `PROFILE=pi`, so
> `application-pi.properties` wins on any key it sets — notably `jethro.trading.sim-instruments`.
> When you add an instrument, add it to **both** `application.properties` and
> `application-pi.properties`, or the Pi/default run keeps ticking the old universe while refdata
> already knows the new name.

Data persists in named volumes (`pg-data`, `redpanda-data`, `ollama-models`), so a restart keeps
your books, fills, orders, topics, and pulled models. The raw path, if you prefer it by hand:

```bash
docker compose up -d                                  # Redpanda + Postgres + Ollama
docker compose exec -T ollama ollama pull qwen2.5:1.5b        # chat model (narration/hypotheses)
docker compose exec -T ollama ollama pull nomic-embed-text    # RAG embeddings (ADR-0035)
./gradlew :app:bootRun                                # the single-JVM app (ADR-0015)
```

```bash
./gradlew build            # compile + package (tests skipped; add -Pci to run them, as CI does)
docker compose --profile app up --build   # run the app as a container too
```

Pick the sim engine and feed with `jethro.trading.*` (properties or env):

```properties
jethro.trading.provider=sim              # sim (default) | yahoo | finnhub
jethro.trading.sim-engine=correlated     # correlated (default) | historical | legacy
jethro.trading.sim-snapshot-path=/data/history.json   # for sim-engine=historical (else a synthetic seed)
```

Without the broker/model running, the app **degrades gracefully** — the market path never depends
on either.

---

## CI

GitHub Actions (`.github/workflows/ci.yml`), two jobs per push:

1. **build** — compile + all unit/module tests (`./gradlew build -Pci`).
2. **integration** — the no-stubs job: starts Redpanda + Postgres + Ollama via the repo's compose
   file, pulls a real model, then runs `:app:integrationTest` (`FullStackIT`): marks flow through the
   real broker, migrations + seeded queries run against real Postgres, and a real `AiDecision`
   (genuine latency + token counts) lands on `ai.decisions` and surfaces as an attention card.

---

## Documentation

- [`docs/architecture/overview.md`](docs/architecture/overview.md) — system architecture + build order
- [`docs/adr/`](docs/adr/) — Architecture Decision Records ([index](docs/adr/README.md))
- [`docs/gap-register.md`](docs/gap-register.md) — open work threads, each with its own home
- [`docs/deferred-register.md`](docs/deferred-register.md) — implementation-level deferred items
- [`CLAUDE.md`](CLAUDE.md) — working conventions

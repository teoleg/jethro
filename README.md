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

**Hedging (ADR-0038/0040/0041/0042, advisory v1).** A deterministic **minimum-variance proxy hedge**:
it sums the book's single-name equity exposure and holds net equity **target-flat**, sizing the
index-future hedge that removes the most variance — `h* = Cov(book, r_F)/Var(r_F)` off the EWMA
covariance (with a covariance burn-in gate, ADR-0041) — carrying its measured **effectiveness ρ²**,
so a weak proxy is flagged *reduce, don't hedge*. The **proxy is chosen by measured ρ²** across
candidates (ES/NQ) with switch hysteresis and unwind-before-build (ADR-0042); under it sits a
**history-free structural tier** (ADR-0040) that hedges each equity to the index at an assigned
GICS-sector beta when covariance isn't yet trustworthy. The hedge is a **delta** (target − held on a
dedicated HEDGE book), so AUTO can't compound. Surfaced on the landing page with the worked math.
DV01-neutral rates + FX axes and the firm-breaker one-shot de-risk are the next increments.

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
- **Provider abstraction + multi-source coverage** (ADR-0009/0023/0024/0056) — the identical pipeline
  runs the **sim** or a real feed, selected by one config knob:
  - **Alpaca** (`provider=alpaca`) — **free real-time US-equity trades** over Alpaca's IEX WebSocket
    (`wss://stream.data.alpaca.markets/v2/iex`), using free paper/trading-API keys. JSON stream with a
    loud fallback if a connection lands on msgpack; auth → subscribe-on-authenticated handshake with
    reconnect/back-off.
  - **Finnhub** (`provider=finnhub`) — free real-time equities WebSocket (`wss://ws.finnhub.io`), plus
    a free Treasury-curve probe when a token is set.
  - **Yahoo** (`provider=yahoo`) — free but **~15-min delayed**, dev/demo only, polled REST.
  - **Multi-source composition (ADR-0056):** a real-time WS source is **composed with a delayed Yahoo
    poll** as a background — Yahoo covers the names the WS doesn't stream (FX, futures) *and* backstops
    the WS names off-hours. A **mark-cache freshness guard** (reject an older provider timestamp) keeps
    the live WS mark winning, so the two sources **never mix** — this fixed the single-source blackout
    where a name on one quiet feed showed 0 exposure. FX / index & rate futures / the SOFR-Treasury
    curve ride the sim or the **live Treasury curve** (ADR-0024) under any equity feed.
  - External provider symbology **never leaks past the market-data gateway**; internal code keys on
    `instrumentId`. Whatever the feed, a missing broker/model **degrades gracefully** — the market path
    never depends on either.
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
- **AI never originates an order** (ADR-0049) — a news/AI thesis is order-eligible **only** when the
  deterministic OOS backtest independently supports that instrument+direction (a hard gate,
  fail-closed: an unmeasured name never trades). The model proposes; the deterministic edge decides;
  outcomes are scored mark-to-mark at horizon expiry and feed a measured track record that can only
  *further* constrain size, never substitute for the gate.
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

## Signals — deterministic selection, social & discovery

The newest layer. Everything here is **advisory or deterministic** — it can shape *which* algo runs
or add *context*, but a signal can never move the book without passing the deterministic edge gate
(ADR-0049), and nothing here writes a number into sizing/risk (invariant 7).

- **Per-instrument strategy selection** (ADR-0043) — momentum and mean-reversion are measured
  **out-of-sample** on multi-seed sim paths, and each instrument routes to the algo with the better
  cost-honest median (or *no-trade* when neither has a measured edge). No blind global algo.
- **Regime-aware selection** (ADR-0044) — a **price-derived trend detector** (Kaufman efficiency
  ratio over each name's own prices, with hysteresis + cross-sectional breadth) picks momentum in a
  trend / mean-reversion in chop and *switches when the regime turns*. It reads **prices only** —
  never the sim's regime label — so it's honest in production (sim=prod parity). The OOS selector
  becomes the edge **gate** on top.
- **Social media — an adversarial source** (ADR-0050) — **always real data** (StockTwits, Telegram),
  no relation to the market sim. A deterministic **spam pre-filter → credibility tiers →
  corroboration gate** stands in front: a subject promotes only on ≥k **distinct credible** channels;
  a low-credibility burst is flagged as a **suspected pump** and ignored, never traded. Per-source
  **connection health, progress, and the live controls** are on their own *Sources* page.
### Autodiscovery — from a headline to a monitored name

The tracked universe is no longer a frozen hardcoded list. A discovery pipeline watches what the market
is actually talking about and — conservatively, programmatically — grows the set. End to end:

1. **Sources** (ADR-0045/0050 §7) — real **RSS** from your curated outlets (central banks, the market
   regulator, the labour-stats agency, broad market feeds) polled off the tick path, plus the real
   **social** feeds (StockTwits/Telegram). Fail-open: a dead outlet shows *unreachable*, the rest keep
   polling; nothing is faked.
2. **Name extraction** — three deterministic ways, no SLM, no scraping: **$cashtags**,
   **exchange-qualified** mentions (`(NASDAQ: X)`), and **bare company names** in prose ("Nvidia jumps")
   resolved via a curated **SEC company-ticker directory**. Only names we do **not** already track
   surface as candidates — a tracked name in the news is left for the advisory path.
3. **Ranked candidate register** — each untracked mention is weighted by **source credibility** (a
   trusted outlet > a verified social account > an anonymous post) and accumulated, with a **cross-source
   bonus** so a name two independent outlets carry outranks one loud single source. It also tracks the
   number of **distinct calendar days** the name recurred (the "sustained" signal) and is **bounded**
   (the weakest candidate is evicted past a cap). *Discover* shows this live, newest headlines and all.
4. **Daily promotion gate** (ADR-0060) — once per session-day a controller promotes a candidate **only**
   if it clears **all** of: **score ≥ threshold**, **sustained** (≥N distinct days, not a one-day
   burst), **corroborated** (≥M distinct credible sources), and **feed-coverage confirmed** (a configured
   provider can actually mark it — never admit an unmarkable name). It is **rate-limited to K/day**, and
   a **blacklist** hard-bans names.
5. **Promotion → a first-class instrument** — a promoted name is written into the **reference-data
   master** with a real `display_name`, a feed symbol, and **PROVISIONAL, flagged** adv/spread
   (`source=discovered`, money-dial rule: never a silent default — the flag says "not yet measured"). It
   then behaves like **any other name**: it joins the refdata trading universe, gets marks / indicators /
   signals, is OOS-evaluated, and **trades through the same gates as everything else** (OOS backtest gate,
   pre-trade guardrail, sim-only execution + firm breaker). No special-casing — running discovered names
   through the real order/risk path is the whole point (it validates the config, the risk limits and the
   strategy stack on live-discovered names).
6. **Bounded + audited + reversible** — a hard **cap** on discovered names with **stalest-eviction**
   (least-recent mention, never a pinned or already-traded name); a **pin-list** protects names; every
   promotion/eviction is a row in `universe_promotion`. *Discover* surfaces the live gate verdict +
   reason per candidate and the audit trail.

The whole feature is **off by default** (`jethro.universe.dynamic.enabled`) with a **dry-run** mode
(`…dynamic.write=false`: decide + audit, write nothing to refdata) so you can watch the gate work on real
candidates before it writes anything. All thresholds are conservative, owner-set placeholders.

The AI/news/social feeds are **built, tested, and advisory-only**; the live external calls are opt-in
(configure your outlets/API tokens) and fail-open — an unreachable source shows *unreachable*, it
never breaks the loop or fakes data.

---

## The platform improves itself — a bounded autonomy loop (ADR-0063)

The newest layer wraps everything above in a **self-improvement loop** that runs unattended on the box
next to the live (paper) platform and pushes for one thing: **higher risk-adjusted PnL — more PnL per
unit of exposure.** Every ~2 hours, one cycle:

1. **Report** — a dependency-free collector snapshots the live app (risk, VaR, breaker, signals/edge
   telemetry, hedging, **attribution**, turnover/cost, equity curves, recent stack traces) into a
   compact, model-readable digest.
2. **Diagnose + change** — Claude Code (headless, on the **Max** plan), framed as a world-class
   trader/quant *and* a senior engineer, reads the digest and — **only if warranted** — makes **one
   coherent change above the safety floor**: fine-tune a dial, fix a bug from a trace, adjust
   sizing/hedging, or add a whole new **strategy / risk model** (with a Proposed ADR in the same
   commit). Most cycles make **no change** — flat is often the right answer (ADR-0062).
3. **Verify + commit** — the change ships only if `./gradlew -Pci test` is green; the wrapper owns
   push + rebuild + restart, so those happen only on a verified commit.
4. **Score, then self-correct** — the change is measured **on the next run** by its ΔPnL/Δexposure on
   **strategy alpha** (never the hedge-masked firm total) and recorded in
   `reports/improvement-ledger.md` with a verdict: ✅ **GOOD** / ❌ **BAD** (**auto-reverted**) /
   ⚠️ **MIXED**. A regression backs itself out and the loop tries a *different* lever next run — no
   human in the loop, striving run after run.

**The numbers are deterministic, never the model's** — the discipline that makes the autonomy safe.
All scoring (the vector, the deltas, the verdict, the revert decision) is computed by a **deterministic
script, `scripts/score-change.py`**, in exact decimal from the live `/api/attribution` + `/api/risk`
endpoints, and committed with an **audited snapshot anyone can recompute** (invariant 7 / ADR-0016 — a
number that gates money/risk is produced by code, never by an LLM). The agent authors code and words;
the script authors every figure.

**What it may never touch:** the **deterministic floor** — the pre-trade guardrail, the firm drawdown
breaker, the invariant-7 gates — and the **real-money path** (behind ADR-0015). It is paper on every
feed (ADR-0061). Bounded, auto-reverting, and green-gated: the worst a bad change can do is lose paper
money for one cycle, then revert.

**Built and tested, inert until enabled** — `ops/loop-control.sh on` installs one tagged cron line;
it's **off by default**. The ledger and its snapshots are the record you read (see
[`ops/README.md`](ops/README.md) for the runbook).

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
· Config · **Sim** (control panel) · Backtest · **Social** · **Sources** (feed health) · **Discover**
(candidate additions + the ADR-0060 promotion gate & audit trail) · Ops.

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
docker compose exec -T ollama ollama pull qwen2.5:3b         # chat model (narration/hypotheses)
docker compose exec -T ollama ollama pull nomic-embed-text    # RAG embeddings (ADR-0035)
./gradlew :app:bootRun                                # the single-JVM app (ADR-0015)
```

```bash
./gradlew build            # compile + package (tests skipped; add -Pci to run them, as CI does)
docker compose --profile app up --build   # run the app as a container too
```

Pick the sim engine and feed with `jethro.trading.*` (properties or env):

```properties
jethro.trading.provider=sim              # sim (default) | yahoo | finnhub | alpaca
jethro.trading.sim-engine=correlated     # correlated (default) | historical | legacy
jethro.trading.sim-snapshot-path=/data/history.json   # for sim-engine=historical (else a synthetic seed)
```

Real feeds need a **free key** (never committed — set via env / `local.env`): `FINNHUB=…` for Finnhub,
or `ALPACA_KEY_ID=… ALPACA_SECRET=…` for Alpaca (paper/trading-API keys; IEX real-time data is free).
Missing/invalid credentials **fall back to the sim feed**, logged — the app always starts.

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
- [`ops/README.md`](ops/README.md) — the self-improvement loop runbook (ADR-0063): enable/disable, billing, the ledger
- [`CLAUDE.md`](CLAUDE.md) — working conventions

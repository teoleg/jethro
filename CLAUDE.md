# CLAUDE.md — Working conventions for Jethro

Jethro is a multi-asset trading platform: realtime market data → algos → orders →
risk/PnL per book, with a monitoring UI. Owner: Oleg (strong Java/C++, backend-focused).

## Ground truth

- Architecture: `docs/architecture/overview.md`
- Deferred work: `docs/deferred-register.md` — any "deferred/tracked/revisit" note added
  in code REQUIRES a row there in the same change; deferred items must never get lost.
- Decisions: `docs/adr/` — **read the ADR index before proposing designs or stacks.**
  Accepted ADRs are settled; do not re-litigate them. To change one, propose a
  superseding ADR.

## Design-first rule

Any architecturally significant choice (hard to reverse, cross-service, cost/latency
impact, constrains future work) requires an ADR **before** implementation. Use the
`adr` skill. Small, local, reversible choices do not need ADRs — just make them. A number
that gates money, risk, or exposure is **not** small-and-local (see below), even when the
code change is one line.

## Risk & money parameters — no invented numbers

Learned the hard way (a `$250k` hedge "cap" that was only a `@Value` default yet read in
the UI as a rule; a `β=1.0` placeholder; a sped-up sim clock).

- **Every parameter that gates money, risk, or exposure** — cap, threshold, floor, limit,
  band, multiplier, target — must carry its source **in the same change**: an ADR number, a
  cited market convention, or an explicit `PLACEHOLDER — Oleg to set`. Never present a
  self-chosen default as an established rule. When a number is mine and arbitrary, say so in
  the sentence that introduces it — do not let it harden into an assumed decision.
- **Config provenance.** Every risk/money dial in `application.properties` (and equivalents)
  gets a comment stating where the number came from.
- **One concept per decision request.** When asking Oleg to decide, name the single policy
  in play; never reuse a term (e.g. "target-flat") across two different mechanisms in the
  same ask — an approval of one is not an approval of the other.
- **This file is maintained, not static.** When Oleg corrects a decision, add the rule that
  would have prevented it here in the same change, so the conventions compound.

## Stack (per ADRs — summary only)

- Backend: Java 21, Gradle monorepo, Spring Boot 3 for scaffolding, plain Java in data
  paths. C++ only behind a measured latency requirement, as a separate process.
- Market path: fused `trading-core` process — feed adapters, algo engine, risk/PnL over
  an in-process ring buffer; ticks archived write-behind to S3 Parquet, never brokered;
  LMDB (local EBS) for dedupe + warm-restart state, derived data only (ADR-0014).
- Messaging: Kafka API via Redpanda everywhere (Docker locally, self-hosted node in AWS
  dev); managed Kafka only behind production triggers. Avro schemas in `common-messaging`
  (Redpanda's built-in schema registry). Delivery is at-least-once — see invariant 6.
- Storage: PostgreSQL (Flyway migrations) — compose Postgres in dev, Aurora as the
  production shape; S3 Parquet for tick archive.
- AI: model-inference SPI in the algo engine. Two tiers: local SLM via Ollama for
  narration/triage/scenario-proposal (ADR-0016 — a model output is NEVER parsed for a
  number feeding positions/PnL/risk); external frontier API via AWS for trading
  decisions, embedded self-hosted behind measured cost/latency triggers (ADR-0010).
- UI: server-served static HTML pages from module resources, polling REST — no Node
  toolchain; React/AG Grid deferred behind concrete triggers (ADR-0028, supersedes
  ADR-0006). Attention-first (ADR-0017): landing page is an agent-curated attention
  feed with a deterministic floor (models may never suppress a triggered alert); grids
  are drill-down evidence views with a "show everything" mode.
- Deployment: single-JVM modular monolith (`app/`) for now — modules isolated by Gradle
  constraints + ArchUnit, cross-domain flow via Redpanda topics even in-process; the
  `order` module MUST be extracted to its own JVM before any real-money broker
  connection (ADR-0015).
- Infra: AWS CDK in Java under `infra/`. Dev = the compose stack on one EC2 node
  (stop-when-idle); Fargate/ALB/Aurora are the production shape (ADR-0013). Everything
  tagged `project`/`service` for FinOps (ADR-0011).
- Local dev: everything must run via Docker Compose with the sim market-data adapter —
  never make a feature depend on AWS or a paid data feed.

## Hard invariants (violations are bugs)

1. No binary floating point for prices, quantities, or PnL — ever. Exact decimal
   semantics end to end: `BigDecimal` / Avro decimal / Postgres `NUMERIC` at boundaries
   (persistence, messaging, reports); **scaled-long decimal fixed-point** (e.g. price as
   `long` in 1e-6 units, scale declared per field) in the allocation-free hot path.
   `double`/`float` on money is a bug in either place.
2. External provider symbology never leaks past `market-data-gateway`; internal code keys
   on `instrumentId`.
3. `fills` is the source of truth for positions; only `risk-pnl-service` writes the
   positions projection.
4. Event schemas are contracts: evolve backward-compatibly, never edit published schemas
   in place.
5. Every event carries provider and ingest timestamps.
6. Delivery is at-least-once; never rely on broker exactly-once. Every event carries a
   stable `eventId`; applying the same event twice must leave state unchanged, and every
   consumer ships a duplicate-delivery test.
7. AI never sits on the tick path; risk guardrails are deterministic code; every AI
   decision is an event on `ai.decisions`.
8. Sim, live, and replay data are never aggregated across modes (ADR-0029): every event
   carries `feedMode` (SIM/LIVE/REPLAY) + `sessionEpoch` in `EventMeta`; one mode per
   session, and a sim↔live switch starts a new epoch/namespace rather than continuing.

## Code conventions

- Java: standard formatting, package root `io.jethro.<module>`. Constructor injection.
  No framework types in domain code (`common-domain` is dependency-free).
- Hot path (trading-core ring buffer consumers): allocation-conscious — scaled-long
  money types from `common-domain`, no `BigDecimal`, no boxing, no streams; convert to
  `BigDecimal` only at the module boundary. Conversions live in `common-domain`
  (`Decimals.toBigDecimal(long, scale)` etc.), never hand-rolled.
- Tests: JUnit 5; every PnL/risk calculation gets exact-value tests (decimals make this
  possible — use it). Sim adapter is seedable — use fixed seeds in tests.
- Errors in data paths: never silently drop a tick/fill — count, log, and expose a metric.

## Response & reasoning style for this repo

- Lead with the recommendation, then the why. One recommendation, not a menu — unless
  the decision is being captured in an ADR, where alternatives belong.
- Finance correctness beats code elegance: when touching PnL/risk math, show the formula
  and a worked numeric example in the PR/response.
- When uncertain about market conventions (day counts, multipliers, settlement), say so
  explicitly and pick the conservative default rather than guessing silently.
- Don't scaffold ahead of the build order in `docs/architecture/overview.md` without
  being asked.

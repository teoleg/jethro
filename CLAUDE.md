# CLAUDE.md — Working conventions for Jethro

Jethro is a multi-asset trading platform: realtime market data → algos → orders →
risk/PnL per book, with a monitoring UI. Owner: Oleg (strong Java/C++, backend-focused).

## Ground truth

- Architecture: `docs/architecture/overview.md`
- Decisions: `docs/adr/` — **read the ADR index before proposing designs or stacks.**
  Accepted ADRs are settled; do not re-litigate them. To change one, propose a
  superseding ADR.

## Design-first rule

Any architecturally significant choice (hard to reverse, cross-service, cost/latency
impact, constrains future work) requires an ADR **before** implementation. Use the
`adr` skill. Small, local, reversible choices do not need ADRs — just make them.

## Stack (per ADRs — summary only)

- Backend: Java 21, Gradle monorepo, Spring Boot 3 for scaffolding, plain Java in data
  paths. C++ only behind a measured latency requirement, as a separate process.
- Messaging: Kafka (MSK Serverless in AWS, Redpanda locally), Avro schemas in
  `common-messaging`.
- Storage: Aurora PostgreSQL (Flyway migrations), S3 Parquet for tick archive.
- UI: TypeScript + React + Vite under `ui/`, AG Grid for blotters, WebSocket streaming.
- Infra: AWS CDK in Java under `infra/`, ECS Fargate runtime.
- Local dev: everything must run via Docker Compose with the sim market-data adapter —
  never make a feature depend on AWS or a paid data feed.

## Hard invariants (violations are bugs)

1. No binary floating point for prices, quantities, or PnL — `BigDecimal` / Avro decimal
   / Postgres `NUMERIC`.
2. External provider symbology never leaks past `market-data-gateway`; internal code keys
   on `instrumentId`.
3. `fills` is the source of truth for positions; only `risk-pnl-service` writes the
   positions projection.
4. Event schemas are contracts: evolve backward-compatibly, never edit published schemas
   in place.
5. Every event carries provider and ingest timestamps.

## Code conventions

- Java: standard formatting, package root `io.jethro.<service>`. Constructor injection.
  No framework types in domain code (`common-domain` is dependency-free).
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

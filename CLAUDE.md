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

## Runtime reality — there is NO always-on instance (check git before diagnosing "no change")

Learned the hard way (chased "why is behaviour unchanged?" assuming a live app was running my
commit — nothing was). **Nothing runs continuously.** Before claiming a change is live, or
explaining why the owner sees no behaviour difference, **read the git commit history first** —
the `chore(status): run …` commits and `reports/` are the ground truth of what actually ran and when.

- **AWS is dormant by default** (ADR-0013 stop-when-idle) and the deploy pipeline (`deploy.yml`) is a
  **manual** `workflow_dispatch` — it may never have run. So there is usually no deployed instance
  serving the UI or working the book. A pushed commit changes **nothing observable** on its own.
- **The continuous-improvement loop (ADR-0063) is the only thing that runs the app** — *ephemerally*,
  per ~30-min cycle: build → boot → the scorer reads PnL/exposure → it commits `reports/` +
  `reports/run-status.json` to **`claude/auto-improve`** → teardown. It is a scoring harness, not a
  live UI the owner watches change in realtime. All reports live on that branch.
- **So a code change becomes observable only when something re-runs it:** a loop cycle (for
  scored PnL/reports) or **AWS brought up / a local `docker compose` run** (for the UI). A **UI**
  change (e.g. a landing-page edit) is deterministic and shows on the next fresh serve — but only once
  the app is actually served to the owner. State this plainly instead of implying a change is "live".
- **Market/session gating:** when the US session is closed the tape is frozen and the loop skips
  analysis ("Market closed — analysis skipped"); flat/unchanged is expected, not a failure. Check the
  session state (and `feedMode`) before reading anything into a flat cycle.
- **A new forecast source earns its weight before it sizes** (ADR-0049/0059/0064): it arrives with no
  track record at neutral/floor weight and must accumulate measured expectancy through telemetry before
  the edge gate lets it put risk on. A slow source (e.g. the ADR-0130 index-trend overlay) also needs
  market-open prints + EWMAC warm-up. So "no dramatic book change immediately after wiring a signal" is
  the designed behaviour — never present a just-added source as if it should have moved PnL this cycle.

## Objective — deploy capital to make money, NOT preserve the status quo (ADR-0132)

Learned the hard way (the loop turned 1,222 orders into ~$122 by keeping the book at ~$39k of a $500k
allowance — because the mission said "hold or reduce exposure" and the daily risk budget was $250).

- **"Better" = grow firm total PnL by DEPLOYING capital up to the owner-set gross budget ($200k, moderate
  vol — ADR-0132) at moderate volatility.** Exposure inside the budget is a resource to **use**, not a
  number to minimize. Only **dead exposure** (risk earning nothing) gets cut. Never re-introduce an
  "exposure must fall" objective — that is the status-quo trap this rule exists to prevent.
- **Undeployed capital under the budget is a failure to attack, not safety.** A flat book while the budget
  is unused is the thing to fix this cycle.
- **State PnL targets honestly against capital and risk.** A dollar target is meaningless without the
  capital base and the vol budget behind it: PnL ≈ gross × daily-return. $1,000/day on $200k is 0.5%/day ≈
  126%/yr — a **strong-day run-rate to build toward**, never a floor the edge guarantees at moderate risk.
  The real floor is **positive expectancy over a rolling 3-day window** at the deployed size. Don't let an
  aspirational dollar figure harden into an assumed-achievable rule (the "no invented numbers" discipline).
- **Bigger book, SAME safety floor.** Scaling size never means relaxing the deterministic floor — the
  gross/net/instrument caps, firm drawdown breaker, conviction floor, edge gate and pre-trade guardrail all
  still stand. Raising *size* dials and relaxing *safety* gates are different decisions; do not conflate.

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
- **"Total" means firm-wide — the real money.** When the owner monitors *total PnL* or *total
  exposure*, it is the whole book **including the hedge** (money actually made net of every cost; all
  money at risk) — the Overview headline. Never present a hedge-stripped or single-book subtotal as the
  headline "total". A decomposition (e.g. strategy alpha vs hedge) is a **diagnostic**, never a
  substitute for the total the owner watches or the loop optimizes. (Learned: the improvement loop
  showed hedge-stripped "alpha" as its headline; it didn't match the Overview total and read as wrong —
  corrected to firm total, ADR-0063.)
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
9. **Sim isolation — the simulator is a TEST tool, never part of the trading platform.**
   The sim is NOT the default (`jethro.trading.provider` defaults to a real feed) and runs
   only when explicitly testing. Nothing sim-specific may *define* the real platform: the
   tradable **universe is the reference-data master** (never a `sim-instruments` list — that
   name is legacy and must not gate real trading), and risk/positions/order routing key off
   **refdata + real `fills`**. Direction is one-way: the sim may CONSUME the real universe
   (tick refdata names when testing), but the platform must never DEPEND on the sim, and sim
   constructs (sim calibration, sim control panel, sim news) stay behind `feedMode==SIM` gates.
   A discovery-promoted name is refdata, so it is first-class everywhere **without** any sim edit.

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
- Reference data is the universe's single source: when a migration adds an instrument to the
  master, the **same change** must backfill its attribute rows — `display_name`, `adv_usd`,
  `spread_bps`, feed symbology (yahoo/finnhub for price-quoted names) + standard identifiers;
  futures/swaps also need contract specs. An instrument in the master without them shows
  blank/generic on the UI. (Learned: the V31 universe expansion added NVDA/JNJ/JPM/AUDUSD but
  not their attributes — backfilled in V34.)
- Books are reference data too: any `book_id` referenced by config or order routing
  (`jethro.hedge.book`, `jethro.strategy.book*`, `jethro.risk.books.*`) MUST have a row in the `book`
  master — the Books view only renders books present in the tree, so a book with positions but no
  master row is silently dropped (not skipped by risk — never shown), and its P&L never rolls up to
  FIRM. (Learned: the HEDGE book had risk limits configured but no master row, so hedge trades were
  invisible on the UI — the BETA demo desk was repurposed as HEDGE in V46.)
- Flyway migration versions are **global, not per-module**: `PersistenceConfig` runs one Flyway over
  `classpath:db/migration`, merging every module's migrations into a single line. Before adding one,
  enumerate `find . -path '*/db/migration/V*.sql'` across the **whole repo** and take the global max + 1
  — versions are scattered across `app/`, `modules/order`, `modules/reference-data`, and gaps exist, so
  "my module's max + 1" is wrong. A duplicate version is not a merge nuisance: Flyway refuses to resolve
  at all and **every DB-backed bean fails, so the app does not boot**. `ModuleBoundariesTest` enforces
  this — module tests can't, since the collision only exists once assembly merges the classpaths.
  (Learned: a `V48__fusion_aim.sql` in `app/` collided with refdata's `V48__sector_breadth_equities.sql`;
  tests were green and the app was dead — renumbered to V51.)

## Response & reasoning style for this repo

- Lead with the recommendation, then the why. One recommendation, not a menu — unless
  the decision is being captured in an ADR, where alternatives belong.
- Finance correctness beats code elegance: when touching PnL/risk math, show the formula
  and a worked numeric example in the PR/response.
- When uncertain about market conventions (day counts, multipliers, settlement), say so
  explicitly and pick the conservative default rather than guessing silently.
- Don't scaffold ahead of the build order in `docs/architecture/overview.md` without
  being asked.

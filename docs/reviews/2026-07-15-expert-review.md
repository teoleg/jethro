# Expert code review — full walkthrough (2026-07-15)

Scope: every module (`common-domain`, `common-messaging`, `trading-core/*`, `modules/*`,
`app`), docs/ADRs, CI, UI pages. Method: architecture + invariant audit, targeted reads
of every load-bearing path, smell sweeps (money types, unbounded state, swallowed errors,
idempotency, escaping, day-basis, lifecycle). Findings are ordered by severity; each has
a file reference and a concrete recommendation. Fixes are deliberately NOT bundled into
this review — each P1/P2 got a tracked task.

## Verdict in one paragraph

The platform is in genuinely good shape for its stage: the hard disciplines actually
hold (exact-decimal money end to end, idempotent consumers with duplicate tests, CAS
exactly-once fills, error paths that count instead of dropping, disclosed conventions,
exact-value finance tests, perf guardrails, honest ADR/register hygiene). The findings
below are real but bounded: one architectural correctness gap that only bites a
long-lived deployment (P1), a handful of consistency/robustness items (P2), and polish
(P3). Nothing suggests systemic quality problems.

## P1 — will produce wrong numbers eventually

### 1. Positions projection replays the `fills` TOPIC from the beginning at boot, but nothing bounds or guarantees topic retention

`RiskDataConsumer` (app/src/main/java/io/jethro/app/risk/RiskDataConsumer.java:82-107)
deliberately uses an ephemeral consumer group and `seekToBeginning` on the fills topic to
rebuild the in-memory `RiskProjection` at every boot. No topic-creation or retention
configuration exists anywhere in the repo, so Redpanda's default (~7 days) applies to the
auto-created topic. Consequence: once the platform has run for longer than the retention
window, a restart silently rebuilds positions from a TRUNCATED fill history — positions,
realized P&L and every downstream number (limits, VaR, EOD equity) are wrong, with no
error anywhere. Invariant 3 names the `fills` **table** (Postgres, written by the order
store) as the source of truth, but nothing reads it back.

Recommendation: at boot, rebuild the projection from the Postgres `fills` table (the
actual source of truth), then subscribe to the topic from LATEST for live increments.
This also fixes the secondary issue that boot-time replay grows unboundedly with
platform age. Alternative (weaker): pin explicit infinite retention on `fills` and
accept growing boot replay. Tracked as a task; dev is unaffected today (nothing has run
7 days), which is exactly why it must not wait for symptoms.

## P2 — correctness/consistency debt to schedule

### 2. `RiskProjection.seenFills` grows without bound

trading-core/risk-pnl/src/main/java/io/jethro/trading/riskpnl/RiskProjection.java:48 —
the idempotency set holds every fillId ever applied, and the boot-time full replay
(finding 1) re-populates it from history each start. Weeks of continuous sim trading
make this a slow memory leak in the risk path. Recommendation: bound it (retention
window keyed to the replay source once finding 1 lands — e.g. only fills newer than the
projection rebuild watermark need dedupe) — and document the bound.

### 3. Valuation-date inconsistency: three surfaces value on WALL date, everything else on SESSION day

The session calendar (sim-compressed days) is the platform's day basis, used by the swap
book, key-rate DV01, VaR and EOD. Three paths still use `LocalDate.now()`:
- app/src/main/java/io/jethro/app/risk/RiskConfig.java:55 (ledger swap DV01 memo),
- app/src/main/java/io/jethro/app/risk/RiskController.java:86 (`/api/swaps` valuations),
- trading-core/risk-pnl/.../ScenarioEngine.java:146 (fresh-tenor per-lot shock reval —
  the fallback leg only; the seasoned hook already uses session day).

Impact is small today (these are fresh-tenor valuations where the date shifts the
schedule marginally) but it means two UI panels can disagree about "today" in sim mode.
Recommendation: inject the session-day supplier into all three (mechanical change).

### 4. Build-order steps 9–10 are NOT built, but the docs claim the modules exist

`infra/` does not exist and `modules/finops` is an empty shell (registered in
settings.gradle.kts, zero sources), yet `docs/architecture/overview.md` lists `finops`
as a module with a role and the repo-layout section previously showed `infra/`.
This is doc drift of the kind the register exists to prevent — and it also corrects an
earlier status claim that "build order steps 1–10 are done": steps 9 (CDK deploy) and
10 (finops + Costs view) are open work. Recommendation: either mark both as
"planned, not built" in overview.md, or schedule them; do not leave the table asserting
a module that has no code.

### 5. No authentication on any endpoint — fine now, a hard gate later

9 mutating endpoints (order submit/cancel, quarantine clear, chat, config) plus all read
surfaces are unauthenticated; no Spring Security anywhere. Correct trade-off for a
single-user dev box on a LAN, but it must join the ADR-0015 order-module-extraction gate
as a precondition for ANY deployment beyond the Pi (the AWS dev node is reachable
infrastructure). Recommendation: add one line to the deferred register with the trigger
"before any non-localhost deployment"; the eventual fix is boring (an auth filter), the
risk is forgetting it.

## P3 — polish / minor

6. **Scenario seasoned-swap hook queries `swap_trades` per (position × rates scenario)**
   (RiskConfig scenarioEngine wiring): `/api/scenarios` polled by the UI triggers
   3 rates scenarios × N swap positions small queries + Strata revals per poll. Cache
   the trade list per engine run (or 1s memo) — same pattern as the DV01 memo.
7. **`config.html` and `ollama.html` have the thinnest escaping coverage** of the eight
   pages (esc()×2 vs innerHTML×4). Everything I spot-checked interpolates self-entered
   config or server enums, so no injection path today — but these pages render with less
   discipline than index/orders/books; worth a 15-minute pass to route all interpolation
   through `esc()`.
8. **The `-Pci` test gate bit this session twice**: `gradle build` without `-Pci`
   silently skips every unit test, and a "BUILD SUCCESSFUL in 3s" reads like a pass.
   Consider printing a loud one-line warning when the suite is skipped, so a local
   contributor can't mistake a skip for a pass.
9. **Duplicate in-memory quote caches**: `LastPriceCache` (order) and `QuoteCache`/
   `MarkCache` (runtime) hold overlapping last-value state fed by the same stream —
   correct but redundant; a future consolidation candidate, not a bug.

## What I looked for and did NOT find (attestations)

- **No binary-float money anywhere**: every `double` sits in analytics (Strata, vol,
  sim) with `BigDecimal` conversions at stated boundaries. Invariant 1 holds.
- **Idempotency**: every Postgres write path uses `on conflict` upserts or CAS
  transitions; the order fill path is exactly-once under races (synchronized +
  DB CAS, fill row written only by the CAS winner); consumers ship duplicate-delivery
  tests (RiskProjection, OrderService, LMDB store).
- **Error-path discipline**: data-path errors count + log + expose (rejectedTicks,
  dropped, skipped exposure); no silent swallows found in data paths.
- **Module boundaries**: `ModuleBoundariesTest` (ArchUnit) exists and runs in CI.
- **Secrets**: the Finnhub token flows env → property → header only; never logged,
  never committed (the `FINNHUB` vs `FINNHUB_TOKEN` split is script-arg vs env-default,
  both wired correctly in run-local.sh).
- **XSS**: model output (commentary, chat answers) and all instrument/book strings are
  routed through `esc()` on the high-traffic pages.
- **AI containment**: inference is off the tick path, single-flight guarded, decisions
  audited to `ai.decisions`; autonomy defaults OFF behind the track-record gate.

## Suggested order of attack

1. Finding 1 (fills replay) — the only item that silently corrupts numbers.
2. Findings 2 + 3 together (both touch the projection/valuation plumbing).
3. Finding 4 doc fix immediately; decide whether infra/finops are next build items.
4. Finding 5 register row now; implementation with the broker milestone.
5. P3 items opportunistically.

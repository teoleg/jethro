# ADR-0028: UI stays server-served static pages; React deferred behind concrete triggers

- **Status:** Accepted (records the owner's 2026-07-15 decision: "no react, it doesn't make sense at this stage")
- **Date:** 2026-07-15
- **Deciders:** Oleg
- **Tags:** ui
- **Supersedes:** ADR-0006

## Context

ADR-0006 (design phase) chose a TypeScript + React + Vite SPA under `ui/`. The UI that
actually got built — and that carried the whole platform through the ADR-0017
attention-first rework, rates/risk/TCA/backtest surfaces and the operator flows — is
eight self-contained static HTML pages served from module resources (`ui-gateway`,
`order`, `reference-data`), ~1,700 lines total, no build step, no dependencies, polling
REST at 1–5s intervals. It works, it is testable on the Pi with nothing but
`git pull` + Gradle + compose, and the owner is backend-focused.

Migrating now would rewrite working UI for a pixel-equivalent result while adding a Node
toolchain to the repo, CI, and the Pi workflow — cost without capability. Doing nothing,
however, would leave an Accepted ADR silently violated; this ADR makes the current state
the documented decision.

## Decision

We will **keep the UI as server-served static HTML pages** and defer any React/Vite
adoption behind concrete triggers. No `ui/` scaffold, no Node toolchain, no npm
dependencies until then.

Triggers that reopen the decision (via a new ADR, scoped to the views that need it):

1. A view genuinely needs **streaming live grids** — the ADR-0017 evidence views at real
   scale (virtualized rows, live row updates, grouping) where hand-rolled tables run out
   of road; this is where AG Grid earns a toolchain.
2. **Shared cross-panel client state** — e.g. one WebSocket feed driving blotter, tiles
   and ticket simultaneously with optimistic updates.
3. Three or more pages need the **same non-trivial interactive component** (today's
   duplication — nav bar and styles copied per page — is cosmetic and accepted).

Until a trigger fires, pages keep polling REST; the ADR-0006 WebSocket subscription
protocol is deferred with the SPA.

## Alternatives considered

**Migrate to React now (execute ADR-0006).** Weeks of churn for no new capability, plus
Node on the Pi or CI-built artifacts to manage. The ecosystem arguments in ADR-0006
remain valid *at the point the triggers fire* — they do not justify the cost today.
Rejected at this stage.

**A lightweight middle layer (htmx/Alpine).** Reduces some duplication but introduces a
dependency and idiom without solving the one hard future problem (streaming grids), so
it spends novelty budget on the wrong thing. Rejected.

**Leave ADR-0006 Accepted and drift.** Free today, but the ADR index would assert a
stack that doesn't exist — exactly the record-rot ADRs exist to prevent. Rejected.

## Consequences

- Positive: zero toolchain, zero dependencies, Pi workflow unchanged; UI iteration stays
  a single-file edit; the decision record matches reality again.
- Negative: per-page duplication of nav/styles persists; polling latency (1–5s) is the
  refresh floor; when the grid trigger eventually fires, the migration lands as a bigger
  step than an incremental one would have been — accepted knowingly.
- Follow-ups: ADR-0006 marked Superseded by this ADR; CLAUDE.md and
  `docs/architecture/overview.md` stack summaries updated; a future ADR re-scopes
  React/AG Grid if and when a trigger fires.

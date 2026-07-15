# ADR-0006: UI in TypeScript + React (Vite), streaming over WebSocket

- **Status:** Superseded by ADR-0028 (UI stays server-served static pages; React deferred behind concrete triggers)
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** ui

## Context

The UI is a monitoring console: a landing page of tiles, each opening a view — Market
Monitor, Order View, Book Structure, Risk & PnL per book. These views are dominated by
live-updating grids (blotters) and charts fed by streaming data. The owner is a backend
developer and suggested "some HTML + JavaScript"; the priority is getting capable views
quickly, not front-end craftsmanship.

## Decision

We will build the UI as a **single-page app in TypeScript + React, bundled with Vite**:

- **AG Grid (community)** for blotters — order view, positions, risk grids; it handles
  streaming row updates, sorting, and grouping (book → position) out of the box.
- **Lightweight Charts** (TradingView OSS) for price/PnL charts.
- **Native WebSocket** connection to `ui-gateway` for streaming; REST for initial
  snapshots. One subscription protocol: client sends `{subscribe: <view-topic>}`,
  server pushes deltas.
- Plain CSS (or a minimal utility layer) — no heavy design system.
- Routing: landing page `/` with tiles → `/market`, `/orders`, `/books`, `/risk/:bookId`.

Deployed as static files (S3 + CloudFront, ADR-0007); the browser talks only to
`ui-gateway`.

## Alternatives considered

**Plain HTML + vanilla JS.** Zero build step, but live blotters, routing, and shared
state across four views get painful fast; grid capability alone justifies the toolchain.
TypeScript also lets AI assistance and refactoring work far better. Rejected.

**Server-side rendering (Thymeleaf/JSP from Java).** Familiar to a Java developer, but
fundamentally wrong for push-based live views. Rejected.

**Vue/Svelte.** Fine choices; React has the deepest ecosystem for trading-style grids and
the most training data for AI-assisted development. Rejected on ecosystem, not merit.

## Consequences

- Positive: capable live grids on day one; typed API contracts shared with the gateway
  (generate TS types from Avro/OpenAPI); trivial static hosting.
- Negative: a Node toolchain enters the repo (isolated under `ui/`); React learning curve
  for the owner — mitigated by keeping the app structurally boring.
- Follow-ups: define the WebSocket subscription protocol in `docs/architecture/`; wireframe
  the four views before building.

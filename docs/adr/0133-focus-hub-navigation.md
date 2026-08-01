# ADR-0133: Focus-hub navigation — group the flat 15-tab menu into tiled hubs

- **Status:** Implemented (phase 1 — hubs added; global nav swap deferred)
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** ui, navigation, ia

## Context

The shared top nav had grown to **15 flat tabs** (Overview, Improve, Markets, Rates, Books, Orders, Config,
Sim, Backtest, Strategy, Signals, Social, Sources, Discover, Ops) — no grouping, no focus. The owner asked
to *"organize by focus"* with modern **tile** landing pages: a small set of focus hubs, each tile showing a
short summary and clicking through to the **existing** page. Explicit constraints: **don't change the
landing (Overview) yet, and don't change the content of the existing pages** — this is navigation and info
focus only.

## Decision

Add a thin, **additive** navigation layer — new tile pages, zero edits to existing pages — under
`modules/ui-gateway/.../static/`, sharing one `hub.css` + `hub.js`. Four focus hubs (owner's grouping):

| Hub | Tiles → existing pages |
|---|---|
| **Status** (blue) | Books, Orders, Markets, Rates |
| **Strategy** (green) | Strategy, Signals, Basket *(planned)*, Discovery *(→ Discovery hub)* |
| **Discovery** (amber) | Discovery, Sources, Social |
| **Ops** (grey, separate) | Improve, Config, Sim, Backtest, Models (Ollama) |

- **Filenames avoid collisions:** hubs are `hub-status/strategy/discovery/ops.html` because `strategy.html`
  and `discovery.html` are existing content pages the hubs link INTO (unchanged).
- **Focused nav:** the hubs carry a 5-item bar — Overview + the four hubs — instead of 15 tabs.
- **Tiles show a live summary, best-effort:** `hub.js` reads ONE headline per tile from the **same REST
  endpoints the existing pages already poll** (`/api/risk`, `/api/orders/day`, `/api/market/regime`,
  `/api/strategy/actions`, `/api/fusion/targets`, `/api/hypotheses`, `/api/feeds`, `/api/improve/status`).
  A failed/empty fetch leaves the tile's static description — a hub is navigation, never a place a number
  can mislead. No new endpoint, no new controller (static resources auto-serve).
- **Basket** has no page yet — shown as a dimmed **Planned** tile so the intended structure is visible with
  no broken link.
- **Ops** absorbs the operational pages the owner didn't slot elsewhere (Config, Sim, Backtest, Improve,
  Models) — the natural home for tooling; revisit if the owner wants them placed differently.

## Consequences

- The owner gets focused, modern navigation now, with **zero risk to existing pages** (purely additive; the
  old 15-tab nav still works everywhere else).
- **Deferred — phase 2 (tracked):** swap the *global* nav in the existing pages + the Overview landing to
  point at these hubs, once the owner signs off on the grouping. Until then the hubs are reachable directly
  (`/hub-status.html`, …) and cross-linked, but the old nav is untouched (landing constraint honoured).
- Reversible: deleting four HTML files + `hub.css`/`hub.js` removes the layer entirely.

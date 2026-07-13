# ADR-0009: Market data provider abstraction

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** market-data, backend

## Context

Realtime data will come from commercial providers whose APIs, symbologies, entitlements,
and costs differ wildly (Polygon, Alpaca, IEX Cloud, Databento; later possibly
Refinitiv/Bloomberg). Provider choice must stay reversible, dev must work without paid
subscriptions or market hours, and downstream services must never see provider-specific
formats.

## Decision

The `market-data-gateway` (ADR-0003) owns a **provider SPI**: a small Java interface —
`connect / subscribe(instrumentIds) / stream of normalized events / health` — with one
adapter per provider. Adapters translate provider symbology to `instrumentId` via the
symbology map (ADR-0008) and emit the **internal normalized schema** (`Quote`: bid/ask/
sizes; `Trade`: price/size; both with provider timestamp + ingest timestamp) onto
`md.quotes` / `md.trades`.

Two adapters ship first:

1. **`sim` adapter** — deterministic random-walk generator, seedable, runs offline,
   any instrument, configurable tick rate. This is the default in local dev and tests
   and unblocks the entire platform before any provider contract exists.
2. **One real provider** — selected when live data is actually needed; candidate order:
   Polygon (WebSocket, good docs, cheap starter tier) → Databento (quality, usage-based).
   The selection is a config change, not a code change, and gets its own mini-ADR.

Rules: dual timestamps on every event (provider vs ingest) so latency and staleness are
measurable; per-provider entitlement limits enforced in the adapter; gateway conflation
(latest-value per instrument at a max rate) is a config knob for UI-bound streams.

## Alternatives considered

**Code directly against one provider.** Fastest today, rewrite when pricing/entitlement
changes force a switch — a well-known trap. Rejected.

**Buy a normalization layer.** Overkill at this scale; the SPI is small. Rejected.

**Skip the sim adapter.** Makes development hostage to market hours and API keys, and
tests non-deterministic. Rejected.

## Consequences

- Positive: platform fully developable offline; provider switching is contained to one
  adapter; latency measurable end-to-end from day one.
- Negative: normalized schema is lowest-common-denominator — provider-specific richness
  (order-book depth, auction data) needs schema evolution when required.
- Follow-ups: mini-ADR to pick the first real provider; define conflation defaults for
  UI streams.

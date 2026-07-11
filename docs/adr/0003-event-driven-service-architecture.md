# ADR-0003: Event-driven services around a streaming backbone

- **Status:** Proposed
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** architecture

## Context

The platform has naturally streaming workloads: market ticks arrive continuously,
algorithms react to them, fills mutate positions, and risk/PnL must update live per book.
Multiple consumers need the same events (algo engine, risk service, UI). Components have
different scaling profiles — market data fan-in is bursty, risk recalc is CPU-bound, the
UI gateway is connection-bound.

## Decision

We will structure the backend as a small set of **event-driven services** communicating
through a durable log (see ADR-0004), with these initial services:

| Service | Responsibility | Consumes | Produces |
|---|---|---|---|
| `market-data-gateway` | Connect to providers, normalize to internal tick schema | provider feeds | `md.quotes`, `md.trades` |
| `algo-engine` | Run pluggable strategies over normalized data | `md.*` | `orders.new` |
| `order-service` | Order lifecycle (new → routed → filled/cancelled), simulated execution initially | `orders.new` | `orders.events`, `fills` |
| `risk-pnl-service` | Positions per book, realized/unrealized PnL, exposures; live recalc on marks | `fills`, `md.*` | `risk.snapshots` |
| `reference-data-service` | Instruments, symbology, books and their structure | — (REST/DB) | `refdata.updated` |
| `ui-gateway` | BFF: REST snapshots + WebSocket streaming to the UI | `md.*`, `orders.events`, `risk.snapshots` | — |

Rules:
- Events are the **only** inter-service data path for market/trade flow; REST is allowed
  for reference data lookups and UI snapshots.
- Every event stream has a versioned schema in a shared registry (`common-messaging`).
- Services are independently deployable; each owns its own state store.
- Start as one repo, separate deployables — services may be co-deployed early to save cost,
  but the topic boundaries are respected from day one.

## Alternatives considered

**Monolith.** Fastest start, but couples scaling profiles and makes the C++ escape hatch
(ADR-0002) and independent risk-recalc scaling painful. The event boundaries are the
design; collapsing them saves little. Rejected — though early co-deployment keeps cost down.

**Request/response microservices (REST/gRPC between services).** Wrong shape for
tick-driven flow; every hop adds latency and coupling; no replay. Rejected for the data
path, retained for reference data.

## Consequences

- Positive: replayable history (backtesting, debugging), independent scaling, clean seams
  for future C++ components.
- Negative: eventual consistency between views; operational surface of a broker.
- Follow-ups: define event schemas (`common-messaging`) before first service; ADR-0004
  picks the broker.

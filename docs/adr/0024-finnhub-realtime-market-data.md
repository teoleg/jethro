# ADR-0024: Finnhub real-time market data — free WebSocket equities feed, composed with Yahoo/sim for the rest

- **Status:** Accepted (a second real provider behind the ADR-0009 port; complements ADR-0023)
- **Date:** 2026-07-13
- **Deciders:** Oleg
- **Tags:** market-data, dev, ai

## Context

The Yahoo adapter (ADR-0023) gives real prices but is a poll against an unofficial,
rate-limited endpoint: bursts get 429'd, so marks arrive slowly and sparsely. That laggy feed
has downstream symptoms — auto-orders get REJECTED for "no market data" when no fresh mark
exists, and the indicators strip competes for the same IP budget. The owner wants a genuinely
live feed. Finnhub offers a **free real-time trade WebSocket** for US equities (email-signup key):
push, not poll, so no rate-limit-on-polling and sub-second updates. Its free tier does **not**
cover index/FX futures or a SOFR curve, so it can't be the whole feed.

Same bounds as ADR-0023: dev/demo only, never production/real-money (ADR-0015 gates a real
broker); provider symbology must not leak past the gateway (invariant 2); provider + ingest
timestamps carried (invariant 5); offline/CI keep the sim.

## Decision

We will add a **`FinnhubMarketDataAdapter`** behind the ADR-0009 port, `provider=finnhub`
(needs `finnhub-token`), that **composes** three sources so each instrument gets the best free
data available:

- **Finnhub WebSocket** streams real-time trades for the covered US equities (AAPL/MSFT/AMZN/GOOG
  via `finnhub` symbology, V12). `FinnhubWebSocketClient` connects to `wss://ws.finnhub.io`,
  subscribes, reconnects with backoff; `FinnhubTradeParser` extracts `{s,p,t}` (exact `BigDecimal`
  price, invariant 1). Real-time → effectively no data delay.
- **Yahoo (background)** supplies the price-quoted rest it covers — futures/FX/SAP — via the
  ADR-0023 adapter (now only ~5 symbols, so far less throttling); the **SOFR curve/swaps** ride
  that background (sim curve). If Yahoo is unavailable, the background is the sim.
- **Fallback:** blank token or missing symbology ⇒ fall back to the sim feed, logged.

The composite is a `MarketDataAdapter` wrapping a background `MarketDataAdapter` — no special
casing downstream; the feed status reports `finnhub` (real-time).

## Alternatives considered

**Finnhub for everything.** Rejected — its free tier is US equities only; futures/FX/curve would
have no data. Composition is what lets each instrument use the best free source.

**Replace Yahoo entirely.** Rejected — Yahoo still usefully covers futures/FX that Finnhub's free
tier doesn't, and stays the zero-signup option. Both live behind the same `provider` switch.

**Alpaca (IEX) instead.** Deferred — comparable free real-time equities + crypto and a future
paper-trading path, but needs key+secret and is a bigger surface; revisit if we want crypto or
real paper trading (it would be its own ADR).

## Consequences

- Positive: real-time equity marks with no polling limits — fresh marks mean far fewer
  "no market data" order rejections; Yahoo's load drops to a handful of symbols so it and the
  indicators strip stop starving each other; the ADR-0009 port proves out a push (WebSocket) feed.
- Negative: a third moving part and a **new external dependency requiring an API key**; Finnhub's
  free tier is equity-only and IEX-ish coverage, and the WebSocket needs live verification on the
  host (CI/offline stay on the sim). Still dev/demo — the real-broker gate (ADR-0015) is unchanged.
- Follow-ups: an Alpaca adapter (crypto / paper trading); surface per-source status distinctly if
  the composite grows; a licensed provider ADR before any production use.

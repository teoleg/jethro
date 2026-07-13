# ADR-0023: Yahoo Finance market-data adapter — a free, delayed, dev/demo-only provider behind the ADR-0009 port

- **Status:** Proposed (realises the ADR-0009 provider abstraction with a first real adapter)
- **Date:** 2026-07-13
- **Deciders:** Oleg
- **Tags:** market-data, dev, ai

## Context

Local dev runs on the seedable sim feed (ADR-0009): great for determinism and offline work,
but the prices aren't real, so the book and the hypothesis layer (ADR-0022) reason over
made-up moves. The owner wants a **free, real** market-data source to see actual prices and
ground the demo. Yahoo Finance is the obvious zero-cost option.

The constraints are real. Yahoo has **no official/licensed API** — the usable endpoints
(`query1.finance.yahoo.com/v8/finance/chart/{sym}`, `/v7/finance/quote`) are undocumented,
increasingly require a cookie+crumb handshake, are **rate-limited** (HTTP 429 on abuse), and
serve data **delayed ~15 min** for many venues. Their ToS restricts use to personal,
non-commercial, non-redistributed consumption. There is **no push/streaming** — it is poll
only. So this can ground a dev/demo but is **not** a production or real-money feed: ADR-0015
already forbids a real broker until the order module is extracted, and a real-money data feed
needs a licensed provider. Two invariants also bind: provider symbology must not leak past the
gateway (invariant 2 — Yahoo tickers like `ES=F`, `^GSPC`, `EURUSD=X` map to `instrumentId`),
and every event carries provider + ingest timestamps (invariant 5), with the delay made visible.

## Decision

We will add a **`YahooMarketDataAdapter`** implementing the ADR-0009 `MarketDataAdapter` port,
scoped as a **dev/demo source only**, selectable alongside the sim and **default OFF** (sim
stays the default). Specifics:

- **Poll, don't stream:** a scheduled batch `/v7/finance/quote` (with the cookie+crumb handshake)
  every N seconds (default ~15s), rate-limit-aware with exponential backoff on 429; the tick
  cadence and instrument set come from config.
- **Symbology at the gateway:** a `yahoo`-source symbology mapping (like the existing `sim`/`GLOBEX`
  entries) translates Yahoo tickers to `instrumentId`; internal code never sees a Yahoo symbol
  (invariant 2). Prices convert to exact `BigDecimal` at the boundary (invariant 1).
- **Honesty:** the ~15-min delay is carried as the provider timestamp and surfaced (a "delayed"
  badge), never presented as live; fetch failures/drops are counted, logged, and metered
  (never silently dropped), and the adapter degrades to no-mark rather than fabricating a price.
- **Dev-only gate:** documented and configured as non-production; promotion to a real feed requires
  a licensed-provider ADR. Offline/CI keep using the sim (ADR-0009) so nothing depends on Yahoo.

## Alternatives considered

**A licensed feed now (Polygon / Alpaca / IEX Cloud).** Deferred — real-time, ToS-clean, but
paid; revive when the demo needs production-grade data or a real broker lands (ADR-0015). Yahoo
is the free stepping stone to exercise the provider port with real prices.

**Stay sim-only.** Rejected against the owner's explicit ask for real prices; the sim remains the
default and the test/CI feed, but it can't ground the demo or the hypothesis layer in reality.

**Scrape the Yahoo web page / use an embedded third-party lib.** Rejected — more brittle and more
ToS-exposed than the JSON endpoints; a thin first-party HTTP adapter is easier to rate-limit,
audit, and rip out.

## Consequences

- Positive: real (delayed) prices in dev with zero cost; the ADR-0009 port gets its first real
  adapter, proving the abstraction; the hypothesis/quant layers reason over genuine moves.
- Negative: an **unofficial, ToS-limited, breakable** dependency — endpoints/crumb flow can change
  without notice and must never reach production or real-money paths; polling adds load and the
  15-min delay must stay visible so no one trades it as live.
- Follow-ups: the adapter + `yahoo` symbology seed; a provider-selection config switch; a licensed
  market-data provider ADR before any production or real-money use.

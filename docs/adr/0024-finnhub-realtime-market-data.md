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

### Real US Treasury yield curve (replaces the factor sim for rates)

The rates stack (DV01, swap PV, rate scenarios, the Rates-page curve) priced on the seedable
**factor-sim** SOFR curve — real methodology, simulated levels. With a Finnhub key we now drive
it from a **live US Treasury curve**:

- A `CurveMarkSource` port abstracts the curve behind the market-data adapters. Two impls:
  `CurveFactorSimulator` (sim, the default and CI) and `RealTreasuryCurve` (live levels, updated
  out-of-band). Adapters emit tenor + swap-par marks from whichever is active, so **everything
  downstream reprices unchanged** — one code path, real or sim.
- `FinnhubYieldCurveClient` fetches the Treasury curve (Jackson, tolerant of a few payload
  shapes) and maps it to the `USD.SOFR.*` node zeros the calibration already consumes.
  `RealTreasuryCurve` interpolates in year-space and computes swap par from those zeros.
- **Selected when `jethro.trading.real-curve` and a token are set**, via a **synchronous startup
  probe**: data → live curve (refreshed every `treasury-curve-refresh-seconds`); a gated/empty
  probe → **fall back to the sim curve, logged** (`RATES CURVE: …`). No fabricated levels —
  Treasury futures are still priced by the market feed, not the curve.
- Convention caveat (finance-math rule): Treasury par yields are not SOFR-OIS discounting; we
  label the curve "US Treasury · live" in the UI rather than silently calling it SOFR.
- **Premium risk:** Finnhub bond endpoints may not be on the free tier. If the probe is gated,
  the platform runs on the sim curve and the host log says so; the free **US Treasury direct
  feed** is the fallback provider (its own follow-up).

### Real news for the hypothesis layer (fulfils ADR-0022's deferred feed)

ADR-0022 stood up the LLM hypothesis layer on a **seedable sim narrative feed** and explicitly
deferred a real news provider. The same Finnhub key now supplies that feed via its **REST news
endpoints** — a second, read-only use of the provider, off the tick path:

- `GET /news?category=general` → market-wide macro headlines (`NarrativeItem.Category.MACRO`).
- `GET /company-news?symbol=…&from=…&to=…` → per-name headlines for the covered equities
  (`NEWS`/`EARNINGS`), pulled only for instruments with `finnhub` symbology.

`FinnhubNewsClient` (Jackson-parsed, defensive — any HTTP/parse error yields no items, never
throws) feeds `FinnhubNarrativeFeed`, which implements the new `NarrativeFeed` port alongside
`SimNarrativeFeed`. It is selected **whenever a Finnhub token is set** — independent of the price
`provider`, so a Yahoo/sim price run still gets real headlines; **CI and offline (no token) keep
the sim fixtures**. Bounds honoured: Finnhub symbols are used **only to query** — every
`NarrativeItem` keys on the internal `instrumentId` (invariant 2); the model reads only the
headline text and a **coarse keyword sentiment tag, never a number** into sizing/risk
(invariant 1/7 — the deterministic quant layer still owns every number). Refreshes are throttled
(`jethro.hypothesis.narrative-refresh-seconds`, default 60) so the hypothesis cadence never
hammers the free-tier quota. Sentiment is a documented heuristic, not a classifier — a licensed
news/NLP provider would be its own ADR.

## Alternatives considered

**Finnhub for everything.** Rejected — its free tier is US equities only; futures/FX/curve would
have no data. Composition is what lets each instrument use the best free source.

**Replace Yahoo entirely.** Rejected — Yahoo still usefully covers futures/FX that Finnhub's free
tier doesn't, and stays the zero-signup option. Both live behind the same `provider` switch.

**Alpaca (IEX) instead.** Deferred — comparable free real-time equities + crypto and a future
paper-trading path, but needs key+secret and is a bigger surface; revisit if we want crypto or
real paper trading (it would be its own ADR).

### Shared REST budget

Finnhub's free-tier limit — **60 calls/min — is account-wide** (keyed on the API key, pooled
across every REST endpoint; only the trade WebSocket is exempt). So a **single**
`FinnhubRateLimiter` (sliding 60s window, default cap 55 for headroom) is injected into **every**
REST client — news and yield curve today, anything added later — and a client that can't acquire
simply skips that cycle. The combined rate therefore cannot cross the cap regardless of how many
pollers exist; per-client throttles alone couldn't guarantee that.

## Consequences

- Positive: real-time equity marks with no polling limits — fresh marks mean far fewer
  "no market data" order rejections; Yahoo's load drops to a handful of symbols so it and the
  indicators strip stop starving each other; the ADR-0009 port proves out a push (WebSocket) feed.
- Negative: a third moving part and a **new external dependency requiring an API key**; Finnhub's
  free tier is equity-only and IEX-ish coverage, and the WebSocket needs live verification on the
  host (CI/offline stay on the sim). Still dev/demo — the real-broker gate (ADR-0015) is unchanged.
- Positive (news): the LLM finally reasons over **real headlines** instead of sim fixtures —
  closing the "News → LLM Layer" edge of the intended pipeline — reusing the key already required
  for prices, at zero extra setup.
- Follow-ups: an Alpaca adapter (crypto / paper trading); surface per-source status distinctly if
  the composite grows; Finnhub `/bond/yield-curve` (real rates), `/stock/candle` (real backtests)
  and `/forex/rates` (real FX) are further real-data upgrades; a licensed news/market-data provider
  ADR before any production use.

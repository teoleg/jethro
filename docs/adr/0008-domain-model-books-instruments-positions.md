# ADR-0008: Domain model — books, instruments, positions, marks

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** domain, backend

## Context

The platform supports multiple books, each holding positions across various instrument
types (equities first; futures, options, FX to follow). Risk and PnL are reported per
book. The UI's Book Structure view needs a navigable hierarchy. A shared vocabulary is
needed before any service is written — these types live in `common-domain` and shape the
event schemas (ADR-0004) and Postgres tables (ADR-0005).

## Decision

Core entities:

- **Instrument** — internal `instrumentId` (stable surrogate), `assetClass`
  (EQUITY | FUTURE | OPTION | FX | BOND), asset-class-specific attributes (expiry, strike,
  underlying...), currency, and a **symbology map** of external identifiers
  (`{POLYGON: "AAPL", BLOOMBERG: "AAPL US Equity", ...}`). External symbols never leak
  past the market-data gateway; everything internal keys on `instrumentId`.
- **Book** — `bookId`, name, base currency, optional parent book (books form a tree;
  risk/PnL aggregates up the tree). A book contains positions, not instruments directly.
- **Position** — keyed `(bookId, instrumentId)`: signed quantity, average cost.
  Positions are a **projection of fills**: `fills` is the source of truth, the
  risk-pnl-service is the only writer of the projection (ADR-0005).
- **Order / Fill** — order: side, quantity, type, limit price, status lifecycle
  (NEW → ROUTED → PARTIALLY_FILLED → FILLED | CANCELLED | REJECTED), owning book.
  Fill: execution price/quantity against an order.
- **Mark** — latest valuation price per instrument, derived from market data with an
  explicit source and timestamp (staleness must be visible in risk output).

PnL and risk, per position and aggregated per book:

- **Realized PnL** — average-cost method on reducing fills. (FIFO tax-lot accounting is a
  future ADR if ever needed; average-cost is simpler and standard for book monitoring.)
- **Unrealized PnL** — `quantity × (mark − avgCost) × contractMultiplier`, in instrument
  currency, converted to book currency at current FX marks.
- **Risk v1** — gross/net exposure, per-asset-class and per-underlying concentration,
  simple price-shock scenarios (±X%). Greeks and VaR arrive with the options asset class
  (future ADR).

All money as `BigDecimal`/fixed-decimal in Java, decimal logical types in Avro, `NUMERIC`
in Postgres — never binary floating point for quantities, prices, or PnL.

## Alternatives considered

**Ticker-string keys everywhere.** Simple until the first symbol clash (same ticker on
two venues) or rename. Surrogate IDs + symbology map is standard for a reason. Rejected.

**Flat books (no hierarchy).** The Book Structure view and desk-level aggregation both
want a tree; parent pointers cost nothing now and are painful to retrofit. Rejected.

**Tax-lot (FIFO) accounting from day one.** Meaningful complexity (lot storage, lot
matching) serving no current requirement. Deferred behind a future ADR.

## Consequences

- Positive: one vocabulary across events, storage, and UI; multi-asset ready without
  over-modeling; staleness explicit in marks.
- Negative: average-cost realized PnL differs from tax reporting; FX conversion adds a
  dependency on FX marks even for an equities-only book (seed with static rates initially).
- Follow-ups: `common-domain` module is the first code written; Avro schemas mirror these
  types; future ADRs for options Greeks/VaR and lot accounting.

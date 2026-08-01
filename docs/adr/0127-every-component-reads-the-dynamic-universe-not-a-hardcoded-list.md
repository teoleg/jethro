# ADR-0127: Every component reads the dynamic universe, never a hardcoded security list

- **Status:** Implemented
- **Date:** 2026-07-29
- **Deciders:** Oleg
- **Tags:** universe, refdata, discovery, invariant-9, data-pipeline

## Context

The universe is meant to be **dynamic** — seeded by migrations and grown at runtime by ADR-0060 discovery
promotion — and invariant 9 is explicit: *the tradable universe is the reference-data master, never a
`sim-instruments` list; a discovery-promoted name is refdata, so it is first-class everywhere without any
edit.* Yet several components were quietly keying off **hardcoded lists** instead of the master, so a
promoted or migration-seeded name (including the ADR-0125 sector expansion) was silently invisible to them:

- `HistorySeeder` (hedge-covariance seed), `TrainingBarsLoader` (learned-signal training), and
  `SimSnapshotController` all iterated **`props.simInstruments()`** — the legacy config list CLAUDE.md
  names as the thing that "must not gate real trading."
- All three looked up a proxy symbol in **`HistorySymbols.PROXY`**, a static hardcoded map.
- `scripts/fetch_bars.py` — which fetches the daily history the OOS backtest validates on — carried its
  own hardcoded `SYMBOLS` map, so a name it didn't list could **never** get history and therefore could
  **never clear the ADR-0049 gate**.

The result was a universe that looked dynamic but whose history/training/backtest pipeline was pinned to
a stale hand-maintained set. "As it happened before."

## Decision

Every component derives its universe from the **refdata master** and derives provider symbols from asset
class, with tiny override maps only for cases a derivation genuinely cannot know:

- **`InstrumentRefSource.instrumentIds()` is the one universe source.** `HistorySeeder`,
  `TrainingBarsLoader` and `SimSnapshotController` now iterate it (each name's `assetClass` from
  `find(id)`), not `simInstruments()`.
- **`HistorySymbols` became a derivation** — `proxyFor(id, assetClass)`: EQUITY → ticker, FX → lowercase
  pair, else skip; overrides only for `ES→SPY`, `NQ→QQQ` (index-future ETF proxy) and `GOOG→GOOGL`. The
  static `PROXY` map is gone.
- **New `GET /api/universe`** exposes the master (id + assetClass; no provider symbology crosses the
  boundary, invariant 2). `scripts/fetch_bars.py` reads it and derives Stooq symbols (`<ticker>.us` / FX
  pair / `^spx`/`^ndx` overrides), so it fetches history for the live universe — discovery names included
  — with **no hardcoded list**. If the endpoint is unreachable it fails honestly rather than fetching a
  stale list.

## Consequences

- **A promoted or seeded name is now first-class end-to-end** — hedge covariance, training bars, sim
  snapshot and backtest history all pick it up automatically, which is what invariant 9 always intended.
  The ADR-0125 sector names now get history, so they can actually be OOS-validated.
- **One derivation, not N hand-maintained maps.** New equities need no edit anywhere; only a genuinely
  new *asset class* or an irreducible provider quirk touches the small override maps.
- **`fetch_bars.py` now depends on the app being up** to read the universe — correct for the loop (the
  app is running) and honest otherwise (it refuses to fetch against a stale list rather than guess).
- Validated: full app Spring context loads with `InstrumentRefSource` injected into the changed beans;
  trading + training tests green.

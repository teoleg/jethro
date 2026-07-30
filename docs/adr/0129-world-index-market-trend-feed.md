# ADR-0129: World index market-trend feed (US / EU / Asia), marked but non-tradable

- **Status:** Implemented
- **Date:** 2026-07-30
- **Deciders:** Oleg
- **Tags:** universe, market-data, reference-data, ui, risk

## Context

The desk had no at-a-glance read of the broad market. The tradable universe carries single names and
two index *futures* (ES/NQ), but there was no cross-region panel of the major indices — US, Europe, Asia —
to show where the market is trending, the context every discretionary read starts from. Oleg asked for it
(and rightly noted it should have been proposed): "add all major indexes into the mix — US, EU, Asia — and
a list that shows market trend."

Two constraints shape the design:

1. **Universe = reference-data master (invariant 9).** Anything the platform knows about is a row in the
   master, queried everywhere — never a hardcoded list. So the indices are refdata instruments, not a
   bespoke constant.
2. **A spot index is not tradable.** You cannot buy "the S&P 500 spot"; the tradable expression is its
   future or an ETF. Presenting spot indices as tradable would be a finance-correctness error and would
   let the fusion engine place orders it can never fill. So indices are **context/signal only**.

## Decision

Add a **spot-index market-trend feed** as first-class reference data, marked live but gated out of every
trade path.

- **New asset class `INDEX`** (`common-domain`). Classifies spot indices distinctly from their futures.
- **Migration `V49__world_indices.sql`** seeds the majors with `display_name`, `currency`, a `region`
  attribute (US / EU / ASIA), and **Yahoo symbology** (`^GSPC`, `^GDAXI`, `^N225`, …). No `adv_usd` /
  `spread_bps` — those are execution dials and an index never executes. Region-diverse: US (S&P 500,
  Nasdaq Composite, Dow), EU (FTSE 100, DAX, CAC 40, Euro Stoxx 50), Asia (Nikkei 225, Hang Seng, Shanghai
  Composite, KOSPI, ASX 200).
- **Marks for free.** The live path already Yahoo-polls every `yahoo`-symbology name (ADR-0056), so the
  indices get real delayed marks with **no market-data code change** — they flow through `md.marks` into
  `MarkState` like any price-quoted name.
- **Non-tradable gate, defense in depth.** `INDEX` is:
  - **never ticked in sim** — `effectiveSimInstruments()` already admits only EQUITY/FX/FUTURE;
  - **vetoed at the executor** — `FusionExecutor` refuses an `INDEX` instrument outright, the single
    chokepoint every order funnels through. This is the hard guarantee, and it holds **even with
    `require-backtest-support=false`** (exploration mode), where the ADR-0049 OOS veto is off — the class
    gate does not depend on it;
  - **skipped by the cross-sectional reversion sensor** — `INDEX` is peer of nothing, so no reversion
    signal is formed on one (saves the wasted work; the executor veto is what actually stops the trade).
- **Trend panel.** A read-only `GET /api/indices` returns the indices grouped by region with last price,
  source, mark age, and a **session change %** (from `MarkHistory`, first→last observed this session —
  labeled as such, not misrepresented as the official day change). The landing page renders a compact
  US / EU / Asia strip, red/green by direction.

## Consequences

- **Intended:** a cross-region market-trend read at a glance, sourced from real (delayed) marks, with the
  indices living in the master so they are queried everywhere and survive the discovery/cleanup machinery
  untouched (they are core, `source != discovered`).
- **Finance-correct:** spot indices never trade; their futures (ES/NQ, and any added later) remain the
  tradable expression. Indices carry no positions, so risk/VaR/hedging never see them.
- **Signal, later:** an index-trend input to the fusion forecast (regime/breadth context) is a natural
  follow-up — deliberately NOT wired here so this change is display-only and reversible. Tracked in the
  deferred register.
- **Honest scope:** the panel's change % is the **session** move (first mark of the session → latest), not
  the official previous-close day change — our Yahoo adapter captures price only. Upgrading to a true
  prev-close day change (capture `regularMarketPreviousClose`, or read `daily_close`) is a tracked
  follow-up. Labeled in the UI so it is not read as the day change.
- Validated: full app test suite green with the new class + gate; context loads.

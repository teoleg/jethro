# ADR-0125: Breadth, not more signal tuning, is the edge lever — widen the live universe across sectors

- **Status:** Implemented
- **Date:** 2026-07-29
- **Deciders:** Oleg
- **Tags:** trading, universe, refdata, edge

## Context

After the book came off dormant (ADR-0122) the loop reached a rigorous, well-evidenced verdict: **no
source has measured edge**, and it is not an execution problem — the desk turns over ~$106k to earn ~$0
because the signals do not predict returns on this universe. The one live candidate, reversion, is
positive and scaling correctly across horizons but only **t≈1.3** — not significant — and every other
source is negative at the long horizons.

The root cause is **breadth**, not the signal. On the live Alpaca feed the effective tradable universe was
~8 real-time US equities, almost all mega-cap tech (AAPL/MSFT/AMZN/GOOG/NVDA) plus JNJ/JPM — a tightly
correlated cluster (the futures/FX/rates in the master are priced only by a delayed background poll, the
ADR-0123 cohort, so they don't trade live). By **Grinold's Fundamental Law**, IR ≈ IC·√breadth: a
momentum/reversion signal spread across a handful of names that move together has almost no achievable
information ratio *no matter how good the signal is*. Re-weighting or re-tuning a source measured at t≈1.3
cannot manufacture significance; **more independent, less-correlated bets** is the lever the math points
at, and it is the one thing this book has never had on the live feed.

## Decision

Widen the live universe with **12 large/mega-cap US equities Alpaca prices in real time, spanning seven
low-correlation sectors** (refdata migration `V48__sector_breadth_equities.sql`), roughly tripling the
live equity cross-section from ~8 to ~20:

- **Energy** XOM, CVX · **Healthcare** UNH, PFE · **Financials** BAC · **Consumer staples** PG, KO, WMT ·
  **Industrials** CAT · **Consumer discretionary** HD, MCD · **Utilities** NEE.

Each carries the full attribute set the master requires (invariant 9 / CLAUDE.md): master row
(EQUITY/USD/×1), `sim`/`alpaca`/`yahoo`/`finnhub` symbology (the **alpaca** row is what makes it
*real-time* rather than a delayed poll), and stylized-but-stated `adv_usd`, `spread_bps`, `display_name`
with its sector. They auto-subscribe on next boot — `TradingCoreLifecycle.alpacaSymbolMap()` reads every
master instrument's alpaca symbol, and the fusion sources run on whatever prints; no config list gates it.

Energy, staples and utilities are materially less correlated with mega-cap tech than the incumbents are
with each other, so the SAME signals (trend, reversion, cross-sectional residual reversion) now have real
diversification to harvest — a larger, more independent cross-section is exactly what the residual-reversion
and momentum constructions need.

## Consequences

- **Intended:** breadth rises ~2.5×, and it is *diversifying* breadth (new sectors), so the desk finally
  has the cross-sectional dispersion its signals are built to exploit. Under vol-targeting, more
  uncorrelated names also *lowers* portfolio volatility per dollar, so exposure sizes comfortably inside
  the firm/book caps (hard pre-trade block, monitored) rather than concentrating.
- **Not alpha by itself:** breadth raises the *ceiling* on achievable IR; it does not create IC. If the
  signals still measure ~0 across a wide, diversified cross-section, that is a much stronger verdict that
  this signal family has no edge — and the next lever is a genuinely new predictor through the ADR-0049
  OOS gate, not more names. Either outcome is progress.
- **OOS validation pends daily bars.** Under exploration mode (ADR-0122, `require-backtest-support=false`)
  the new names trade on the live signals immediately, giving breadth now; once `scripts/fetch_bars.py`
  has run on the host, the OOS gate can validate them and exploration mode can be re-tightened.
- **Deferred:** real CUSIP/ISIN identifiers for the 12 names (display-only reference attributes, invariant
  2 — never data-path keys) are NOT invented here; tracked in the deferred register. Hedge betas (V32) for
  the new names are also a follow-up — until then they are unhedged at default, not mis-hedged.
- **Reversible:** the migration is additive and idempotent; the names can be blacklisted via
  `jethro.universe.dynamic.blacklist` without a schema change if any prove problematic.

# ADR-0025: Realistic simulated execution — spread/fee cost model, working-order matching, cancel/TIF

- **Status:** Proposed
- **Date:** 2026-07-14
- **Deciders:** Oleg
- **Tags:** order, execution, finance-math

## Context

Simulated execution (ADR-0019) fills MARKET orders at the last mark, full quantity, zero
cost. Real trading crosses a bid/ask spread and pays fees. The backtest engine already
charges `costBps` per fill, so the platform's two P&L sources disagree by construction:
the gate that authorizes bounded autonomy ("backtest-supported", ADR-0022) is measured
with costs, while the live sim P&L it authorizes is measured without them. The distortion
is not small — a 131-share AAPL round-trip that lost $5.93 in the sim would lose ~$15
with a 4bp spread — and it teaches every strategy that churn is free.

Separately, the order lifecycle is incomplete: a LIMIT order that isn't marketable stays
ROUTED forever (nothing re-checks working orders when new marks arrive), there is no
cancel, and no time-in-force. Doing nothing means P&L stays systematically inflated and
optimizing against the sim optimizes for overtrading.

## Decision

We will make simulated execution price-realistic and lifecycle-complete, in the order module:

1. **Cost model at the fill boundary.** `fillPrice = mark ± halfSpread`, where the spread
   (bps of price) and a fee (bps of notional, embedded in the fill price for v1) are
   configured per asset class under `jethro.execution.*`. Defaults (dev estimates, stated
   as such): EQUITY 5bp spread + 1bp fee; FUTURE 1bp + 0.2bp; FX 1bp + 0; SWAP 0.4bp
   (rate-quote adjusted). Exact decimals end to end (invariant 1). The backtest's
   `costBps` defaults are derived from the same config so both P&L sources agree.
2. **Working-order matching.** On every new mark for an instrument, ROUTED LIMIT orders on
   that instrument are re-tried through the same executor; fills flow the normal path.
3. **Cancel + TIF.** A cancel endpoint (`CANCELLED` is terminal); TIF `GTC` (default) and
   `IOC` (unmarketable → immediate `CANCELLED`) now; `DAY` when the session calendar
   exists (ADR-0026 follow-up).

## Alternatives considered

**A full matching engine / synthetic order book.** Rejected: enormous surface, and with no
real counterparty flow the book would be as synthetic as a spread model — cost without
added truth for the strategies we run.

**Market-impact models (e.g. Almgren–Chriss).** Deferred: impact matters when order size is
material vs. ADV; the sim has no volume model yet. Revisit with the liquidity work
(ADR-0026 follow-up), trigger: any strategy sized above ~1% of modeled ADV.

**Costs only in post-trade analytics (TCA), fills stay free.** Rejected: leaves the
incentive distortion in place — strategies and autonomy gates act on the inflated number.

## Consequences

- Positive: every P&L number means what it claims; churn shows its true cost; backtest and
  live sim measure the same economics; the order lifecycle (working orders, cancel, TIF)
  matches how real venues behave.
- Negative: all sim P&L drops (dashboards look worse — that is the point); configured
  spreads are estimates, not live quotes, and per-class constants miss per-name variation.
- Follow-ups: bid/ask emitted by the market-data pipeline (per-name spreads); fee as a
  separate cash line in the ledger rather than price-embedded; market impact + ADV.

## Implementation note — bid/ask through the market-data pipeline (2026-07-14)

The first follow-up landed: quotes are now first-class through the whole path.
`MarketDataListener.onQuote` (default no-op — adapters/listeners without quote data need no
change) → the correlated sim synthesizes top-of-book around every emitted mid (`Quotes`,
scaled-long centi-bps so SWAP's 0.4bp is the exact integer 40; the SAME per-class spreads the
execution model charges, so quoted touch ≡ synthetic touch by construction — worked: mid 190,
5bp → 189.9525 × 190.0475) → `QuoteCache` conflates last-value on the feed thread (quotes are
context, not archived ticks; one writer per structure) → `MarkEvent` gained optional nullable
`bid`/`ask` decimals (added with defaults — backward-compatible, invariant 4) → the order
module's `LastPriceCache` stores the quote and `SimulatedExecutor` fills at the QUOTED touch
when present (BUY crosses to the ask, SELL hits the bid; LIMIT marketability against the real
ask/bid), keeping the synthetic mid±half-spread only as the fallback for feeds without quote
data (Yahoo/Finnhub trades). The markets page shows bid/ask in the tile tooltip. This is the
foundation for per-name spreads (calibrate `QuoteSpec` per instrument instead of per class)
and TCA vs arrival (tracked).

## Implementation note — ADV + square-root market impact + participation cap (2026-07-14)

The third follow-up landed. Reference data now carries `adv_usd` per instrument (V19 —
stylized but realistic liquid-market magnitudes: mega-caps $5–15B/day, ES $250B, Treasury
futures $40–190B, FX majors deeper still). MARKET fills additionally pay the standard
empirical **square-root impact law**: impact fraction = σ_daily × √(orderNotional / ADV),
with σ the measured EWMA daily vol (the vol-targeting/VaR history) and coefficient Y=1
(the conventional order-of-magnitude choice, stated not fitted). Worked: 500 shares @ 200
against $1B ADV at σ=2%/day → participation 10⁻⁴ → impact = 0.02 × 0.01 = 2bp; 4× the size
pays only 2× the impact — the concavity that makes slicing orders rational. Impact needs
ADV AND measured vol AND a price quote; anything missing → spread+fee only (unmodelled,
disclosed, never guessed — swaps are left unmodelled in v1: a $1M lot is negligible D2D
participation). A **participation cap** (`jethro.execution.max-adv-participation`, default
2% of ADV) REJECTS outsized orders pre-route with the reason — the square-root law is
calibrated for small participations, and no desk slams 2% of ADV as one market order.
Remaining, tracked: per-tick sim volume printing (nothing consumes tick volume until a
VWAP/TCA benchmark needs it — building it now would be decoration); fee as a separate cash
line; TCA vs arrival (#12).

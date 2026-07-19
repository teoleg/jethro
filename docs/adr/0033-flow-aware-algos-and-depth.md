# ADR-0033: Flow-aware algorithms and synthesized order-book depth

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** Oleg
- **Tags:** algo, market-data, execution, sim

## Context

A code sweep found the alpha is blind to flow: the momentum and mean-reversion strategies consume a
**price window only** — no volume — and sizing is by volatility, not liquidity; order-book **depth
does not exist** (`onQuote` carries top-of-book prices with no sizes, and the only liquidity proxy
is the √-impact cost term). Now that real volume flows through the pipeline (ADR-0032) and news
drives correlated volume surges (ADR-0034), there is genuine flow for the algos to use — but nothing
consumes it. A breakout on thin volume is treated the same as one the whole market participated in,
and the strategy can size an order with no regard for how much the name actually trades.

Constraints: strategy changes must stay deterministic and flow through the SAME backtest/guardrail
harness (ADR-0018/0027); the backtest tape doesn't model volume yet, so a volume gate must be
**neutral by default** there (never silently change backtest results); no real L2 feed exists, so
any depth is synthesized and must be labelled; money stays exact/scaled-long (invariant 1).

## Decision

We will make the algorithms **flow-aware** and carry **synthesized depth** end to end:

- **Volume confirmation (signals).** `Strategy.Observation` gains a `relativeVolume` (recent ÷
  baseline, from `VolumeStats`; **default 1.0 = neutral**). A signal only fires when the move is
  confirmed by participation (`relativeVolume ≥ volumeConfirmMin`) — a breakout on thin volume is
  discarded. Neutral default ⇒ the OOS backtest (no volume) is unchanged; only the live path gates.
- **Liquidity-aware sizing.** The strategy caps an order's notional at a fraction of the instrument's
  **live measured ADV** (`MeasuredAdvSource`, ADR-0032), below the execution participation cap so a
  sized order passes — sizing now respects how much the name actually trades, not just its vol.
- **Synthesized depth (next slice).** `onQuote` gains bid/ask **sizes**, synthesized by the sim from
  ADV; a `MarketDepth` view carries them; sizing and a depth-aware fill nuance consume them.

## Alternatives considered

**Volume as a sizing input only, leave signals price-only.** Half the gap — the point the review
raised is that *signals* ignore flow. Rejected as the whole answer; sizing is one half, confirmation
the other.

**A real L2 order-book feed.** The honest way to model depth, but no free L2 source exists and it is
a large build; synthesized depth-at-touch from real volume is enough for liquidity-aware sizing.
Deferred behind a real depth feed / real-money routing.

**Gate the backtest on volume too.** More consistent live-vs-backtest, but the sim tape the harness
replays has no calibrated volume, so it would gate on a fiction. Rejected until the backtest tape
carries volume; the neutral default keeps the harness honest meanwhile (a stated asymmetry).

## Consequences

- **Positive:** the algos finally use the flow that now exists — no chasing unconfirmed moves,
  orders sized to real liquidity; a base for a genuine volume-confirmed edge once G5's news surges
  and G4's depth land.
- **Negative:** a **live-vs-backtest asymmetry** (live gates on volume, the backtest doesn't) — must
  be disclosed and revisited when the backtest tape carries volume; synthesized depth is a model,
  not a book, and must read as such; another dial (`volumeConfirmMin`) to calibrate — too high mutes
  the strategy, so it ships conservative and measured on the strategy-activity view.
- **Follow-ups:** a full L2 depth-walk fill model (behind a real depth feed); volume as a feature in
  the hypothesis/AI layer; once the backtest tape has volume, gate it there too. Depends on ADR-0032
  (volume/ADV), ADR-0025 (execution), ADR-0018/0027 (strategy harness).

## Implementation status (2026-07-18) — built

- **G3 flow-aware algos.** `Strategy.Observation.relativeVolume` (default 1.0 neutral) gates momentum
  + mean-reversion on participation; the strategy caps order notional at a fraction of live measured
  ADV. Backtest unchanged (neutral default) — the live-vs-backtest asymmetry is disclosed.
- **G4 synthesized depth.** `MarketDataListener.onQuote` gains bid/ask **sizes** (backward-compatible
  default); the sim adapters synthesize depth-at-touch from the trade volume (≈5 ticks of size);
  `QuoteCache` carries it; `/api/depth` + the sim page surface the top-of-book book. **Fill-side
  liquidity stays the ADR-0025 √-impact model** (finite liquidity already priced) — a full L2
  depth-walk is deferred to a real depth feed, so we don't double-count.

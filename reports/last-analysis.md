The desk was being credited with an hour of return against one round trip while turning its book over every 43 seconds; the holding period is now derived from the horizon its edge is actually measured over, and a cut trades in full while adding is slowed (ADR-0080).

## Situation (live, in words)

**Money.** Total PnL is **unchanged** run-over-run — identical to last run, and barely moved across the
last three. All of it is realized: the book holds no position, so there is no mark-to-market inside the
number. The book is not bleeding this window because nothing traded at all.

**Risk.** Gross and net exposure are both **zero**. VaR reports "no positions", the firm drawdown
breaker is not halted, and we are nowhere near it. There is no live danger state.

**Cause.** Last cycle's change (ADR-0079, the portfolio diversification multiplier) scored ⚠️ MIXED on a
move of exactly zero. That is **unmeasured, not refuted** — a sizing correction on a book that never
traded can only score zero. Nothing to revert.

**Danger.** Not the usual one: we are not bleeding *and* adding exposure. The opposite — the desk is
**frozen**. The edge gate is reduce-only (no source's measured expectancy clears its measured
round-trip cost with significance) and the book is flat, so the reduce-only projection returns zero on
every name: all 23 fusion targets carry `deltaQty = 0`. That state sustains itself until the gate opens
on its own evidence.

**Order post-mortem.** The last window in which anything traded was 20:08–20:21. It was MSFT: eleven
legs in thirteen minutes — BUY 51/66/77, SELL 26/50/78/49, BUY 35/26/2/4, each paired with a matching
ES hedge clip — and the buys and sells net to **exactly zero**. The position ended where it started.
Every leg paid the spread and the fee. That is the loss mechanism, on tape.

**Memory.** The findings log has flagged the measurement horizon as the next lever for two cycles
running, deliberately deferred behind the cross-name sizing fix. That fix shipped last cycle, so it is
unblocked — and this cycle's evidence arrives at the same place from the cost side rather than the
sampling side.

**Change vs market.** Flat at both endpoints, zero orders in the window. Attribution is therefore exact
and empty: **0% market, 0% change.** Nothing here is inferred from a noisy delta.

## Diagnosis

Two numbers describe the same trade and have never agreed. Sources are graded on a **3600-second**
forward return (`jethro.signals.horizon-seconds`), and that average is what the edge gate tests against
the desk's measured **round-trip** execution cost. But the trading policy sat at `adjustment-rate = 0.5`
on a 30-second cycle, so exposure e-folded toward its target with a time constant of
`-30 / ln 0.5 = 43 seconds`. The desk held a view for 43 seconds while being graded on an hour of it,
and so paid on the order of 83 round trips over the single hour of return it was credited for. The gate
is not too lenient; it is **dimensionally wrong**, and it errs one way only. The MSFT tape is precisely
what a 43-second time constant predicts: a round trip every ~70 seconds, netting to flat, paying the
spread every leg.

## Change

The trading rate stops being a dial and becomes a derived identity — `a = 1 - exp(-cycle/horizon)`, the
unique fraction whose exposure time constant equals the horizon the edge was measured over, read from
the same property the telemetry itself is configured by so the two cannot drift apart.
`jethro.fusion.adjustment-rate=0` now means *derive*. And the rate applies **only to the risk-increasing
part** of a delta: the walk to flat trades in full, in one cycle, where it used to be an asymptotic
seven-cycle grind that paid a round trip on every step down and left a one-unit residual. Cut fast, add
slow — the asymmetry the owner's thesis is built on, moved into the sizing step.

**This will score as no material move next cycle, and that is expected, not an accident.** The gate is
shut and the book is flat, so nothing trades either way. It is shipped now deliberately: the gate
re-opens by itself the moment a source earns its cost, and a 43-second holding period must not be in
place when it does — the same order-of-operations lesson the last two cycles recorded. Full suite green;
ADR-0080 Proposed.

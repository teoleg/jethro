# ADR-0106: A round trip that pays the desk is not a measurement of cost

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Oleg
- **Tags:** backend, trading, execution, risk

## Context

ADR-0075 tests every name against **its own** measured round-trip execution cost before the desk may
put risk on it, and states the desk-wide verdict at the **cheapest** round trip in that map — the
per-name question asked at its most favourable name, which is sound because the test is monotone in
cost. ADR-0099 fills the gap for a name the desk has never filled by charging it the round trip its own
live quote implies, floored at the desk blend. ADR-0101 then derives each name's no-trade buffer from
the same figure: the Carver/Grinold cost-to-edge width `2C/μ`.

That per-name cost `C` is a realised **implementation shortfall against the arrival mark**. It therefore
contains the price *drift* over the fill window as well as the spread the desk actually crossed. Under
ADR-0084 the desk **posts** to enter and crosses only to exit — and a passive order is filled precisely
when the price comes to it, so the drift term at entry is systematically favourable. On a name where
that favourable drift exceeds the exit's half-spread, the arithmetic returns a round trip that is
**negative**: the measurement says the desk was paid to enter and leave a position.

It cannot be. Entering and leaving crosses the touch or waits for it, and the fee is charged on both
legs. A negative reading is drift that belongs to the *signal* being booked as though it belonged to
*execution* — the same quantity counted twice, once as expectancy and once as a cost rebate.

Live on the running desk this is not hypothetical, and it does two concrete kinds of damage:

- `NQ` measured **−0.25445 bps**. Because the desk-wide verdict is taken at the cheapest entry in the
  map, that single reading became the desk's cost, and `netEdgeBps = avgReturnBps − cheapest` came out
  **above the expectancy that was actually measured** for every source at once — `reversion` was graded
  at `8.683` against a measured `8.429`, `trend` at `−5.977` against a measured `−6.232`. The gate that
  exists to make the desk pay for its trading was handing it 0.25 bps of edge no signal produced.
- ADR-0101's width is guarded by `if (!(costBps > 0)) → Carver floor`, so the same name collapses to the
  narrowest no-trade buffer on the desk (0.10 against the `2C/μ ≈ 0.32` its blend implies). The desk
  re-trades its least honestly-priced name the most often — exactly backwards.

The prior reasoning is recorded in `EdgeGate`'s own javadoc: *"a measured zero (or a negative, i.e.
price improvement) is a real reading the gate must use, not an absent one."* That is right about a
**one-way** slippage — price improvement on a single fill is real and repeatable. It is wrong about a
**round trip**, which is a two-legged quantity whose sign is fixed by construction.

## Decision

We will treat a **non-positive measured round trip as no measurement at all**, and charge that name
through the ADR-0099 fallback it would take if it had never filled: `max(deskBlend, quotedRoundTrip)`,
or the desk blend where it has no two-sided quote.

The rule lives in one place — `QuotedSpreadCost.withQuotedFallback`, the single point where the
ADR-0075 cost map is assembled before it reaches `EdgeGate`, `PositionBuffer` and the horizon ladder. A
positive measurement is still returned untouched: a fill always beats a quote, and ADR-0084's genuine
saving (AAPL measures `0.889` against a `2.00` quoted touch — about one half-spread, precisely what
posting to enter should save) is preserved exactly.

Nothing is invented. The replacement value is the name's own live quote or the desk's own blend,
whichever is larger — both already measured, both already the established treatment for a name with no
cost reading of its own.

**Worked example (the live figures).** `NQ` measured `−0.25445`; its quote is
`20000 × (19786.142008 − 19785.884790) / (19786.142008 + 19785.884790) = 0.12999… bps`; the desk blend
is `1.3449575609756097`. `max(1.3449575609756097, 0.12999…) = 1.3449575609756097`, so `NQ` is charged
the blend. The desk-wide cheapest moves from `NQ`'s impossible `−0.25445` to `ES`'s real `0.41852998`,
and `reversion`'s net edge from `8.68305925` to `8.42860925 − 0.41852998 = 8.01007927` — a t-statistic
of `8.01007927 / 0.87098112 = 9.198` against a hurdle of `2.0`. **No source changes verdict**: the one
source that passed still passes with room, and the four that failed still fail. `NQ`'s ADR-0101 buffer
widens from the `0.10` Carver floor to `2 × 1.3449575609756097 / 8.42860925 = 0.3192`.

## Alternatives considered

**Floor the reading at zero rather than discarding it.** Simpler, but it asserts that this name's round
trip is free, which is a number the desk did not measure and which is false — the fee alone is not zero.
It would also leave the desk-wide minimum at zero and keep `netEdgeBps = avgReturnBps`, i.e. a gate that
still charges nothing. Discarding says the honest thing: this reading tells us nothing about this name's
cost, so use the estimate we use when we know nothing.

**Take the desk-wide verdict at the *median* rather than the *cheapest* round trip.** This would blunt
the single-name contamination without touching the sign question. Rejected: it changes the meaning of
the desk-wide test (ADR-0075's minimum answers "is there any edge worth paying for *anywhere*", which is
the right question and is monotone in cost), and it would still leave the contaminated figure driving
that name's own ADR-0101 buffer.

**Fix the estimator instead — measure cost against the decision mark, or decompose shortfall into spread
and drift.** This is the *correct* long-term answer and would remove the bias on every name rather than
only where it crosses zero. It is a change to `ExecutionQualityRepository` and the TCA schema, touches
the published cost series the whole desk is calibrated against, and is not reversible in one commit.
Recorded as a follow-up; the sign guard is the honest, provable, one-commit floor under it.

**Charge every name the wider of measured and quoted.** This would negate ADR-0084's measured win —
posting to enter genuinely saves about a half-spread and the data confirm it — and so would over-charge
the desk into inactivity on names it trades well. Rejected.

## Consequences

- **Positive.** The gate can no longer credit the desk with more edge than it measured: `netEdgeBps ≤
  avgReturnBps` becomes structural rather than incidental. The name with the least trustworthy cost
  reading stops getting the desk's tightest re-trade buffer.
- **Strictly one-way, and asserted as tests.** Every per-name hurdle is greater than or equal to what it
  is today, and `EdgeGate`'s test is monotone in cost, so this change can only ever **remove** a trade,
  never add one. The desk-wide cheapest can only rise. Exposure therefore cannot grow because of it.
- **Negative / risks accepted.** The desk pays a slightly higher hurdle on any name whose favourable
  entry drift is persistent and real; if such an edge exists, this forgoes it. That is the conservative
  side of the trade, and the right one while the estimator cannot separate drift from spread. On the
  live cross-section no source's verdict changes, so the immediate effect is confined to `NQ`'s buffer
  and to the honesty of the published `netEdgeBps`.
- **No new dial and no new number.** The threshold is the sign of a cost — arithmetic, not appetite.
- **Feed-agnostic (invariant 9).** The rule reads only the desk's own measured series and the running
  feed's own quote; there is no level, no class assumption and no sim special-case.
- **Follow-ups.** (1) Decompose the TCA shortfall into spread and drift so the bias is removed on every
  name rather than only where it changes sign — the reading above suggests the *whole* series is biased
  low, not just `NQ`, which would make every cost-keyed control on this desk too permissive.
  (2) `turnover_cost_by_name` in the loop report has been erroring for several cycles (`column "qty"
  does not exist`); it is the aggregate that would grade exactly this decision. Both in the deferred
  register.

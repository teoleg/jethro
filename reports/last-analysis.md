The edge gate was charging every name the desk's *average* trading cost, so an edge that survives in the cheapest names was being refused everywhere — each name is now tested against its own measured round trip, on both sides of the gate (ADR-0075).

## Situation (live endpoints, read first)

**Money — flat, not bleeding.** Total PnL is unchanged run-over-run and unchanged across the last
three; every move in that span sits inside the scorer's noise deadband. `stale` and `underwater` are
both set and we are off the +1%-per-3-iterations target. The cause is arithmetic, not a loss: with zero
exposure, PnL cannot move.

**Risk — zero, no danger state.** Gross and net exposure are both flat at zero, VaR reports "no
positions", the firm drawdown breaker is untripped, the hedge axis reads FLAT. Nothing to de-risk,
nothing to cut, so the danger-state override does not apply.

**Cause — last cycle's change was inert by construction, as it said of itself.** ADR-0074 (credibility
counts the sample its estimate was made from) scored ⚠️ MIXED "no material change". It rotates
conviction *between* sources and provably cannot scale exposure, so with a flat book there was nothing
for it to act on. The window's orders were the last of the ADR-0065 flattening tail — single-share ALPHA
closes with matching fractional HEDGE ES trims under a reduce-only gate. **No trigger opened a position
this window**, so 100% of the (nil) PnL and exposure move is mark drift plus prior policy: market and
prior policy, none of it attributable to my last change in either direction.

**The genuinely new fact this cycle: `reversion` has a positive measured expectancy.** It is the first
source ever to show one — 23 resolved observations, seven wins to two losses, a positive mean — with 23
more observations open, which will carry it past the 30-observation minimum inside the next window or
two. Every other source is significantly negative (trend over ninety-odd observations, momentum, social)
and cannot be rescued by anything. So for the first time there is a candidate the gate could plausibly
judge, and the question of *what the gate does when it judges it* stops being academic.

## Diagnosis — the hurdle is set by the cost of names the trade was never going to be put on in

The gate compares each source's measured expectancy against **one blended round-trip cost** for the
whole desk. That blend is a fiction: the desk's own TCA spans two orders of magnitude, from the index
future at a fraction of a basis point to the widest-spread equity in double digits. ADR-0072 already
established the principle — cost is a per-name property — and fixed the half where the blend
*under-charges* expensive names, adding a veto so a name whose round trip exceeds the passing edge stays
reduce-only. It left the other half in place. The blend equally **over-charges the cheap names**: a
source whose edge comfortably survives the index future's round trip is refused everywhere, because the
average name costs a large multiple of it.

`reversion`'s measured expectancy is exactly in that band — below the blended hurdle, above what several
of the cheapest names actually cost. That is not a marginal accounting point; it is the difference
between a permanently flat book and a book that puts its first risk on where the edge demonstrably
survives what trading it demonstrably costs. The lesson was already written down when ADR-0072 shipped —
*"a gate that compares edge to cost must compare them at the granularity cost is incurred"* — and then
applied to only one of the gate's two comparisons.

There was a second inconsistency worth removing in the same breath: the desk-wide test is a t-test, the
ADR-0072 per-name veto was a raw comparison of means. So a name whose round trip ate almost all of the
measured edge passed the per-name bar while a name with a larger surplus could fail the desk-wide one.

## Change (ADR-0075)

One rule, at the granularity cost is incurred, on both sides: a name may take risk on when some source's
measured expectancy survives **that name's own** measured round trip with the same significance hurdle
and minimum sample as before. The desk-wide verdict is that same test at the cheapest round trip the
desk can actually pay. A name that has never filled is charged the desk's **measured blend** — never an
invented cost — which is bit-for-bit the bar it faced before.

The change is provably monotone and I want that on the record, because loosening a gate that keeps a
losing book flat is exactly the mistake this loop has already paid for once. For any name costing more
than the blend the new rule is **strictly tighter** than the veto it replaces (significance, not a raw
comparison of means); for an unmeasured name it is identical; only a name whose own measured cost is
below the blend can gain permission. And a measured-**negative** source clears nothing at any cost — its
surplus is negative even at a zero round trip — so this cannot hand the book to trend, momentum or
social. That is the material difference from the continuous risk-appetite gate that scored ❌ BAD and was
reverted: that one let below-hurdle sources size at reduced conviction; this keeps the binary switch and
the hurdle untouched and changes only which cost the expectancy is measured against. No new dial, no
retuned dial, and the deterministic floor — guardrail, breaker, invariant-7 gates — is untouched.

**What to expect, honestly.** This may still score ⚠️ "no material change": `reversion` needs to clear
the minimum-sample bar before it can speak, and it is a few observations short. But unlike the last four
cycles this one is not inert by construction — it is the rule that decides whether the desk's first
positive-expectancy source is allowed to trade, and it will bind the moment that source resolves its
open calls. If it opens the book and the book loses money, the scorer will say so and revert it, which
is the correct test.

## Flagged, not acted on

The blended cost remains a documented lower bound on true round-trip cost — it omits the cash
commission, which the TCA table stores without the multiplier needed to express it in bps. That
understatement is unchanged by this change but now applies per name. Separately, the wide-spread
mega-cap whose one-way slippage looks like a provisional refdata spread rather than a measured property
is still unrepaired; I am still declining to touch a cost input in the same change that reads the cost
hurdle.

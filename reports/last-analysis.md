The fusion sizer divided cash-at-risk by price instead of price × contract multiplier, so the one name the edge gate currently permits — ES — was queued to open with 50× the intended exposure; fixed to size and round in each instrument's own contract terms (ADR-0078).

## Situation (live, in words)

**Money.** Total PnL is **lower than the last reading** (down ~$31) but the last reading was taken with
a position on. The honest comparison is flat-to-flat: the book was flat at the start of this window and
is flat now, and across those three runs total PnL is **up ~$42**. Growth is on target (~8% vs the 1%
per-3-iteration bar). Every dollar of it is realized; there is no open PnL to give back.

**Risk.** Gross and net exposure are both **zero**. Nothing is on. Nowhere near the drawdown breaker,
and no exposure to cut — the opposite problem: no forward earning power.

**Cause, and change-vs-market.** This window is unusually clean to attribute: the book was flat at both
endpoints, so there is **no mark-drift component at all** — 100% of the move is the trading, 0% is the
market. Only MSFT (with its ES hedge leg) traded, ~33 fills between 20:08 and 20:21, and that round trip
netted **positive** after fees. So the trading was not the problem. What made it possible is the
interesting part: `reversion` had exactly **one** resolved emission cohort, so under ADR-0077 its
standard error read zero and the gate could not certify it; the moment the second cohort landed a real
standard error appeared and the gate re-shut on every name except ES. Last cycle's change (ADR-0077,
scored ⚠️ MIXED) is working as designed — it is not the culprit, and I am not reverting it.

**Danger.** Not bleeding-with-rising-exposure. But there *was* a live danger, and it is not the one the
flags were pointing at.

## What I found instead

The current fusion target book carries `ES targetQty −8.799423` with a queued delta of `−4.399712`
contracts. At ES ≈ 5451 with a **contract multiplier of 50**, that delta is roughly **$1.2M of gross
exposure** — against an intended `unit-notional` of $50,000, on a firm whose entire recorded exposure
history peaks near $415k. `TargetPlanner.targetQuantity` computed `unitNotional / price`, but
`unitNotional` is *cash*, so it has to be divided by the money value of one **unit** of the instrument —
`price × contractMultiplier`. `PositionRisk` values the resulting position as `qty · mark · multiplier`,
`Positions` settles PnL the same way, and the ADR-0039 hedge advisor has always divided by
`price × multiplier` on this exact instrument. The fusion sizer was the only place that didn't, so every
non-equity target carried **multiplier × the cash asked for**: 50× on ES, 20× on NQ, 1,000× on the note
futures, 45,000–80,000× on the swaps. Equities and FX (multiplier 1) were correct and are untouched.

This was about to fire rather than sitting dormant, and the two mechanisms compose in the worst
direction: the ADR-0075 gate admits a name only when a source's edge clears **that name's own** measured
round trip, and ES — the cheapest thing the desk trades — is currently the *only* name that clears it.
Cheap-to-trade and large-multiplier are the same property of an index future, so the gate systematically
selects precisely the contracts where the sizing error is biggest.

## The change (ADR-0078, Proposed)

The fusion order path now consults the instrument master end to end: `targetQuantity` divides by
`price × multiplier` so that `|qty| × price × multiplier` equals the cash asked for in every asset class;
a name with no contract spec plans **flat** rather than being sized as if it were a share (no invented
number, invariant 7); and `tradableQuantity` rounds toward zero in the instrument's own terms — whole
units at multiplier 1 (byte-identical for every equity and FX name) and at the order/fill quantity scale
for a contract, which is what the hedge advisor already submits on ES. Without that second half, fixing
the sizing alone would have silently made every future, bond and swap untradable, including the one name
the gate permits. No new dial, no minimum-notional number, deterministic floor untouched. Worked example
(equity / ES / ZF) is asserted as an exact-decimal test.

**What I expect.** Exposure on any futures/rates target falls by exactly its multiplier; equities and FX
are unchanged exactly. This is a correctness fix, not an alpha idea, so I am not forecasting a PnL jump
from it — the win is that the desk stops being one gate-opening away from putting on seven figures of
index exposure it never intended, and that the ADR-0075 cost arithmetic now applies to the position size
the desk actually means to hold.

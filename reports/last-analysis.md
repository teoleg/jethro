Fixed the fusion's source-trust weighting: it was blind to *how badly* a source was losing, so it gave a measured loser the same conviction as an unmeasured peer.

## Situation (live endpoints, read at the top of this cycle)

**Money — bleeding.** Total PnL has fallen every reading today: it sat at the post-reset residual, was
lower when the scorer measured last cycle's change, and is lower again now — a loss of a few tens of
dollars over about a quarter of an hour on a book of only two positions. The damage is almost entirely
*realised*, not marked: the realised leg dwarfs the unrealised one. That is the signature of churn, not
of an adverse market. Fees are already several dollars against a book measured in low tens of thousands.
Nowhere near the ≥1%-per-3-iterations growth target; the target is not merely missed, PnL is going the
wrong way.

**Risk — spiked, now unwinding.** Gross exposure went from flat to tens of thousands after last cycle's
change opened the desk, and is already back down by roughly two thirds as the revert takes hold. Net is
small and long. Historical VaR is a low three-figure number against that gross, ES roughly triple it.
The firm drawdown breaker is **not** tripped and is not close — the losses are small in absolute terms;
the problem is their sign and their cause, not their size.

**Cause — my own last change, and it is already reverted.** Last cycle I replaced ADR-0064's binary
edge gate with a continuous risk appetite that sized the book at half notional when evidence was
*absent*. The scorer marked it ❌ BAD (exposure grew, PnL did not) and auto-reverted it. The live
numbers say the verdict was right, and they say *why* I was wrong: I read the desk's state as "no
evidence yet" because no source had reached the gate's 30-observation minimum. But the evidence was
there and it was negative — the trend source has 23 resolved observations at a mean return well below
zero, roughly two and a half standard errors below, and every other source is negative too. I sized on
the absence of proof while the telemetry was quietly reporting proof of harm. The revert restored the
reduce-only gate, which is why exposure is falling; the book is being worked flat and the residual loss
is the cost of that round trip.

**Danger — no.** We were bleeding *and* adding exposure an hour ago, which is the state that overrides
everything; the revert has already de-risked it and gross is falling toward flat under a gate that
cannot re-open. So the de-risking move is done, and this cycle is free to fix a cause rather than a
symptom.

## What I changed and why

Three independent measurements now agree that this desk's momentum/trend family is anti-predictive on
this stream: the live signal telemetry (all three routed sources negative, the only well-sampled one
significantly so), the historical observation ledger across both feed modes, and the strategy selector's
own walk-forward backtest, which reports momentum losing on twelve of seventeen names and picks
mean-reversion wherever it picks anything. The regime detector independently says CHOP, with every
name's efficiency ratio below a half. That is a coherent picture, not a one-window blip.

Against that, the fusion layer reports its per-source weights as exactly equal — 1.0 for every source.
That is not a coincidence, it is a defect, and I could reproduce it from the code by hand. Source trust
was computed as `max(0, 2·hitRate − 1)`: every source at or below a coin flip floors to zero, the pooled
mean goes to zero, and the method takes its "no measured edge anywhere" branch and returns 1.0 for
everyone. The whole desk being underwater is exactly the case the statistic cannot see. Worse, hit rate
is the wrong quantity for a trend follower in the first place — it is *designed* to be right under half
the time and paid by asymmetry, so hit rate reads its intended shape as failure and cannot distinguish
it from real failure.

So I replaced the statistic and kept everything around it: source trust is now Φ of the source's own
sample t-statistic of expectancy — its measured mean return against its own standard error — which folds
magnitude, dispersion and sample size into one bounded number with no flat region. Credibility shrinkage,
the pooled prior, the min-sample floor and the MIN/MAX bound are untouched, and no new dial is
introduced: Φ(0) is one half exactly, so a cold start still reads equal by symmetry rather than by a
fallback branch. It is bounded in (0,1), so a measured-bad source is quietened toward the floor, never
inverted into a contrarian bet on its own failure — inverting a losing signal is the classic overfit and
I am deliberately not doing it. ADR-0067 records the decision; the deferred register regained two
findings the revert had dropped.

One property matters for how this should be judged, and it is the reason I chose this lever after last
cycle's reversal: the combiner normalises by the sum of the weights, and the floor keeps every source
active, so re-weighting **rotates conviction between sources but cannot resize the book**. Identical
forecasts produce an identical target position under any weights — there is a test asserting exactly
that. Unlike last cycle's change, this one cannot grow exposure. It is not a retry of the reverted
idea: that one scaled the notional through the gate, this one changes only whose view counts.

Honest expectation for the next scoring: the gate is closed and the book is winding to flat, so the
vector will likely read as little or no change and this will score MIXED. That is the correct outcome
for a change that fixes how the desk will decide once it has something worth deciding on, and I would
rather bank a real correction than manufacture a trade. The standing problem remains that no source has
positive measured expectancy, so the gate has nothing legitimate to open on — the next lever I intend is
a source built for the regime the detector is actually reporting, measured honestly before it is
allowed to size anything.

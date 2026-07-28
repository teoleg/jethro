Fixed a one-sample off-by-one that stopped the warm-restart seed from EVER arming the risk cut or the concentration control: both count returns, the seed hands them that many prices, and N prices are only N−1 returns (ADR-0117).

*Every figure below is read from the live endpoints, `logs/report.md` or the ledger; none is authored here
(invariant 7 / ADR-0016 — the scorer owns every number that gates money). The span-4 and span-2 examples are
arithmetic on the estimators' own counters, not measurements of this book.*

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `-$0.21`, **up** `+$0.42` on the last run. Over the last three runs the book went
from `$0.00` to `-$0.21` — but those three runs span the epoch boundary (the LIVE epoch opened flat), so the
"3-run" figure is a fresh-book artefact rather than a drawdown. The desk is not bleeding: it is barely on.

**2. Risk.** Gross `$759.72`, net `$84.47`, **falling** (`-$0.96` on the run). VaR95 `$8.23`, breaker
`halted: false` and nowhere near it. Two positions, total: short 1 AAPL and a `0.001136` ES hedge.

**3. Cause.** Last cycle's change (ADR-0116, the σ print gate) scored **⚠️ MIXED — "no material change
(within noise band)"**, which is what I predicted and is the right verdict on a book that held nothing for
the window. Since it went in the book opened its first two LIVE positions and PnL moved `+$0.42`. Neither
is attributable to it.

**4. Danger.** No. Not bleeding, exposure falling, breaker clear.

**Order post-mortem.** Two orders all day: `ALPHA AAPL SELL 1` at 13:40:44Z and `HEDGE ES BUY 0.001136` ten
seconds later — the hedge tracking the short it created. The AAPL leg is a **winner** (`+$1.28` unrealized);
the ES hedge leg is `-$1.44`. Both names fell by almost the same percentage, so a beta-1.25 hedge (AAPL's
`hedge_beta` in refdata, ADR-0040) necessarily lost slightly more than the short made. Neither trigger is
broken. **Change vs market: this is market, not code** — nothing I shipped opened, closed or resized either
leg, and one hour of two co-moving names is far too little to call the hedge ratio wrong. I am not touching
it on this evidence.

**Why the desk then stopped.** `mayIncrease: false`. The edge gate went **active** the moment that first
fill gave it a measured cost, and no source clears: `resolved` is 0–5 against `minSample 30`, trend's
measured expectancy is *negative*. It is correctly refusing to pay to trade an unproven signal, and it
self-heals — observations accrue from published forecasts, not from fills. I left it alone; forcing it open
would be exactly the overfitting the gate exists to prevent.

## What I found instead — the seed that could never finish

Reading the WARN log for what did **not** self-heal, two lines stand out because the store supplied
everything that was asked for and the answer was still "cold":

```
risk-cut σ sensor still cold for NQ after seeding 120 of 120 stored prices
fusion covariance still cold after seeding 120 synchronised snapshots of 4 name(s)
```

`StreamVolatility` and `StreamCovariance` are both denominated in **returns** — both gate on
`returns < span`, and both say in their own `update` that a series' first price "is a price, not yet a
return". `FusionLifecycle` seeds both by replaying `warmupSamples()` **points**. N points are N−1 returns,
so the ADR-0071 warm restart landed **exactly one return short — every time, for every name, at any depth of
history**. Not a data shortage: `seedPrices` caps the walk at the count it is handed, so more history could
never fix it. Live confirmation on the same page: `streamVolMeasuredNames: 1` against
`covarianceCoveredNames: 6`, and `riskCuts: []`.

One sample would normally be nothing. It is not nothing *since ADR-0113/0116*, which moved both estimators
onto the tape's clock: the missing return no longer arrives on the next 30 s cycle, it arrives on the next
genuine **print** — ~90 s on NQ, ~13 min on GBPUSD, ~20 min on ES (ADR-0114's measurements) — against a
process that redeploys every thirty minutes. The covariance is worse, needing the *pair* to print. So the
warm restart was handing over sensors still mute for much of each process life, which is the precise failure
ADR-0071 was written to eliminate, re-entered one sample wide. Both failures run the expensive way: a cold σ
means ADR-0086 makes no claim and a **losing position is not cut**; a cold covariance means a concentrated
book is not shrunk.

## The change

Each estimator now states its seed requirement in the unit the seed is counted in —
`StreamVolatility.warmupPrices()` and `StreamCovariance.warmupSnapshots()`, both `warmupSamples() + 1` — and
the two `FusionLifecycle` call sites ask for and log that number, so `n of n, still cold` can never again
mean this bug. `warmupSamples()` keeps its meaning and every existing caller is byte-identical; no
arithmetic, decay or gate moves. No dial and no number needing provenance: `+1` is the count of returns
derivable from a price series. Proposed ADR-0117 ships in the same commit.

**The honest cost.** This is *earlier* protection, not free protection — an armed risk cut can cut, so a
name whose σ is now measured at boot may be stopped out where it previously would have been left alone, and
that can lose money in a whipsaw. It also repairs neither a name genuinely short of history (AAPL at 99 of
120) nor one whose price never moves (the Treasury curve, variance exactly zero, correctly silent). With the
edge gate reduce-only I expect MIXED again; the thing to check next cycle is the WARN log — the σ and
covariance seeds should report *warmed* at boot instead of cold, and `streamVolMeasuredNames` should rise
off 1.

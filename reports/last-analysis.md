Stopped the desk diluting the one view it has evidence FOR with the one it has evidence AGAINST: the source-weight floor is gone, so `trend` is weighted by its own measurement instead of being held up at 2.84× it (ADR-0087).

## Situation (read from the live endpoints; every figure quoted, none computed here)

1. **Money.** Total PnL is **higher** than last run — `-787.09` against `-788.14`, **+$1.05** — and **+$38.97**
   across the last three runs, `pnl_growth_pct` **4.59%** against a **1%** target. `on_track`, not `stale`,
   still `underwater`. The book is **not** bleeding; this is the first genuinely healthy trend in the run.
2. **Risk.** Gross exposure **$130.67** (net the same), **down $1,472.39** since last run. Historical VaR95
   on it is **$1.99**; the breaker is not halted and is nowhere near. Exposure is falling while PnL rises —
   the good quadrant, and nothing to de-risk.
3. **Cause.** Last cycle's change (ADR-0086, the chandelier exit) scored **⚠️ MIXED** and was kept. The live
   numbers say it did exactly its job: gross **$6,495.71 → $130.69** with PnL **up $1.37** on the scored
   window. The MIXED came from the risk-adjusted note (PnL/$1 gross reads worse when a negative PnL is
   divided by a much smaller gross) — an artefact of the ratio's sign, not a loss. No culprit to name.
4. **Danger.** No. PnL up, exposure down 92%, one small ES position, breaker far away.
5. **Order post-mortem.** 55 fills today. **MACRO/ES `+$33.68` on `$2.36` of fees; HEDGE/ES `+$68.88`.**
   Both winners. The `-$889.65` in ALPHA is spread almost evenly across seven equity names that are all
   **flat now with zero unrealised** — realised churn from the 7/26 session (217 fills, `$158.45` of fees),
   sunk before this window and unmoved in it. No trigger fired a losing trade this window.
6. **Change vs market.** Every position but one ES line is flat with zero unrealised, so the market
   contributed ~nothing to the firm total this window: the move is essentially all the fusion desk's own
   realised trading. The three-run `+$38.97` cannot be split further from the numbers alone — I am not
   claiming it as attributable to any single change.

## Diagnosis — the mechanism

The desk is finally healthy, so the target is no longer "make it trade", it is **make the trades it does
place carry the view the evidence supports**. Reading the live weight vector against the live telemetry at
the ADR-0082-selected 900 s rung: `reversion` is decisively positive (hit rate 0.785 over 230 resolved
calls in 10 cohorts) and is the source the whole book rests on; `trend` is measured **significantly
negative at every rung of the ladder** — 230 to 500 resolved calls, 10 to 22 cohorts, negative at 3600 s,
900 s and 225 s alike. Its evidence-implied weight is **0.088**. `weights.min = 0.25` lifted it to **0.25**,
2.84× what its own measurement supports, and **no other source came near the clamp** — so the floor's entire
live effect was to over-trust the single worst-measured source on the desk. Since `trend` and `reversion`
are mirror readings of the same mark stream (hit rates 0.290 vs 0.785), that over-trust lands as a direct
drag: `trend` opposes `reversion` on **every one of the 12 largest planned names**, and on SAP it takes
13.9% off the conviction. The floor's stated reason — keep a decayed source contributing so the
diversification multiplier's source count is unchanged — was retired by **ADR-0076**, which sizes that
multiplier from weight *concentration*; and it is false anyway, because the shrunk ratio is strictly
positive on any finite sample.

## Change

ADR-0087: `jethro.fusion.weights.min` defaults to **0**, and Bühlmann credibility is the single continuous
thin-sample defence — the same argument, on the same class of construct, that ADR-0074 used to remove this
floor's twin. `max` = 3.0 is untouched, the losing source is **down-weighted, never inverted** (Φ is bounded
in (0,1) precisely to forbid that), and the statistic, shrinkage constant, edge gate, per-name cost test and
deterministic floor are all untouched. The live five-source vector and the SAP worked example are pinned as
an exact test — including that the diversification multiplier **falls** (1.05246 → 1.02042), so part of the
conviction increase is paid straight back as less scale-up.

Honest limitation, stated up front: conviction magnitude is what `TargetPlanner` sizes from, so **gross
exposure will rise** on the two names that clear the ADR-0075 cost test (ES, MSFT). That is the intended
direction — more of the measured-positive view, less of the measured-negative one — but it is an exposure
increase and will be scored as one. If the next verdict is ❌ BAD, what has been refuted is `reversion`'s
live edge, not this floor's redundancy, and the next lever should be the cost side, not the weights.

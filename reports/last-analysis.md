The book finally deployed broadly — and in doing so exposed the real blocker with no ambiguity left: the desk can GROW a name it already holds but cannot START one.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `recent_orders`,
`logs/report.md`, the scorer's output, or the repo source. None is authored here — invariant 7 / ADR-0016.)*

## No code change this cycle — the pending change is still under measurement

`scripts/score-change.py score` prints **`74a47adee still accumulating evidence (4/6 cycles) — held, not
scored this run`**, and `reports/.pending-baseline.json` is present. Under ADR-0116 a new change stacked on
a pending one destroys its evidence. So this run verifies, diagnoses, ranks and records; it ships no code.

## Situation — the four questions

1. **Money.** Total PnL **`-85.68734607`**, against `-45.96` since the last run and `-118.20` across the
   last three. By book: `HEDGE` **`+121.83844392`**, `ALPHA` **`-150.72628463`**, `MACRO`
   **`-56.79950536`** (all realized, carried from the 14:32Z sweep). `UNDERWATER`, off the growth target.
   The strategy book is losing while the hedge is winning — `/api/attribution` reads `strategyAlpha
   -207.52578999` against `hedgePnl 121.83844392`, with `totalFees 296.016031`.
2. **Risk.** Gross **`86124.23375000`** — the report puts that at **5.7%** of the $1,500,000 firm cap with
   `1,413,876` of headroom; net `-10728.59625000`, **1.1%** of the net cap. Against the ADR-0132 owner
   budget of $200,000 the book is now materially deployed rather than dormant. Nowhere near a cap, breaker
   not halted. Exposure is not the danger.
3. **Cause.** No change was deployed this cycle, and ADR-0135's guarded branch did not fire for a second
   window (zero `sources=1` orders in `29 × sources=2` and `12 × sources=3` since 15:00Z), so it can be
   neither credited nor blamed (Rule 237/239). The `-45.96` is mark-to-market on positions opened in the
   preceding minutes — `ALPHA` is `unrealizedPnl -122.06541663` on a `64809.82000000` book whose 21
   positions are all newly on. That is market and the desk's own entry, not a change.
4. **Danger.** No. Not near a cap, not near the breaker, and no longer dormant. The pathology is narrower
   and sharper than "under-deployed", and it is item #1 below.

## What the deployment revealed

`/api/fusion/targets` splits with no exception: **`deltaQty` is exactly `0.0` on all four targets whose
`currentQty` is 0 — PFE, HD, JPM, MSFT — and non-zero on all five that hold something.** PFE carries the
largest absolute forecast in the book (`6.601885581804063`, three sources, `estimable: true`) against a
target of `6589.465055` and plans nothing, while CVX trades on `-2.6889594393466063`. So this is not the
conviction floor and not slow pacing; the veto is conditioned on being flat.

Two mechanisms in code produce exactly that shape, and I am deliberately **not** picking one: the ADR-0126
σ-cold veto in `PositionBuffer.mayIncrease`, which routes the delta through `TargetPlanner.reduceOnly` —
and `reduceOnly` returns zero identically when `cur.signum() == 0`, so a flat name under it can never open
at any conviction; or the ADR-0094 band measured from flat, which ADR-0133's revert restored. This register
has already had one trace on this item falsified and one causal story reversed by a timestamp, so the next
change is the one that makes the veto reason **observable** on `/api/fusion/targets` — the same move
ADR-0134 made for orders, which turned four cycles of guessing into a lookup.

I also logged, but did not act on, a second live pattern: BAC was bought at `+5.310419321398655` (three
sources) and twenty minutes later carries `-5.488089050299691` (three sources); AMZN the same. The
dominant fusion weight is `reversion` at `1.6917588861521635` in a regime reading `"trend": "CHOP"` — a
signal whose sign is expected to invert faster than an 18-minute entry schedule can reach its target.

## What I did

Re-ranked `reports/must-fix.md`. **#1** is now the opening veto, with a VERIFY-BY that demands the veto
reason be readable from telemetry *and* a flat name actually opening — a gross rise alone no longer counts,
because that half already passed while the defect stood. The zero-source sweep drops to **#2**: last
block ranked it first on the argument that deploying capital just feeds it, and this window falsified that
coupling — `+57555.68` of gross went on across eight names and the sweep fired zero times. New **#3** is
the mid-entry sign reversal; **#4** is the ADR-0084 cancel churn, which got worse (`FILLED 4921` /
`CANCELLED 1772`, more cancelled than filled inside this window).

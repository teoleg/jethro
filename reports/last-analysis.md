The desk's own significance test passes exactly one source — and the combiner still let a source that FAILS that test out-vote it ten to one on the largest position in the book (ADR-0097).

## Situation — every figure below is quoted from the live endpoints; none is computed here

**1. Money.** Total PnL `$267.21` on `/api/risk` `.total`. The SITUATION header puts the window at
`+34.22` and the last three runs at `+105.87`; `run-status.json` has `on_track` true with
`pnl_growth_pct` far ahead of the 1% target, `stale` and `underwater` both false. The book is **not
bleeding**. Attribution splits it `ALPHA +339.59`, `MACRO +376.99`, `HEDGE −451.51`, on `$356.76` of
fees — the fee line is larger than the firm's entire profit, and the hedge alone loses more than the
firm makes.

**2. Risk — this is the failing half.** Gross exposure `$50,876.08` against net `$6,444.17`, with the
header flagging **EXPOSURE RISING** at `+36,404.57` on the window and `+30,244.03` over three runs.
VaR95 `$437.47` / ES95 `$527.26`; the firm drawdown breaker is `halted: false` and, against Oleg's
`max-firm-drawdown` of `50000`, nowhere near tripping. So this is not a danger state by the owner's own
stated tolerance — the book is small relative to every configured limit — but PnL is rising in tens of
dollars while gross rises in tens of thousands, and that ratio is what is being scored.

**3. Cause.** Last cycle's change (`d82aea4c8`, ADR-0096) was scored **❌ BAD** and auto-reverted at
06:30. Gross is still `$50,876` *after* that revert, so the ramp is not that change's doing — it is
structural, and it is why six of the last eight changes scored BAD on "exposure grew with no PnL gain".
Honest attribution of this window: the exposure move is **mechanism, not last cycle's change** — the desk
is grinding toward a target book an order of magnitude larger than what it holds, at the derived ADR-0080
rate, and would have done so under any of these commits. I credit last cycle's change with none of the
PnL move and none of the exposure move; the revert removed it before most of the window.

**4. Danger.** Not a danger state: PnL up, breaker far away, VaR small. So the response is not to de-risk
blindly — it is to fix the mechanism that keeps adding exposure without adding edge.

**5. Order-level post-mortem.** `recent_orders` shows the same trade every 30 s: `JPM SELL`, `AAPL SELL`,
`JNJ BUY`, `GOOG BUY/SELL`, in the same direction, cycle after cycle, plus a `HEDGE ES BUY` chasing
behind them. That is a monotone ramp, not a strategy firing. Winners: `AAPL +283.27`, `MSFT +127.15`,
`JPM +120.73`, `JNJ +100.25`. Losers: `HEDGE ES −424.44` and `NQ −26.99`, `GOOGL −161.84` and
`SAP −85.46` (both now flat), `GOOG −35.18`.

## Diagnosis — the mechanism

The desk runs one statistic through two consumers and gets two answers. The **edge gate** passed exactly
one source this cycle: `reversion` (`t = 10.65`, `p = 2.9e-13`). `social` failed it (`t = 1.82`,
`p = 0.039`), and `momentum` (`−2.80`) and `trend` (`−4.96`) are measured significantly **negative**. Yet
the combination weights read `reversion 2.106`, **`social 1.757`** — a source the desk itself says has
not demonstrated an edge carrying 83% of the weight of the only one that has.

The cause is that the weighting statistic is `Φ(t)`, a **probability**. Essentially all of Φ's dynamic
range lies in `t ∈ [−2.5, 2.5]`; past the hurdle it is flat, so it cannot tell a source that barely
clears from one that clears fivefold. On `JPM` — the desk's largest position, `$13,288` gross, planned
`−162.58` shares — social's `−16.00 × 1.757` out-voted reversion's `+1.29 × 2.106` roughly ten to one and
**reversed the sign of the only view with a demonstrated edge**. The desk then spends every cycle, and
every basis point of fee, grinding into that short.

## Change

`EdgeGate.demonstratesEdge` — the desk's existing significance test at **zero cost** (cost still decides
whether to trade, never whose view counts) — now admits a source to the vote, and any source that fails
it is held at the existing MIN weight. It still contributes, so the active-source count and the ADR-0076
diversification multiplier are unaffected. No new dial, no new statistic, no money number: `min-sample`
and `t-hurdle` are the gate's own. Strictly **one-way** — MIN is the clamp bound already applied, so a
demotion can only lower a weight and a measured-negative source is never inverted into a contrarian bet.
That one-wayness is the property ADR-0087 and ADR-0093 both lacked, and this repeats neither: it does not
remove the floor and it does not replace `Φ(t)`. Proposed **ADR-0097** in the same commit; full suite
green.

Expected: the desk stops sizing its largest positions on evidence it had already judged insufficient, the
weight vector concentrates so `DM` falls (`JPM` 1.1944 → 1.1299, its fused view `−7.66` → ≈`−1.5`, under
`min-forecast-to-route`), and the planned book — and the ramp toward it — shrinks. If this scores BAD the
source-weighting lever is definitively closed and the next cycle must move to the hedge (`−451.51` on
`$8,306` of gross, the firm's single worst position) or to the reversion sensor's own span.

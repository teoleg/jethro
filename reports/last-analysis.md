Held the code frozen for the pending measurement — and found why the book cannot open a position: the no-trade band is wider than the aim on every flat name, reconciled to the routed share.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, `recent_orders`, `orders_by_status`, or the scorer's own output. None is authored here —
invariant 7 / ADR-0016.)*

## Step 0 — last cycle's pending change (`026cda49d`, the ADR-0136 revert): ✅ DEPLOYED, ⏳ STILL NOT SCORED

`scripts/score-change.py score` reports `026cda49d still accumulating evidence (3/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still holds its snapshot. Per ADR-0116 that
**forbids a code change this cycle** — piling one on top destroys the evidence. I recorded **no new
baseline**, so its window is intact. What follows is the diagnosis that aims next cycle's one change.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-308.37** (`/api/risk` `.total`, firm headline incl. hedge), **+$6.54** since
   last run and **+$6.54** across the last three. Still `UNDERWATER`, `on_track=false`. The books:
   `ALPHA -364.45167289`, `HEDGE +112.87993718`, `MACRO -56.79950536`.
2. **Risk.** Gross **$11,313.99**, net **$748.56** — **0.8%** of the $1,500,000 firm cap, **$1,488,686**
   of headroom. The book came **off dormant** this cycle (gross was $0.00). Rising exposure with this much
   room is the goal, not a concern.
3. **Cause.** The move is **not** attributable to the pending revert, and not to any code. Four orders
   filled in the window, all `fusion entry — target increase`: `AMZN BUY 10` and `GOOG BUY 5` at
   `14:55:08`, the `HEDGE ES SELL 0.013712` that followed them, and `AMZN BUY 5` at `14:59:41`. That is
   the desk's normal entry path resuming, not a changed rule.
4. **Danger.** No. `DANGER` needs bleeding *near* the cap; gross is at **0.8%** of it, `breaker.halted`
   **false**, `riskCuts` **[]**. The live problem is the inverse — **99.2% of the budget is undeployed**.

## The 14:30Z item #1 was resolved without any code — which falsifies its root cause

Its VERIFY-BY was met on all three legs: non-zero `aims` **5 → 20** of 22, `insideBuffer` **22 → 20**,
gross **$0.00 → $11,313.99**. But the only commit since was `4f9ff26`, the **offline report generator**.
What actually moved was JVM state: `streamVolMeasuredNames` **6 → 20**, `bookVolBrake` **0.880 → 1.0**,
`portfolioRiskMultiplier` **0.977 → 1.0**. The zero aims were a **cold-start coverage artifact** on an
ephemeral ~25-minute JVM — not the structural sizing defect the register asserted. That is the third time
this loop has read a warm-up state as a permanent defect, and the previous attempts at it graded ❌ BAD.

## The real item #1, closed-form and reconciled to the share

`PositionBuffer.band` is `scale × fraction`, `scale = |target| × Forecast.TARGET_ABS / |forecast|`, with
`TARGET_ABS` **10.0** and `position-buffer.fraction` **0.10** — and `widthFor` returns that floor
unchanged because `edgeGate` is **`null`**, so ADR-0101's measured width never applies. The band is
therefore exactly **`|target| / |forecast|`**. Against `aims` ⋈ `targets` from this cycle, every
zero-`deltaQty` name has `|gap| < band`: NVDA gap **65.07** vs band **92.00**, XOM **90.96** vs **116.53**,
CAT **9.05** vs **20.38**, PG **66.61** vs **203.41**, AAPL **38.25** vs **101.60**. AMZN is the single
name that trades, and it trades to the band's near edge — `80.236431 − 75.16 = 5.07` against the observed
`deltaQty` **5.074178**. **6 of 6 reconcile**, so this is measured, not hypothesised.

The opening condition reduces to `|aim|/|target| > 1/|forecast|`: at the forecasts carried (**4.81–5.98**)
the aim must reach **17–21%** of target, while the ADR-0080/ADR-0117 adjustment path has it at
**6.5–20.1%**. From a flat holding the desk is stalled a few percent short of its own trigger on every
name — a weak view is handed a band *wider than its own target*. AMZN cleared it only by already holding 10.

**Next cycle's one change targets that**, once `026cda49d` is scored, and it needs a superseding ADR
stating which quantity is re-based — the band's typical-strength `scale`, or the aim path — not a quiet
dial turn on Carver's convention or on ADR-0117's identity. I deliberately did **not** re-rank the fee bill
to #1: `totalFees` **357.229782** exceeds the deficit **−308.37124107**, but that is lifetime across
**5129** filled orders and mostly predates the cost-aware work (ADR-0064/0084/0094/0101), while this window
placed **4** orders. Chasing it would be overfitting a sunk number.

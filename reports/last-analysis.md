The desk was holding six names on the opposite side of their own live target and barely moving — and the buffer's own published arithmetic says why: it was steering them to a destination that is *also* on the wrong side, because the no-trade band is scaled by the target and straddles flat. Fixed as ADR-0132.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Situation (answered first)

1. **Money.** Total PnL **$131.04**, **+$7.70** since last run and **+$1.25** over three. Not bleeding.
2. **Risk.** Gross **$38033.13** — **2.5%** of the $1,500,000 firm cap, **$1,461,967** of headroom. Net
   **$-6383.71**, **0.6%** of the $1,000,000 net cap. Flags: **none**. Nowhere near a cap or the breaker.
3. **Cause.** Last cycle's change `64a7a6336` — the completed auto-revert of the graded-BAD ADR-0131
   re-seed — scored **⚠️ INCONCLUSIVE** and is kept. Its own claim was ✅ VERIFIED on five independent
   JVMs. With no pending baseline, a new change was due this cycle.
4. **Danger.** None. `run-status.json` (heartbeat `2026-07-30T19:06:38Z`) reads `pnl_growth_pct` **7.74**
   vs target **1.0**, `on_track` **true**, `stale` **false**, `underwater` **false**.
5. **Attribution.** The window's move is **not separable** into market vs change — the scored change was a
   revert that opens and closes nothing, and a JVM boot sits inside the window. I claim neither.

## What I found, and how I stopped guessing

The last four cycles blamed this on the no-trade band being inert, then on an "inverted" ADR-0101 fallback,
then on the target book "re-randomising every 30s". I re-tested all three against this run's telemetry and
**the third was wrong too**: sampled at three consecutive re-plans the targets *drift*, they do not
re-randomise (MSFT **-63.86 → -51.60 → -57.99**, NVDA **+132.74 → +169.02 → +174.22**, PG **-285.25 →
-153.26 → -158.88**). The earlier reading was the forecast crossing zero on a few names, not the book.

What the same three samples show in every one of them is the real defect. Six names — GOOG **-9.00**, AAPL
**-13.00**, NVDA **-16.00**, PG **+26.00**, KO **+45.00**, WMT **+9.00** — held *unchanged* on the opposite
side of their own live target, moving between **0.000000** and **0.2** shares per re-plan on gaps of 9 to
45 shares. KO and WMT read `deltaQty` **exactly 0.000000** in all three.

Then I stopped inferring. `/api/fusion/targets` also publishes `aims`, and with it the entire
ADR-0094/0101/0102 arithmetic can be recomputed — it reproduces the published `deltaQty` **exactly on all
13 planned names** (GOOG `6.043466 × 0.008298707 = 0.050153`; AMZN `22.379233 × 0.008298707 = 0.185720`).
The band is scaled by the average position at the **target**, and early on ADR-0080's hour-long aim path
that band exceeds the aim, so the no-trade region **straddles flat and reaches onto the side the forecast
opposes**. GOOG's destination computes to **-2.956534** while its target is **+32.814821**: the desk's own
arithmetic intended to stop while short a name it wants long. ADR-0102 bounds the *intent*; nothing bounded
the destination a whole band below it. So the desk carried gross exposure on positions its current view
contradicts, plus the hedge notional sized against them.

**ADR-0118 diagnosed this exact position and its fix cannot reach it** — it scoped the escape to
`!mayIncrease`, which with `edge-gate.enabled=false` (ADR-0122) and the ADR-0126 σ sensors warm never runs.
Its own test pinned the open-gate case at `deltaQty` 0 as if that were correct.

## The change

ADR-0132: `PositionBuffer.onTargetSide` replaces a destination on the opposite side of flat from the target
with **flat**, before the ADR-0107 rating so it stays a rated unwind and not a liquidation. Band, aim path,
ADR-0101 width and rating all unchanged; no number introduced — the bound is flat. Proved to fire only on a
holding the target opposes, to resolve to `|held+delta| = 0` (never opens, enlarges or side-flips), and
never to trade past the aim. Full `-Pci test` green.

**Watching next run:** no name with `currentQty` and `targetQty` of opposite sign and `|deltaQty|` below
`|currentQty| × 0.0082987`; `insideBuffer` falling from **19** of **24**; firm gross falling as the
contradicted positions wind off.

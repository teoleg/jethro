The dormancy broke — the desk traded at an outlier forecast, which confirms the no-trade band is what gates it, and left the book short a name it now wants to be long.

*(Every figure below is read from `/api/risk`, `/api/orders`, `/api/fusion/targets`, `/api/signals/telemetry`,
`/api/attribution`, the named source files and the scored ledger. None is authored here — invariant 7 / ADR-0016.)*

**Money.** Total PnL **-$599.77535104**, up **+3.30** since the last run and **+3.30** over the last three;
`realizedPnl` **-$603.28776489**, `unrealizedPnl` **+$3.51241385**. Still UNDERWATER and still off the
**1.0%**/3-iteration growth target. But the book is no longer empty: gross exposure **$0.00 → $4224.88160000**,
net **$1157.48160000** — **0.3%** of the **$1,500,000** firm gross cap, **$1,495,775** of headroom unused.

**Risk.** No danger state. `var95` **45.43**, `es95` **56.02**, `var99` **69.08** over **155** observations
with `coveredExposure` **4224.64** and `skippedExposure` **0.00**; breaker `halted: false`; regime CALM,
`volRatio` **0.93**. Exposure rose from dormant with the cap three orders of magnitude away — that is the
goal, not a concern.

**Cause — the desk traded for the first time in ~19 hours, and it is attributable.** `orders_day.total` is
**2**: ALPHA NVDA `SELL 7.000000` FILLED on `fusion entry — target increase
[forecast=-9.608504615051698, sources=3]`, and the ADR-0019 auto-hedge ES `BUY 0.006928` FILLED on
`Σβ·E = -2691.82 systematic` that followed it 12 s later. Only two positions are non-flat (NVDA **-7.000000**,
ES **0.006928**); the other 21 rows are flat. So the whole **+3.30** window move is the mark on those two
fills — NVDA `unrealizedPnl` **+4.27000000**, ES **-0.75758615**, and `attribution.totalFees`
**398.004411 → 398.212047**. **No code change landed this window** (ADR-0116 freeze, below), so *none* of it
is attributable to a change of mine: this is the existing code finally clearing its own band, marked over
~14 minutes on 7 shares. That is noise, not evidence of edge, and I am not claiming it as a win.

**What this proves about must-fix #1.** Last cycle I derived from `PositionBuffer` that a flat name escapes
the no-trade band only when its forecast is far above typical strength — the band is scaled by
`|target| × TARGET_ABS / |forecast|` with `TARGET_ABS` **10.0** (`Forecast.java:20`) and `width` floored at
`bufferFraction` **0.5** (`application.properties:313`). This cycle the prediction was tested by the app: the
one name that traded carried `|forecast|` **9.608505**, and every planned name visible at this snapshot
(`|combinedForecast| ≤ 3.722563`) is still at `deltaQty` **0.0**. Predicted, then observed.

**And it exposed a worse failure mode than "the book can't build".** NVDA's `combinedForecast` inverted from
**-9.608505** at the fill to **+3.722563** at the plan ~14 minutes later. The desk now holds **-7.0** against
a `targetQty` of **+176.077255**, and the band is releasing `deltaQty` **+0.058091** per cycle toward it. So
the buffer admits positions only at extreme conviction and then holds them against a reversed view — it
preferentially retains exactly the positions most likely to have been opened on a forecast that mean-reverts.
It is cheap today (NVDA is **+4.27000000** unrealized) and it is the mechanism by which this book would carry
stale, view-contradicting risk once it is no longer flat. Item #1 keeps rank; its severity is upgraded.

**One reading I still cannot explain, and am not smoothing over.** Working the published `bufferedDelta` path
by hand gives `edge = 0` for both NQ (**0.136033** target, **0** held, forecast **3.146893**, delta
**0.001584**, last cycle) and NVDA (above) — yet both show a small non-zero delta. Two instances now, not one.
It does not change the money conclusion (**0.058091** against **176.077255** is a build measured in thousands
of cycles), but it means my model mis-predicts two of its own rows, so it is carried in
`reports/must-fix.md` as a unit test the fix must pass *before* the band is touched.

**Decision: no code change.** `scripts/score-change.py score` reports `d51f179a2` (ADR-0139) **still
accumulating evidence (5/6 cycles)** and `reports/.pending-baseline.json` still names it, so under ADR-0116 a
change now would destroy the evidence — this is the fifth and final cycle of the freeze; it lifts next run and
the band fix goes in then. ADR-0139's mechanism ✅ verified again on this fifth independent boot (**15**
corroborations in **1439 s** of uptime versus **18 in 64,010 s** pre-fix). Separately `social`'s expectancy
swung a fifth time — **8.855736 → 4.837178 → 7.810160 → 8.351863 → 8.808298** bps on eight more resolved
observations — so the standing decision request to Oleg on `jethro.fusion.social.per-channel` stays open and
explicitly **NOT-YET-SUPPORTED**; that dial is the owner's and the loop does not touch it.

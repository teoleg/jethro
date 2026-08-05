Persisting the aim would NOT unfreeze the desk — four of six frozen names had enough process lifetime this boot and still routed nothing, so the freeze is the aim losing a race to a moving target, not a cold-start clock; no change, the revert is 5 of 6 cycles into measurement.

*(Every figure below is read from `/api/risk`, `/api/fusion/targets`, `/api/social`, `ops_jvm`, the report's
`recent_orders` and the scorer, or derived by script from those plus the shipped constants. None is authored
here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** `risk.total` reads `totalPnl` **-640.57492504**, **-11.63** since the last run and **-3.31**
   over the last three. Underwater and drifting — but by **marks, not by trading**.
2. **Risk.** `grossExposure` **5242.89912500** is **0.3%** of the $1,500,000 firm cap (headroom
   **$1,494,757**); `netExposure` **505.50087500** is **0.1%** of the $1,000,000 net cap. `breaker.halted`
   **false**; `regime` **CHOP**/**CALM** at `volRatio` **0.95**; `var95` **75.35** on `coveredExposure`
   **5242.90**. Nowhere near danger — the live problem is the inverse, a desk holding almost nothing while
   the budget goes unused.
3. **Cause.** Nothing this cycle. The pending change is `e956dcf46`, the completed revert of the graded-BAD
   ADR-0139; `scripts/score-change.py score` prints `still accumulating evidence (5/6 cycles) — held, not
   scored this run`, so it has no verdict yet and it is not mine to pre-judge.
4. **Danger.** None — not near a cap, breaker clear. The `DANGER` condition (bleeding *near* the cap) does
   not hold; the flag that does hold is UNDERWATER.
5. **Orders / attribution.** `recent_orders` shows **no new order** since the 17:00:28Z auto-hedge — the
   **fourth consecutive zero-order window**. `attribution` splits the firm total into ALPHA
   **-603.20172505**, MACRO **-56.79950536**, HEDGE **+19.42630537**. Every dollar of the move is a mark on
   a position no code of mine touched: **100% market, 0% change-attribution** (Rule 357).

## Step 0 — `e956dcf46` (the ADR-0139 revert): ✅ VERIFIED, fifth independent boot

`traffic.timestampMillis` **1785956401616** − `ops_jvm.uptimeSeconds` **1364** puts boot at
**18:37:17.616Z**, after the revert's **16:38:20Z** commit and in a different process from the
18:06:55.128Z boot graded last cycle. `counters.corroborated` reads **7** in **1364 s** — the lowest rate
yet, against **8/1387 s** and **11/1344 s** post-revert and **17/1427 s**, **15/1439 s**, **17/1362 s** on
the ADR-0139 boots. `manipulationSuspected` **38** on `ingested` **3390** / `kept` **809**.

**Last cycle's VERIFY-BY was the wrong metric and is retired.** It said the `credible: true` count should
stay a small minority of `recent`; this cycle **5** of the 12 shown read `credible: true` and that is *not*
a regression, because all five are `channel: yahoo` — the news-RSS path. `NewsSocialFeed.java:54-55`
constructs every wire item with `SYNTHETIC_FOLLOWERS` **5_000_000**, `verified` **true**,
`SYNTHETIC_AGE_DAYS` **3650**, so a curated outlet is credible by construction and always has been,
untouched by ADR-0139 or its revert. The metric that actually discriminates: **all seven `stocktwits:`
rows read `credible: false`** — no uncurated author passed. That, plus the falling corroboration rate, is
the conjunction at `SocialChannels.java:54-55` doing its job.

## Item #1 — ⚠️ STILL-BROKEN, and its diagnosis is now partly WRONG

Last cycle's VERIFY-BY was: after a fresh boot, at least one name's `|aim|/|targetQty|` should exceed the
`1 − e^(−uptime/3600)` an in-memory reseed could produce. At **1364 s** that cap is **0.315378**, and
**every** visible name is under it — BAC **0.063338**, CVX **0.219300**, AMZN **0.065118**, XOM
**0.059964**, MSFT **0.052135**, AAPL **0.017274**. Unfixed, as expected: nothing shipped.

But the same table **refutes the fix I had ranked #1**. The release condition off `PositionBuffer.band()`
is `|aim|/|target| > TARGET_ABS × fraction / |f|`, i.e. `1/|f|` at the live **10.0** and **0.10**. Four of
the six — BAC (needs **0.128355**), AAPL (**0.161675**), AMZN (**0.235171**), XOM (**0.291657**) — have
requirements *below* the **0.315378** this boot's lifetime allows. They had the time and still routed
nothing. **So persisting the aim across restarts would not have unfrozen them**, and Rule 366's "safe
structural fix" does not fix the thing it was promoted for.

What the table does show is a **12.7× spread** in `|aim|/|target|` across six names that share one derived
`adjustment-rate`, one band, and one seed (`currentQty` **0** for all six). Under a stationary target that
spread is arithmetically impossible — every name would read the identical ratio. The aim is therefore not
a warm-up clock ticking toward a fixed target; it is **chasing a target that moves within the boot**, in a
`CHOP` regime, through a band that is *inversely* proportional to forecast strength. A weak view is
penalised twice: the planner already shrank its target, and then the band demands a *larger* fraction of
that smaller target.

**No change this cycle** — the ADR-0116 freeze binds at 5/6, and I would rather ship the right fix next
cycle than the one this cycle's own numbers just refuted. One reading is left open and not smoothed away:
`insideBuffer` **20** against `instruments` **21** says one name was outside its band at the 18:59:43Z
snapshot, yet no fusion order appears in `recent_orders` — a second instance of the Rule 343 tiny-delta
anomaly, and the fix's test must answer it.

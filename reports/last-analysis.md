The frozen desk is a state-persistence bug, not a band-width one — the aim map is in-memory, so the buffer's release time must be paid inside one ~1400 s process lifetime, and two names need more than that; no change, the revert is 4 of 6 cycles into measurement.

*(Every figure below is read from `/api/risk`, `/api/fusion/targets`, `/api/social`, `ops_jvm`, the report's
`recent_orders` and the scorer, or derived by script from those plus the shipped constants. None is authored
here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** `risk.total` reads `totalPnl` **-631.26343754**, **-16.27** since the last run and **-10.81**
   over the last three. Underwater and drifting — but by **marks, not by trading**.
2. **Risk.** `grossExposure` **5255.10763750** is **0.4%** of the $1,500,000 firm cap (headroom
   **$1,494,745**); `netExposure` **514.81236250** is **0.1%** of the $1,000,000 net cap. `breaker.halted`
   **false**; `regime` **CHOP**/**CALM** at `volRatio` **0.92**. Nowhere near danger — the live problem is
   the inverse, a desk holding almost nothing while the budget goes unused.
3. **Cause.** Nothing this cycle. The pending change is `e956dcf46`, the completed revert of the graded-BAD
   ADR-0139; the scorer prints `still accumulating evidence (4/6 cycles) — held, not scored this run`, so it
   has no verdict yet and it is not mine to pre-judge.
4. **Danger.** None — not near a cap, breaker clear.
5. **Orders / attribution.** `recent_orders` shows **no new order** since the 17:00:28Z auto-hedge — the
   **third consecutive zero-order window**. The book is the same ALPHA **GOOG 8.000000** and HEDGE **ES
   -0.006099**. Every dollar of the move is a mark on positions nothing touched: **100% market, 0% change**.

## Step 0 — the ADR-0139 revert re-verified on a fourth, independent boot: ✅ VERIFIED

Boot **18:06:55.128Z** (`traffic.timestampMillis` **1785954602128** − `uptimeSeconds` **1387**), after the
revert's **16:38:20Z** commit and a different process from the one graded last cycle. `counters.corroborated`
ran **8** in **1387 s** — its lowest rate yet, against **11/1344 s** post-revert and **17/1427 s**,
**15/1439 s**, **17/1362 s** on the ADR-0139 boots. `manipulationSuspected` **39** on `ingested` **3390** /
`kept` **794** still fires. One of the 12 `recent` posts read `credible: true`; that is the strict conjunction
**working**, not a regression — `SocialChannels.java:54-55` requires verified **and** followers ≥ **5000**
**and** age ≥ **180** together, and the author clearing all three is a verified, long-lived publisher, which
is exactly who should pass. ADR-0139's defect was passing on any *one* credential. Recorded honestly: the
telemetry does not expose the author's three fields, so the conjunction is proven from the source line and
the rate, not from that row.

## Decision — no code change, and what the cycle produced instead

`reports/.pending-baseline.json` still names `e956dcf46` at 4/6 cycles, so the ADR-0116 freeze binds. Step 0
ran anyway, and the cycle's product **corrects my own conclusion from last cycle**.

`insideBuffer` reads **20 of 20** — every name frozen, including flat names carrying large plans (KO
`targetQty` **1584.259488**, WMT **431.975458**, NEE **-404.251600**, BAC **-512.748000**), with the desk
holding one position, GOOG **8.000000**, against its own plan of **135.389483**.

Last cycle I concluded the freeze was a reset race that item #1's `target == 0` branch supplied, so one `if`
would fix both items. **That is too strong.** `PositionBuffer.java:105` holds `aims` in a plain in-memory
`HashMap` — no LMDB, no warm restart — so at every boot the map is empty and `nextAim` takes
`previous == null ? held` (`:263`), reseeding every name on a flat book at **zero**. `Forecast.TARGET_ABS`
**10.0** × `position-buffer.fraction` **0.10** = **1.0**, so release reduces to `|aim|/|target| ≥ 1/|f|`, i.e.
**t ≥ 3600·ln(|f|/(|f|−1))**. On this cycle's own forecasts that is ~**624 s** for KO and ~**799 s** for GOOG,
but ~**2230 s** for NEE and ~**2597 s** for BAC — against observed process lifetimes of **1387**, **1344**,
**1362**, **1427**, **1439 s**. **NEE and BAC cannot open in this harness at all**, on the warm-up horizon
alone, with no reset invoked. Reading the whole of `apply` also shows **five** seed/reset paths, not the two I
weighed (`nextAim:259`, `withinTarget:311`, `apply:138`, `apply:178`, `apply:163`), and the shortfall-vs-uptime
arithmetic **cannot** distinguish a reset from an aim chasing a rising target — so instrumenting "which reset
fired" was the wrong plan anyway.

**What changes next.** The buffer item is **promoted to #1** in `reports/must-fix.md` (the degenerate
zero-forecast liquidation drops to #2 — un-refuted, but smaller while the desk is frozen), because it is the
largest live cost and it now has a *safe* fix that touches no band, rate or cap: **persist the aim across
restart** as ADR-0014 derived state, with a staleness bound so a long outage cannot resume an ancient intent.
That ships with its ADR the cycle the freeze lifts.

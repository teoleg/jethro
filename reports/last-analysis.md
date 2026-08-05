The buffer freeze is a RACE the aim loses by 4.3×–11.3× — it needs 779–2417 s of uninterrupted accumulation and is getting reset every 72–347 s; no change, the revert is 3 of 6 cycles into measurement.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `/api/social`,
`ops_jvm`, the report's `recent_orders` and the scorer, or derived by script from those plus the shipped
constants. None is authored here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** `risk.total` reads `totalPnl` **-633.35472504**, **+3.91** since the last run and **-10.50**
   over the last three. Underwater and off the owner's growth target (`pnl_growth_pct` **-5.3%** vs
   **1.0%**; `on_track=False`, `stale=True`) — but not bleeding this window; the move was positive.
2. **Risk.** `grossExposure` **5252.55892500** is **0.4%** of the $1,500,000 firm cap (headroom
   **$1,494,747**); `netExposure` **512.72107500** is **0.1%** of the $1,000,000 net cap.
   `breaker.halted` **false**. No danger flag — the book is **dormant**, which is the opportunity.
3. **Cause.** The pending change is `e956dcf46`, the completed revert of the graded-BAD ADR-0139. The
   scorer prints `still accumulating evidence (3/6 cycles) — held, not scored this run`, so it has no
   verdict yet and it is not mine to pre-judge. Its **mechanism** re-verified ✅ on a third, independent
   JVM (boot **17:37:38.570Z** = `traffic.timestampMillis` **1785952802570** − `uptimeSeconds` **1344**):
   **all 12** `recent` STANDARD posts read `credible: false`, and `counters.corroborated` **11** in
   **1344 s** stays below the **17/1427 s**, **15/1439 s**, **17/1362 s** of the ADR-0139 boots.
4. **Danger.** None — not near a cap, breaker not tripped, PnL up on the window.
5. **Orders / attribution.** `recent_orders` shows **no order at all** since the previous report. The book
   is the same ALPHA **GOOG 8.000000** (`unrealizedPnl` **-11.12000000**, was **-14.60000000**) and HEDGE
   **ES -0.006099**. Every dollar of the move is a mark on positions nothing touched: **100% market, 0%
   change** — the one window where that split is unambiguous.

## Decision — no code change, and what the cycle produced instead

`reports/.pending-baseline.json` still names `e956dcf46` at 3/6 cycles, so the ADR-0116 freeze binds: a new
change now would destroy the evidence the revert is accruing. Step 0 ran anyway, and the cycle's product is
a **quantification of must-fix item #2** that turns the fix from arguable into specifiable.

Last cycle established *where* the no-trade band releases: from a flat holding,
`|aim|/|target| ≤ 1/|forecast|`. This cycle establishes *how fast the aim can get there*. `edgeGate` is
**null**, so the derived ADR-0080 rate falls back to the shipped base horizon and
`adjustmentRateFor(30, 3600)` gives **a = 0.008298707361124036**; the aim e-folds from flat as
`target·(1 − e^(−t/3600))`, so release needs **t ≥ 3600·ln(|f|/(|f|−1))** seconds of uninterrupted,
sign-stable accumulation. Inverting the six visible live ratios gives aim ages of **72–347 s** against
requirements of **779–2417 s** — short by **4.3×–11.3×**. The decisive check is against the process itself:
uninterrupted since the **1344 s** boot the ratio would read **0.3116**, which clears KO (0.1946), NVDA
(0.2031) and MSFT (0.2542) outright; it reads 0.0199–0.0920 instead. **The band is not slow — it is a race
the aim keeps losing to a reset**, and the desk can only open on an outlier forecast that shortens the
requirement enough to win one (the 15:45:50Z NVDA fill at **-9.608504615051698** needed only **396 s**).

That also links the two must-fix items **causally**, not just structurally. `nextAim`
(`PositionBuffer.java:259`) snaps the aim to **zero** on a flat target — so item #1's degenerate
`[forecast=-0.0, sources=1]` plan does not merely liquidate the position, it **destroys the accumulated
aim** item #2 needs minutes to build. Item #1 keeps rank #1 with its severity upgraded, even in a window
where it did not fire. What this snapshot **cannot** do is separate that reset from `withinTarget`'s
sign-flip reset (`:311`), and the age inversion assumes a stable target since the last reset — a growing
target would make the implied age an over-estimate, i.e. the shortfall worse, not better. So the fix must
first instrument **which** reset fires; that is next cycle's change, once the freeze lifts.

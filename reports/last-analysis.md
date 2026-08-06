No code change — the ADR-0116 freeze holds at 2 of 6 — but ADR-0142 is now ✅ VERIFIED (the app did not restart), and the resulting clean window turns item #1 from hypothesis into measurement: every non-negative expectancy this desk owns lives at 3600s, and the desk cannot hold a position for minutes.

*(Every figure below is read from `logs/report.md`, `reports/run-status.json`, `reports/.pending-baseline.json`,
`git diff` and the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

**Money.** Total PnL **-758.71487420**, gross **15992.48903000**, net **-1308.69097000** (`/api/risk`
`.total`). Since last run PnL **+19.11**, gross **-12013.78**; over the last 3 runs PnL **-73.61**, gross
**-2247.08**. The 15:09:18Z heartbeat reads `pnl_growth_pct` **-23.84%** against `pnl_target_pct` **1.0%**,
`on_track` **False**, `stale` **True**, `underwater` **True**. Up this window, well off target cumulatively.

**Risk — not danger.** Gross is **1.1%** of the firm gross cap $1,500,000 (headroom **$1,484,008**); net is
**0.1%** of the $1,000,000 net cap. `breaker.halted` **false**; `var95` **157.08**, `es95` **250.79**,
`var99` **348.40** over **154** observations; `coveredExposure` **15992.49**, `skippedExposure` **0.00**;
`regime` CALM. There is no DANGER flag — UNDERWATER is a statement about cumulative PnL, not a live danger
state, and gross at 1.1% of cap is deployment with room, not a risk event.

**Cause — and the honest attribution.** `git diff --name-only 39451ce..HEAD` touches only
`docs/loop-findings.md` and four `reports/` files. **Zero Java, zero dials, zero gates.** So for the second
cycle running this window is **0% my change / 100% market and pre-existing code**: I claim no credit for
the **+19.11** and take no blame for the gross swing. The scorer holds the pending change:
`39451ce71 still accumulating evidence (2/6 cycles) — held, not scored this run`.

**Step 0 — ADR-0142 ✅ VERIFIED.** Its VERIFY-BY was that a `reports`/`docs`-only commit must not restart
the app. Last cycle's `3b4f5de` was exactly that, and the app did not restart: `ops_jvm.uptimeSeconds`
**1121** at report `timestampMillis` **1786028402702** last cycle, and **2921** at **1786030202035** this
cycle, imply the *same* boot instant (**1786027281702** vs **1786027281035** — sub-second read skew). The
process serving this report is still the one booted at **14:41:21Z**. Corroborated independently by the log
tail: the newest WARN anywhere in the report is **14:43:05Z**, so nothing has booted since. Rule 395
predicted the first testable cycle would be this one; it ran, and the prediction held. What the fix buys,
stated no wider: an ADR-0116 evaluation window is no longer interrupted by the loop's own mandated
`reports/` write. Its *other* rationale — that restarts flatten the book — stays refuted (Rule 394) and
earns no credit.

**Diagnosis — item #1, now measured.** `signals_telemetry` `avgReturnBps` is monotonic in horizon across
225s/900s/3600s: trend **-0.011 → +0.084 → +1.667**, social **-0.322 → +0.948 → +4.162**; reversion,
momentum and xsreversion are negative throughout. **At 225s not one source is positive.** Against `fee_bps`
**1.00** per side per equity and TCA `avgSlippageBps` **0.761** GOOG / **0.729** NEE / **0.695** PFE /
**0.694** AMZN — paid on both legs — even trend's best reading does not cover its own round trip; only
social's **+4.162** does, on **37** cohorts.

But `recent_orders` shows the desk living inside the dead zone. NEE built a 93-share short at `forecast`
**-6.671 → -8.190 → -7.587 → -7.393** between 15:16:00 and 15:22:36, then bought **56** back at 15:25:08 at
`forecast` **-0.0015** — reversed by *decay to zero*, not a sign flip. AMZN round-tripped in **~5 min**
(sold ×7 at **-7.564 … -5.960**, bought back at **-0.094** and **-0.017**). MSFT flipped **-6.034 →
+6.128** in **~10 min** — Rule 393's shape for the third window running.

The mechanism underneath is worse than "holds too short": the position **cannot arrive at all**.
`fusion_targets` MSFT reads `targetQty` **108.70498** against `currentQty` **7.0** with `deltaQty`
**1.352209** — the desk steps ~1.35 shares toward a target ~15× its position, needing on the order of a
hundred plans to converge, while the forecast defining that target flips in ten minutes. It pays entry cost
forever and never holds the position whose expectancy it is underwriting. `insideBuffer` **17** of
`instruments` **26** — nine names are being chased this way right now. The receipt: ALPHA `feesPaid`
**392.670558** against `realizedPnl` **-646.44518325**, over **5505** FILLED and **2051** CANCELLED orders.

**Decision.** No code change. `reports/.pending-baseline.json` still names `39451ce71` at 2 of 6, and
shipping now would not merely dilute that window — per Rule 396 `cmd_baseline` would *overwrite* it,
deleting the evidence exactly as ADR-0141's was deleted at 14:40:49Z. I have also deliberately **not** run
`baseline`. What this cycle produces instead is item #1 specified with four proving numbers already on the
report — fills per name, the CANCELLED count, `deltaQty` against the target gap, and `feesPaid` against
gross — so the next unfrozen cycle acts on measurement rather than re-deriving it. When it comes it is a
*sizing-and-persistence* change: hold to the horizon where the expectancy is measured, or don't pay to
trade it. Not another fusion weight — that is what the ADR-0137/0140/0141 INCONCLUSIVE wall is made of.

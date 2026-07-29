The desk was planning its two largest positions — KO and WMT — in names its own log says it cannot stop out, so an unarmed trailing stop is now a veto on OPENING (ADR-0126).

*(Every figure below is read live from `/api/risk`, `/api/fusion/targets`, `/api/signals/telemetry`,
`/api/attribution`, the turnover/TCA aggregates or the app log, or is computed from those in exact
decimal by the script quoted in the commit. None is authored here — invariant 7 / ADR-0016. The PnL
verdict stays the scorer's.)*

## Situation

1. **Money.** Recovering, still underwater. Total PnL **−$16.77**, **+$68.30** on the window and
   **+$86.68** over the last 3 runs — the first positive 3-run move in some time. Fees paid **$28.68**
   against a firm total of −$16.77, so cost is still the larger number than the loss.
2. **Risk.** Ample room, and no danger. Gross **$12,610.76 = 0.8%** of the $1,500,000 firm cap
   (headroom **$1,487,389**); net **−$1,420.66 = 0.1%** of the $1,000,000 net cap. Breaker clear. The
   only flag is UNDERWATER. Gross **fell $12,283** on the window, which is the ES hedge tracking down,
   not a de-risking.
3. **Cause.** ADR-0124 scored **⚠️ INCONCLUSIVE** and was kept; `.pending-baseline.json` is gone, so a
   new change is due this cycle. The app restarted at ~19:09Z and ADR-0125's V48 universe is now live
   (`instruments 49`, 20 names in fusion) — that restart, not any loop change, is what moved the book.
4. **Danger.** No. Nothing near a cap or the breaker.

## Step 0 — must-fix #1 re-graded against THIS run's telemetry: ⚠️ RE-SCOPED, not actionable as written

Last cycle's #1 was "every held equity position is frozen inside its own no-trade band". **It is no
longer reproducible.** I recomputed every band in exact decimal from `/api/fusion/targets` at the
*correct* width — `jethro.fusion.position-buffer.fraction=0.10`, not the `0.5` the register quoted,
which is a different dial (`jethro.fusion.buffer-fraction`, superseded at the routing stage by
ADR-0094). At 0.10 the band is `|target| ÷ |forecast|`, and forecasts have strengthened since the
restart. GOOG, JPM and AAPL carry non-zero `deltaQty` right now; no held name is frozen. What remains
is that a name with `|combinedForecast| < 1` can never be opened — which is Carver's rule working as
designed on a view a tenth of typical strength, not a defect. **The register's #1 was measured with the
wrong constant; corrected in `reports/must-fix.md`.**

## The new #1 — the desk had no exit for its biggest bets

`FusionLifecycle.seedVolatility` logs, at WARN, `risk-cut σ sensor still cold for KO … this name cannot
be stopped out until its mark history has accumulated`. **Nothing consumed that warning.** Eight names
with live published targets — **KO, WMT, BAC, MCD, PG, XOM, NEE, HD** — have never armed an ADR-0086
stop, and the two **largest absolute targets in the whole book** are among them: **KO short 101.879300**
and **WMT short 74.222500**, against a largest *protected* target of NVDA 37.621300. ADR-0125 widened
the universe for breadth and every new name arrived unprotected, so the desk was about to put its
biggest risk exactly where it had no way out. That inverts the owner's thesis, which is add only when
risk is *contained* and cut when it is not — a position that cannot be cut keeps its whole left tail.

**Shipped ADR-0126:** a name whose σ sensor has not warmed is **reduce-only** — the desk may always cut,
hold or be stopped out of it, never open or enlarge it. Applied inside `PositionBuffer` because that
step re-derives every delta and discards upstream clamps, and evaluated **independently of the edge
gate**: ADR-0075's clamp and ADR-0118's escape both sit inside `if (gate != null && !gate.mayIncrease
(...))` and are dead while ADR-0122 holds `edge-gate.enabled=false`. That is the third rule found behind
that dead branch, so this one deliberately does not depend on it. No number introduced — the condition
is the sensor's own `sigmaPerSample(...).isPresent()`, the same reading `TrailingRiskCut` needs before
it will cut.

## On edge — checked, and the honest answer is still "none significant"

Per-source cohort t-stats from `/api/signals/telemetry` (`avgReturnBps ÷ stdCohortMeanBps/√cohorts`),
against the desk's own ~3 bps measured round trip (1.00 bps fee + 0.5–0.7 bps slippage each way):
reversion is the only source positive at **all three** horizons (+7.62 bps @3600s, t≈1.1; +2.17 @900s,
t≈1.3; +0.14 @225s) but clears neither the 1.5 hurdle nor cost at the short horizons. **xsreversion is
negative at every horizon** (−3.57 @3600s, −0.87 @900s) while carrying fusion weight 0.860, and social
is negative at every horizon on weight 1.030. That is worth acting on — but re-weighting sources is
combiner work, and it is not worth a cycle while the desk can still open risk it cannot close. Queued
as must-fix #2.

## Attribution this window — honest split

**None** of the move is attributable to a loop change: ADR-0124's footprint is names at `targetQty 0`,
and no loop commit was deployed into this window — the JVM restart that brought ADR-0125's V48 universe
live is what changed the book. The window's realised PnL is two closed-out losers (**GOOGL −$40.71** on
6.0 bps slippage, **MACRO/NQ −$35.82**, both now flat) against four winners (**NVDA +$42.15**, **AAPL
+$24.41**, **GOOG +$21.30**, **JNJ +$15.98**) and the **HEDGE book +$10.50**. ALPHA is **+$8.56 net of
$27.22 of fees**; the firm is negative because MACRO gave back **−$35.82**. Market and mix, not code.

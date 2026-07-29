Every equity position the desk holds is frozen inside a no-trade band 5–30× wider than the position itself, and ADR-0118's escape hatch — written for exactly this trap — is gated on the edge gate that ADR-0122 disabled, so it can never fire.

*(Every figure below is read live from `/api/risk`, `/api/fusion/targets`, `/api/var`, `/api/breaker`,
`/api/regime`, `/api/attribution`, the Flyway history, or is computed from those endpoints in exact
decimal by the script quoted in the commit. None is authored here — invariant 7 / ADR-0016. The PnL
verdict stays the scorer's.)*

## Situation

1. **Money.** Underwater and drifting down. Total PnL **−$130.75**, **−$26.18** on the window,
   **−$93.05** over the last 3 runs. `pnl_growth_pct` **−99.22%** against the **+1.0%** target —
   `on_track=false`, `stale=true`, `underwater=true`.
2. **Risk.** Not the problem. Gross **$24,743.86 = 1.6%** of the $1,500,000 firm cap (headroom
   **$1,475,256**); net **$284.74 = 0.0%** of the $1,000,000 net cap. VaR95 **$192.34**, ES95
   **$256.17** on covered exposure **$24,743.86** with `skippedExposure 0.00`. Breaker `halted: false`.
   Regime `CALM` / `CHOP`, `volRatio 0.98`.
3. **Cause.** ADR-0124 is at **5/6** and unscored. Its footprint is confined to the three names it
   silences — TSLA, META, GOOGL — none of which appears in the window's `recent_orders`.
4. **Danger.** **No.** UNDERWATER is the only flag; 98.4% of the gross ceiling is unused and the breaker
   is clear. Nothing here calls for de-risking.

**No code change this cycle.** `reports/.pending-baseline.json` is present and `score-change.py score`
prints `3e7817e4f still accumulating evidence (5/6 cycles)`. Stacking a change on a measurement in
progress destroys the evidence.

## Step 0 — ADR-0124 → ✅ VERIFIED, fifth consecutive cycle

Deployment proven behaviourally rather than from `git log` (rule 84): `uptimeSeconds` **836** against
`traffic.timestampMillis` **1785351601499** puts the JVM start at **18:46:05Z**, and
`/api/fusion/targets` reads `sources=1 → agreement 0.000, fc −0.0, targetQty 0` for **TSLA, META and
GOOGL** — a value only the ADR-0124 code produces. Corroborated names still carry all the conviction
(**NVDA `sources=3, agreement 0.834, fc −5.227`**, **GOOG `sources=3, agreement 0.826, fc −4.753`**).

## The new must-fix #1 — the book cannot get out of its own positions

`PositionBuffer.band()` sizes the no-trade region as `|target| × Forecast.TARGET_ABS ÷ |forecast| ×
buffer-fraction`. With `TARGET_ABS = 10.0` and this book's live `|combinedForecast|` running
**0.045–8.338**, that ratio inflates the band to **tens of shares against holdings of 2–13 shares**.
Recomputed in exact decimal from `/api/fusion/targets`:

| name | \|fc\| | target | held | band | \|gap\| | inside band? | live deltaQty |
|---|---|---|---|---|---|---|---|
| AMZN | 4.0065 | 70.4258 | −13.0 | **87.8884** | 13.00 | **yes** | 0.0000 |
| AAPL | 3.2970 | 78.4625 | −9.0 | **118.9907** | 9.00 | **yes** | 0.0012 |
| MSFT | 8.3379 | 75.1371 | −8.0 | **45.0573** | 8.00 | **yes** | 0.0210 |
| GOOG | 0.2142 | −2.0340 | −8.0 | **47.4879** | 5.97 | **yes** | 0.0000 |
| NVDA | 0.0450 | 0.5657 | −2.0 | **62.8966** | 2.00 | **yes** | 0.0000 |

**All five held names are inside their band, so `bufferedDelta` returns zero every cycle, indefinitely.**
GOOG proves this is not a stalled *entry*: target and holding are the same side — short 2.03 wanted
against short 8.00 held — and the desk cannot cut the difference. ADR-0118 diagnosed this exact trap and
wrote the escape (`isTrappedExit` → close in full), but the call sits inside
`if (gate != null && !gate.mayIncrease(...))`, and `jethro.fusion.edge-gate.enabled=false` (ADR-0122)
means that branch never runs. **The fix is in the source and unreachable in the configuration the desk
actually runs** — the second instance this week of a rule gated behind the disabled edge gate (rule 96,
`anyAdmitted`). These frozen names carry the loss: **AMZN −$42.33**, **MSFT −$26.49**, **GOOG −$22.81**
unrealized against a firm unrealized of **−$83.90**.

## Item #2 — ADR-0125 is committed, undeployed, and has no baseline

`ecf079c` (V48, ~12 new equities) was committed **18:54:55Z** by an out-of-band session, after the
**18:46:05Z** boot. It is not running: Flyway tops out at **version 47** and the boot log reads
`Alpaca real-time WS for 7 equities`. `.pending-baseline.json` still names **3e7817e4f (ADR-0124)**,
which scores next cycle — so if the app restarts first, V48's universe change lands inside ADR-0124's
window and the scorer will attribute it to ADR-0124. Flagged so next run discounts that row in words
rather than hand-editing anything.

## Attribution this window — honest split

The three names ADR-0124 silences did not trade, so **none** of the **−$26.18** is the change's. The
window's orders are a JNJ round trip (built 18:00–18:08, sold 25 at 18:10:34, **+$15.98** realized), a
JPM round trip (built 18:16–18:35, sold 28 at 18:37:27, **−$35.20** realized) and an NVDA round trip
(**+$37.40** realized) — build-then-dump whipsaws in a `CHOP` regime, roughly offsetting. The rest is
**−$83.90** of unrealized mark on the five frozen shorts — market, on positions the desk is structurally
unable to close. The gross rise of **+$154.97** is the ES hedge tracking (`status: ON-TARGET`,
`trackingRate 0.992506`), not added risk (rule 91).

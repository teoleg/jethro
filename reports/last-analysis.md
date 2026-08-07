The desk is locked flat with the market OPEN and 20 live forecasts, because 19 of 20 names are reduce-only: their risk-cut σ sensor cannot warm across the overnight session gap, so their trailing stop is unarmed and ADR-0126 forbids any increase.

**No code change this cycle.** `403a95ffd` (ADR-0144) is at **1/6** under ADR-0116 and
`reports/.pending-baseline.json` is present — `scripts/score-change.py score` prints
`403a95ffd still accumulating evidence (1/6 cycles) — held, not scored this run`. Piling a change on top
would destroy its evidence window. The register is updated and the #1 target for next cycle is set.

---

## Step 0 — verifying `403a95ffd` (ADR-0144): ⚠️ **DEPLOYED AND CORRECT, BUT NOT YET EXERCISED**

It **deployed**: the commit is stamped `09:45:23 -0400` and the JVM's `uptimeSeconds` **838** against the
report clock puts boot at **09:46:11 -0400**, 48 s later — the restart gate rebuilt on it, as it should
for an `app/` path.

It has **not run once**. `PositionBuffer.uncorroboratedHold` requires `held.signum() != 0` — it only ever
holds a position the desk already has. Every name in `fusion_targets` reads `currentQty: 0`, gross is
**$0.00**, and the newest order in the window is **2026-08-06 20:17:40.939144+00**, before the change
existed. So its VERIFY-BY (the share of full exits carrying one source falling below all of them) has an
empty numerator and an empty denominator. **Not STILL-BROKEN, not VERIFIED — ungraded.** It stays #2 and
keeps its window; it can only be graded once the desk holds something again, which is exactly what the new
#1 blocks.

I checked the obvious regression risk and cleared it: ADR-0144 cannot be what is holding the book flat,
because its branch is unreachable at `held = 0`.

---

## Situation triage

**1. Money.** Total PnL **-$897.88**. Since last run **+0.00**; over the last three runs **+0.00**. Not
bleeding — frozen. The daily curve's last real move was **-628.07** (08-05) → **-897.88** (08-06), and
nothing since: `chore(status)` read `market-closed` from 08:30Z through 13:00Z.

**2. Risk.** Gross **$0.00** — **0.0%** of the $1,500,000 firm cap, headroom **$1,500,000**. Net **$0.00**
of the $1,000,000 net cap. `breaker.halted` **false**; `var95` **0.00** on `coveredExposure` **0.00**
(`note: no positions`). **DORMANT** — the opportunity, not the danger.

**3. Cause.** Not the change. See Step 0 — ADR-0144 never executed.

**4. Danger.** None. Zero exposure, breaker clear, nothing near a cap. The inverse applies: with the full
budget unused and the session open, staying flat is the failure.

**5–7. Order-level post-mortem and change-vs-market attribution.** The window contains **no orders at all**
— the last fill predates the change. PnL moved **+0.00**. The window is therefore **100% neither**: no
market move to attribute and no change effect to credit or blame. What the window *does* contain is the
reopen, and that is where the finding is.

---

## The finding: the σ warm-up cannot cross a session boundary, so the whole book is reduce-only

The market is open and the plan is healthy. `fusion_targets` reads `routing: true`, `instruments: 20`,
`edgeGate: null` (not gating), `portfolioRiskMultiplier: 1.0`, `riskCuts: []`, and real conviction —
BAC at `combinedForecast` **+16.105**, `sources: 2`, `targetQty` **4351.609784** on a price of **62.66**.
Marks are live: `ticksIn` **9734**, `ticksDropped` **0**, alpaca `lastUpdateAgeMillis` **179**.

And yet `BAC` reads `currentQty: 0, deltaQty: 0`. So does every other equity. `insideBuffer` is **19** of
**20**, and the `aims` map is **0.0** for all nineteen equities — only **NQ** carries an aim
(**0.033791**) and the plan's only non-zero delta (**0.018429**).

The chain, read end to end in the code:

1. `FusionLifecycle.stopArmed` is `streamVol == null || streamVol.sigmaPerSample(instrument).isPresent()`.
2. `fusion_targets.streamVolMeasuredNames` is **1**. So the stop is unarmed for 19 of 20 names — and the
   one measured name is, by inference, the one name that moved.
3. `PositionBuffer.mayIncrease` is false for an unarmed stop (ADR-0126), which clamps the order
   reduce-only **and re-seeds the aim to where the desk will actually be** — `aim = held + delta` = **0**.
4. Reduce-only on a flat position is nothing. The aim is pinned at zero, is written back at zero, and the
   name cannot leave flat. Nineteen times over.

**Why σ is cold, and why this recurs every session.** The warm-up logs say it exactly: `risk-cut σ sensor
still cold for BAC after seeding 30 of 121 stored prices — stopped on HISTORY_EXHAUSTED covering 932s in
1 read(s) at a 30000ms step`. Every equity terminates the same way, **22–46 of 121**, on `GAP_BREAK` or
`HISTORY_EXHAUSTED`, all covering roughly **900–1000 s**. That is the length of the live session so far,
not a defect in the store. `SensorWarmup.GAP_TOLERANCE_SAMPLES` is **30** consumption steps; at the σ
sensor's **30000 ms** step that tolerance is 15 minutes, and the overnight close is many hours — so the
replay stops dead at the session boundary, deliberately, rather than fabricating a jump across it. The
sensor then needs its full **121** prices at a 30 s step accumulated from live prints alone: about an hour
of continuous session.

So the desk is structurally unable to put risk on for roughly the first hour of **every** session, and any
restart inside the session restarts that clock from zero. The trend and reversion sensors truncate at the
same boundary (`145 of 193`, `82 of 241`, same `GAP_BREAK`/`HISTORY_EXHAUSTED`) — this is one bug class
across every mark-fed estimator, which is Rule 444's lesson arriving from the other side.

The pointed detail: `history_status` reads `days: 1574, instruments: 55, ready: true, source: "existing"`.
A rich daily history **is loaded and available** — the σ warm-up simply does not draw on it, only on the
intraday mark series that the overnight gap severs.

That is the new **#1**, and it is upstream of everything else in the register: while it holds, no position
exists, so ADR-0144 cannot be exercised, the venue-fee item has no turnover to measure, and the horizon
item has no holding period. Next cycle's one change targets it — at the seed, not at ADR-0126's gate,
which is correct policy and stays untouched.

**Also cleared, without action:** must-fix #3 (the volatility-regime baseline latching) reads
`volRatio` **0.86**, `regime` **CALM** this cycle, against **277215656.99** / **ELEVATED** last cycle. The
latch did not survive the restart — it was masked, not fixed. The mechanism stands and the item stays open.

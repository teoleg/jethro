Found the dormancy in code — the no-trade band is wider than the target, so the desk's delta is identically zero — but ADR-0139 is still being measured (3/6), so no code change this cycle.

*(Every figure below is read from `/api/risk`, `/api/orders`, `/api/fusion/targets`, `/api/signals/telemetry`,
`/api/social`, the app's own WARN stream, the named source files and the scored ledger. None is authored
here — invariant 7 / ADR-0016.)*

**Money.** Total PnL **-$603.08**, flat to the cent across the last three heartbeats (**+0.00** since last
run, **+0.00** over the last three). Gross **$0.00**, net **$0.00** — **0.0%** of the firm gross cap
$1,500,000, so **$1,500,000** of headroom sits unused. UNDERWATER and DORMANT; growth **0.0%** against the
**1.0%**/3-iteration target, `on_track=False`, `stale=True`.

**Risk.** No danger state: VaR **0.00** ("no positions"), breaker `halted: false`, regime CALM. The problem
is the opposite of danger — the book is not on. `orders_day.total` is **0** and the newest order in the book
is still **2026-08-04 21:00:47Z**, so the desk has now sat out three boots and a full session open.

**Cause — and it is not what I ranked #1 last cycle.** Last cycle blamed the warm-up seed's inability to walk
across the overnight close. That mechanism is now **confirmed** by a clean two-boot dose-response: equity
coverage moved **2011–2046 s → 3970–3999 s** when the boot moved **1674 s** later, tracking time-since-open
one-for-one, while the rates names — marked off a curve with no session hole — reach **241 of 241**. But
confirming it also **disproved it as the cause of the dormancy**: it self-heals as the session runs. This
boot the sensors *are* warm — `streamVolMeasuredNames` **3 → 20**, seeds now **207–233 of 241** — the planner
produced **22** real targets (PG **423.028615**, WMT **500.316417**, NVDA **208.639712**), and the desk
*still* placed nothing. Warm sensors, real targets, zero orders: the constraint is downstream of the sensors.

**It is the buffer, and the arithmetic is in the code.** `PositionBuffer.band` sizes the no-trade region as
`|target| × Forecast.TARGET_ABS / |forecast| × width`, with `TARGET_ABS = 10.0` (`Forecast.java:20`) and
`width = max(bufferFraction, min(1.0, 2C/μ)) ≥ 0.5` (`PositionBuffer.widthFor`,
`application.properties:313`). So `band ≥ |target| × 5.0 / |forecast|`, which is **wider than the target
itself** whenever `|forecast| ≤ 5.0`. ADR-0102's `withinTarget` clamps the aim into `[0, target]`, and every
held position is 0, so the no-trade region **contains the entire interval the aim is allowed to occupy** and
the delta is zero *unconditionally* — not "until the aim climbs", but always. Every planned forecast sits in
that region (strongest PG **4.270153**, then **4.102602**, **3.481492**), which is exactly why `insideBuffer`
reads **22 of 22** and every `deltaQty` is **0.0**.

**Why the dial is not the fix.** `buffer-fraction = 0.5` is **OLEG-SET 2026-07-21**, set when the ADR-0055
band was `|target| × fraction` — under which 0.5 means "half the target" and can never swallow it. ADR-0094
replaced the *base* of the fraction with the average position at a typical forecast without re-deriving the
owner's number against the new base, so the same 0.5 silently became "half the average position". I will not
re-set an owner's number to compensate for a base change he never saw; the fix is code that bounds the band
by the interval the aim can actually reach, shipping with its own ADR next cycle.

**Decision: no code change.** `scripts/score-change.py score` reports `d51f179a2` (ADR-0139) **still
accumulating evidence (3/6 cycles)** and `reports/.pending-baseline.json` still names it, so under ADR-0116 a
new change would destroy the evidence. ADR-0139's own mechanism ✅ verified again on this third boot
(**17** corroborations in **1377 s** of uptime, versus **18 in 64,010 s** pre-fix). Separately, `social`'s
expectancy swung a third time — **8.855736 → 4.837178 → 7.810160** bps on five additional resolved
observations — so the standing decision request to Oleg on `jethro.fusion.social.per-channel` stays open and
explicitly **NOT-YET-SUPPORTED**; that dial is the owner's and the loop does not touch it. Next cycle, once
ADR-0139 scores, the one change goes to must-fix **#1**, graded by `insideBuffer < instruments` and
`orders_day.total > 0`.

**Attribution.** No code change landed this window and no order was placed, so neither market nor change
claims the move — PnL is flat to the cent because the book is empty. This window is baseline.

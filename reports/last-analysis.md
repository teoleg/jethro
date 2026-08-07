# Last analysis — 2026-08-07 14:30Z

**No change — ADR-0144 is under measurement at 2/6, and the freeze bought a natural experiment that proves the σ warm-up, not conviction, is what decides whether the desk can trade.**

## Situation — the four questions, in numbers read from the live endpoints

**1. Money.** Total PnL **-$860.16**, up **+$37.71** on the last run and **+$37.71** over the last three
(the two prior cycles were flat at **-$897.88**). Still UNDERWATER. The book is not bleeding — but see the
attribution below, because that gain is not what it looks like.

**2. Risk.** Gross **$11,677.55** = **0.8%** of the $1,500,000 firm gross cap, headroom **$1,488,322**; net
**$11,677.55** = **1.2%** of the $1,000,000 net cap. `breaker.halted` **false**, `riskCuts` **[]**,
`edgeGate` **null**. The desk came **off dormant** this cycle — that is the goal, and with 99.2% of the cap
unused, rising exposure is the intended direction, not a concern.

**3. Cause.** `403a95ffd` (ADR-0144) is at **2/6** cycles — `still accumulating evidence`, unscored, so the
freeze holds. Its precondition became reachable for the first time (a non-zero held position exists now),
but it has **not fired once**: no exit occurred in the window, and the last `fusion exit — target decayed to
flat` is stamped **2026-08-06 20:17:37**, before the change existed. Verdict: **ungraded** — neither
VERIFIED nor STILL-BROKEN.

**4. Danger.** None. Not bleeding, nowhere near the cap or the breaker.

## What the window actually shows

Nothing changed since last cycle, yet the desk went from **$0.00** gross to a live position. The reason is
the clock, and it confirms must-fix #1 causally rather than by inference:

| read | 14:00Z | 14:30Z |
| --- | --- | --- |
| `ops_jvm.uptimeSeconds` | 838 | 2638 |
| `streamVolMeasuredNames` | 1 | 2 |
| names with a non-zero `aim` | 1 | 2 |
| `insideBuffer` / `instruments` | 19 / 20 | 22 / 23 |
| `grossExposure` | $0.00 | $11,677.55 |

The mapping is **one-for-one**: exactly the names whose σ has warmed (`NQ` **0.038287**, `MSFT`
**-2.935877**) carry a non-zero aim; the other 21 read exactly **0.0**. They are not flat for want of
conviction — `AAPL` carries `combinedForecast` **-6.411632656835155** with `sources: 3` and `targetQty`
**-421.799922** against `deltaQty: 0`. σ warms only from live intraday prints (**121** of them at a
**30000 ms** step ≈ an hour of session) because `SensorWarmup` truncates at the overnight gap, while
`history_status` reads `days: 1574, ready: true` — deep history is loaded and the seed never draws on it.

**Attribution — the +$37.71 is credited to nothing.** Realized PnL got *worse*
(**-897.87616775 → -898.10895975**, the cost of entering). The whole gain is `unrealizedPnl`
**+37.94573000** on one `NQ` position of **0.019661** at `avgCost` **29600.75** against `mark` **29697.25**
— filled **14:23:43**, measured **14:30:03**, a **six-minute** holding period. That is noise with no
statistical content, on a position `403a95ffd` did not cause. Not a win for the change, and not market
movement on anything the desk was already holding — the other 21 positions are flat.

## Decision

Freeze holds, so no code change. Must-fix **#1 stays #1 and is graded STILL-BROKEN** — its own anti-clock
guard is what forces that: the rise from 1 to 2 measured names came from the session lengthening, not from
a fix, and grading it as progress would have been the Rule 433 error. A **new item #3** is recorded: the
passive re-plan cancels its own order faster than it can fill — **12** `NQ` orders in the window, **1**
FILLED, **11** CANCELLED by ADR-0084 re-plan on a ~30 s cadence, with the size ladder restarting from
near-zero after the fill while `aims.NQ` **0.038287** sits above a held **0.019661** and the forecast holds
steady at **5.476118838093129**. Next cycle's one change targets #1, at the σ **seed** — teaching it to
draw on the daily history already loaded — never at ADR-0126's reduce-only gate, which is correct policy.

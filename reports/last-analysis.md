Found why the desk goes blind after every restart — a sensor's warm-up cannot walk across the overnight session close — but ADR-0139 is still being measured, so no code change this cycle.

*(Every figure below is read from `/api/risk`, `/api/orders`, `/api/fusion/targets`, `/api/signals/telemetry`,
`/api/social`, the app's own WARN stream and the scored ledger. None is authored here — invariant 7 /
ADR-0016.)*

## Situation

1. **Money.** `/api/risk` `.total`: `totalPnl` **-603.08012889**, **+0.00** since the last run and **+0.00**
   across the last three. UNDERWATER, but inert rather than bleeding.
2. **Risk.** `grossExposure` **0.00000000**, `netExposure` **0.00000000** — **0.0%** of the firm gross cap
   $1,500,000, full headroom, breaker `halted: false`. That is **DORMANT**, which ADR-0132 calls a failure
   to attack. It is **not** the DANGER state: nothing is near a cap.
3. **Cause.** Last cycle's `d51f179a2` (ADR-0139) is **still unscored** — `reports/.pending-baseline.json`
   still names it and the ledger's newest row is still `629dbbdf8`. Per ADR-0116 the freeze holds for a
   second cycle, so **no code change**. Its *mechanism* re-verified on a fresh boot: `counters.corroborated`
   **17** in **1252 s** of uptime, against **16 in ~900 s** last boot and **18 in 64,010 s** pre-fix.
4. **Danger.** None. No exposure, no drawdown breaker, `regime` CALM, `volRatio` **0.92**.
5. **Orders.** Newest `recent_orders` row is **2026-08-04 21:00:47Z** — nothing placed across two restarts
   and a full session open. Nothing to attribute to a trigger, good or bad.
6. **Change vs market.** Neither claims this window: **+0.00** PnL over three heartbeats, zero exposure,
   zero orders. Baseline.

## What the cycle actually produced

**A code-traced cause for the dormancy, and it is an edge problem, not a trading one.**
`SensorWarmup.walk` (`app/src/main/java/io/jethro/app/fusion/SensorWarmup.java:224-257`) breaks its
backward walk on any gap wider than `step * GAP_TOLERANCE_SAMPLES` — and a scheduled US session close is
~16.5 h wide, so it is treated as a data outage. Boot was **14:09:10Z**; the session opened **13:30Z**,
**2350 s** earlier; and **every** equity seed terminated in the band **2011 s – 2046 s** of coverage —
`trend` JNJ **173 of 193**, `reversion` HD **113 of 241**, risk-cut σ PG **53 of 121**. In the *same boot*,
the rates names, whose marks come off a continuously-refreshed curve with no session hole, are the only
ones to reach **FULL**: **241 of 241**, covering **11331 s**. Same code, same store — the only difference
is session continuity. Downstream: `insideBuffer` **21 of 21**, `streamVolMeasuredNames` **3 of 21**, and
every planned row at `deltaQty: 0` while `targetQty` is large (HD **318.360826**, PG **589.883741**).
This ranks **#1** because a silent sensor publishes no forecast and therefore logs no `signal_observations`
— on a ~30-minute restart cadence the loop has been measuring every source on an evidence base its own
restarts thin out. One honest caveat carried into the VERIFY-BY: `trend` needs **193** samples in **965 s**
of span yet seeded **173** inside **2038 s**, so print density also binds for some names; bridging the hole
is necessary but may not be sufficient.

**The decision request escalated to Oleg last cycle is DOWNGRADED to not-yet-supported.** At the same
endpoint, horizon and cohort count, `social`'s expectancy moved **+8.855736 → +4.837178** bps and
`stdCohortMeanBps` rose **26.863 → 35.424588** on **one** additional resolved observation (**373 → 374**).
An estimate a single print moves that far is not a basis for asking the owner to unlock a money dial. The
ask stays open and untouched — the dial is the owner's — but it should be re-offered only after social
holds its sign and magnitude across several independent cycles.

Meanwhile nothing the desk is *permitted* to size beats its own cost: `trend` **+1.192832** (98 cohorts),
`reversion` **+0.384366**, `xsreversion` **-2.213774**, `momentum` **+6.549253** on only **7** cohorts —
against **1.009** bps/side of fee plus **~0.75** bps of slippage. So the buffer holding the book flat is
currently saving money, and un-dormanting the desk stays ranked *below* fixing the blindness and the edge.

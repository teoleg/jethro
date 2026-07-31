The desk is not churning a few cold names — it liquidates its ENTIRE book to exactly flat at every loop teardown and rebuilds from zero, so it can never hold a position as long as the 3600s horizon its own edge is measured over; a second hold cycle spent on measurement turned that from hypothesis into proof.

*(Every figure below is read from `/api/risk`, `/api/ops/jvm`, `/api/attribution`, `/api/hedging`,
`/api/signals/telemetry`, `logs/report.md`, `logs/jethro-app.log`, the scorer's snapshot, or a SQL
aggregate over the `fills` table. None is authored here — invariant 7 / ADR-0016.)*

# HOLD at 3/6 — no code change this cycle

`python3 scripts/score-change.py score` prints
`score: 4f67f0515 still accumulating evidence (3/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present (commit `4f67f05158a4b7cf159180250589c7b48a0ebc59`,
`ts` `2026-07-31T16:35:59Z`). Per the ADR-0116 hold rule a new change now would destroy the evidence on the
revert under measurement. The cycle went into measurement instead — and it upgraded the register's top item
from a hypothesis to a proof, and corrected it.

## Situation triage

1. **Money.** SITUATION header: total PnL **$72.70**, **-33.20** since last run, **-145.34** over the last
   3. Heartbeat `2026-07-31T17:38:42Z`: `pnl_growth_pct` **-79.14** vs `pnl_target_pct` **1.0**,
   `on_track` **false**, `stale` **true**, `underwater` **false**. Off target and down on the window.
2. **Risk.** Gross **$16,105.99** = **1.1%** of the $1,500,000 firm cap, headroom **$1,483,894**; net
   **$5,013.99** = **0.5%** of the $1,000,000 net cap. `Flags: none` — not near a cap, not near the
   breaker. The book is badly *under-deployed*, and the reason is the defect below.
3. **Cause.** The pending change is the manual completion of the failed auto-revert of the ❌ BAD ADR-0133.
   It is **✅ VERIFIED deployed and landed**; its effect is at 3/6 and is the scorer's to grade, not mine.
4. **Danger: NO.** Not bleeding at the cap, not near the breaker.
5. **Change vs market.** Neither. This window's move is *mechanical* — see below.

## Step 0 — last cycle's change (`4f67f0515`): ✅ VERIFIED deployed, ⏳ effect under measurement

`/api/ops/jvm` `uptimeSeconds` **1283** against `/api/risk` `asOfMillis` **1785520826830** puts boot at
**2026-07-31T17:39:03Z**, well after the 16:35:50Z revert commit — the running code is the reverted code.
`docs/adr/0133-*.md` reads `**Status:** Reverted` and `PositionBuffer` carries no band cap.

## What the hold cycle found

**The whole book was liquidated to exactly flat at the loop teardown, then rebuilt from zero.** Summing
every LIVE fill executed before the 17:39:03Z boot, ALPHA nets **0.000000** across **21** names over
**1687** fills — not "mostly reduced", exactly flat. The minute `17:38` — 2s after the heartbeat, 19s
before the boot — did **11** fills and **$99,922.58** of turnover against the heartbeat's recorded gross
**99920.63500000**: a round trip of essentially the entire book in one 500ms burst. The new process rebuilt
to gross **16111.97500000** by 18:00Z. Same signature one cycle earlier: minute `17:12`, **$81,499.01**
against a whole-book gross of **81647.96500000**.

Across today's 9 loop cycles since 13:52:55Z, the boot/teardown windows hold **56** fills — 12.5% of fills —
but **$372,487.16** = **24.17%** of turnover and **$37.33** = **23.95%** of the fee bill, against **392**
fills / **$1,168,829.38** / **$118.53** outside them. That mechanical quarter of the cost stands against a
firm total PnL of **76.28509719** and `totalFees` **261.125419** (ALPHA `feesPaid` **254.804452** on
`totalPnl` **-48.08228860**).

**It is not a data bug.** At one instant `/api/risk` `positions` and `sum(BUY − SELL)` over `fills` agree to
the share on all five holdings (MSFT **20.000000**, PFE **-221.000000**, BAC **68.000000**, GOOG
**1.000000**, ES **-0.053455**). Invariant 3 is intact; the liquidation was real trading, not a projection
wiped by a restart.

**The decisive argument is horizon, not fees.** Every source in `/api/signals/telemetry` publishes
`horizonSeconds` **3600** while the loop round-trips the whole book every ~30 minutes. The desk is
structurally incapable of holding a position as long as the horizon over which its own expectancy is
measured — reversion **+4.851871026828735** (63 cohorts), social **+5.298898880312067** (22). No sizing,
fusion or hedge work can realise an edge whose holding period is halved before it pays. So the register is
re-ranked: this defect is now **#1**, above the scorer's revert bug, which has a proven manual workaround
already executed by hand twice.

**One correction to the standing diagnosis.** The flatten fires at **teardown**, 26 minutes into a healthy
process — *not* at boot. Cold sensors are the sequel (the rebuy), not the cause, and no shutdown-flatten
hook exists in the source. Next cycle's job is to find what collapses all 21 fusion targets at once. One
dead end is already closed: a full re-seed does **not** warm a sensor — `ReversionForecastLifecycle` logs
*"still cold … after seeding 241 of 241 stored prices"* — so "seed harder / seed more often" is refuted in
advance; warm-up is wall-clock against process lifetime.

## What I am watching next cycle

The scorer's verdict on `4f67f0515` at 6/6, and whether the next cycle repeats the teardown flatten (re-run
the per-minute `fills` grouping around the next heartbeat, and check ALPHA's pre-boot net). The one change,
once the hold lifts, targets register item #1.

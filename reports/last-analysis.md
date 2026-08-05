The desk's only source with cost-beating measured expectancy was gagged by a boolean the platform never sets — the author credibility floors are now what decides, so social can reach the corroboration gate at all.

*(Every figure below is read from `/api/risk`, `/api/fusion/targets`, `/api/signals/telemetry`, `/api/social`,
`/api/tca`, `/api/discovery`, the app's own boot log, the scored ledger or a live read of the same
StockTwits endpoint the app polls. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — verify last run's change first

**`629dbbdf8` (ADR-0138) SCORED ⚠️ INCONCLUSIVE, and its mechanism is ✅ VERIFIED.** The scorer's row:
risk-adjusted return **+0.001718**/cycle over 37 cycles, **t = +1.00** against the 1.5 hurdle → kept, not
reverted (ADR-0116).

The defect-level verification is unambiguous, and for once it is graded from the app's own log rather than
a replication script — which is what ADR-0138 shipped the terminator line for:

| VERIFY-BY | reading at the **2026-08-04T19:43:11Z** boot | verdict |
| --- | --- | --- |
| zero `sensor still cold` WARNs for a name with stored marks | the twelve equities cold at **every** prior boot (JPM 137/193, BAC 192/193, NEE 171/193, JNJ, CAT, GOOG, MCD, HD, PFE, CVX, KO, PG) are **absent** from this boot's cold list | ✅ |
| any remaining short seed names its terminator + span | every one does: rates names `FULL` at **241 of 241** / **193 of 193**; AMD `HISTORY_EXHAUSTED` at **15 of 193** covering **19880s** at a **586000ms** step; NFLX **10 of 193**, PLTR **20**, META **41**, GOOGL **24**, AUDUSD `NO_HISTORY` — every survivor is a genuinely thin series, not a short window | ✅ |
| no `sources ≤ 1` exit within five minutes of JVM start | boot **19:43:11Z**; first post-boot order **20:10:10Z** — **27 minutes** later. Against five prior boots each followed ~40 s later by a liquidation | ✅ |

Item #1 has carried the register for six cycles and it closes. `026cda49d`'s flagged auto-revert stays
**deliberately not completed** (Rule 303): it is itself the revert of the graded-BAD ADR-0136.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **-$603.08**, unchanged on the run and over the last three (**+0.00**) — the US
   session was closed for every heartbeat since, so the tape was frozen. Growth **0.0%** against the
   **1.0%** target: `on_track=False`, `underwater=True`. Fees are **$398.00** of the **$603.08** deficit.
2. **Risk.** Gross **$0.00** — **0.0%** of the $1,500,000 firm cap, **$1,500,000** of headroom; net
   **$0.00**. **DORMANT**, which under ADR-0132 is a failure to attack, not safety.
3. **Cause.** Not last cycle's change. The window's gross move **$52,192.44 → $0.00** is the cash-close
   collapse: at **20:10:10Z** — **600 s** after the 20:00Z equity close, exactly the
   `jethro.fusion.freshness-seconds=600` window — eight names exited within 200 ms of each other on
   `fusion exit — target decayed to flat [forecast=±0.0, sources=1]`, then GOOG, AAPL, NVDA, AMZN and
   MSFT as each name's last print aged out. That is the ADR-0135 breadth collapse, whose fix was graded
   ❌ BAD and is not re-tried.
4. **Danger.** None. Bleeding, but with 100% of the gross cap unused — the opposite of the DANGER state.
5. **Order-level post-mortem.** Every FILLED order in the window carries a `sources ≤ 1` exit reason; the
   rest are HEDGE ES orders REJECTED on `market session is closed — only risk-reducing orders allowed
   (ADR-0115)`. No trigger opened a position this window; the desk only closed.
6. **Change vs. market.** Cleanly separable and it is **all baseline behaviour**: the JVM ran **64,010 s**
   without restart, no code changed since `629dbbdf8`, and the PnL move over the last three heartbeats is
   **+0.00** with a frozen tape. Nothing here is credited or blamed on code.

## Diagnosis — where the money actually is

With item #1 closed I re-ranked the register against the standing priority (2026-07-28: work on EDGE, and
if a source's measured expectancy is positive and significant net of cost, the work is to **let it size**).

**Measured cost, from the app's own fills:** `$39.187210` of fee on `$388,348.116512140580` of notional =
**1.009 bps** per side, plus **~0.75 bps** of slippage per fill on the liquid names (`/api/tca`: GOOG
`0.744`, NEE `0.753` avg bps) — call it **~3.5 bps** round trip.

**Measured expectancy, `/api/signals/telemetry`, cohort-clustered t at the 3600 s horizon:**

| source | mean bps | cohorts | t | in the live plan? |
| --- | --- | --- | --- | --- |
| social | **+8.856** | 37 | **+2.01** | **no — 0 of 9 names** |
| momentum | +6.549 | 7 | +0.63 | no |
| trend | +1.540 | 98 | +0.83 | yes |
| reversion | -0.286 | 86 | -0.13 | yes |
| xsreversion | **-5.006** | 44 | -1.16 | yes |

`social` is the only source positive at **all three** horizons (+8.856 / +1.950 / +0.508 bps) with a hit
rate that rises with horizon (0.619 / 0.588 / 0.509) — the shape of a slow news signal, not noise. It also
already carries the **highest** fusion weight, **1.5768**. And it contributes to **none** of the nine
planned names, while the three sources that *are* sizing the book are exactly the three that do not beat
their own trading cost. That is the whole problem in one line.

**Why social cannot speak — traced to a single boolean.** `/api/social` counters: **153,207** ingested,
**5,289** kept, **18** ever corroborated. The ADR-0050 §3 gate promotes only on ≥ **2 distinct credible**
channels, and neither wired source can supply one:

- `SocialChannels.isCredible` required `verified() && followers ≥ 5,000 && ageDays ≥ 180`, and the
  StockTwits adapter populates `verified` from `user.official` — which marks StockTwits' **own** corporate
  accounts. A live read of the same endpoint the app polls (`streams/symbol/AAPL.json`, 30 messages)
  returns `official: false` for **all 30** authors, while `followers` spans **-2 … 5,978** with one author
  **above** the 5,000 floor and join dates back to **2018** well past the 180-day floor. The conjunction
  short-circuits before either floor is read: **no organic post has ever been credible.** The class's own
  javadoc promises the opposite — "unknown accounts are judged by the author FLOORS below".
- News can supply only one: of six wired outlets, only Yahoo resolves single-name tickers — all 24
  discovery candidates, over two days and up to 206 mentions, carry `sources: [news:yahoo]` and nothing
  else.

One credible channel against a threshold of two is unreachable. The gate was not filtering noise; it was
switched off.

## The change — ADR-0139

Platform verification and the two measured author floors become **alternative** credentials for a STANDARD
channel rather than a conjunction: `verified || (followers ≥ floor && ageDays ≥ floor)`. Both floors keep
their configured values, `k = 2` is unchanged, the `manipulationSuspected` pump tell is unchanged, and the
change is strictly one-way — nothing credible today becomes non-credible. No money, risk or exposure
number is introduced or altered; this decides only whose mention counts as independent evidence. The
deterministic floor — edge gate, conviction floor, pre-trade guardrail, drawdown breaker, ADR-0086 cut —
is untouched, and ADR-0049 still forbids a social subject from originating an order.

**What I deliberately did not do.** Lower `corroboration-channels` to 1 (that *is* the adversarial
control, and at k=1 one account promotes a subject). Re-try ADR-0135's "hold through a breadth collapse"
(graded ❌ BAD). Widen deployment on trend/reversion/xsreversion to fill the dormant book — they measure
below cost, and forcing trades that lose money is not a fix for dormancy.

**The honest caveat, stated up front.** Social's edge rests on few *independent* episodes: 18 corroborations
in the source's life, re-emitted each cycle, which is why 373 resolved calls sit in only 37 cohorts. A
`t = +2.01` on that base is suggestive, not established, and broader coverage may dilute it. That is the
reason to ship it: the ADR-0049 telemetry and the ADR-0064 edge gate will now measure social on a real
sample and shrink its weight if the edge was an artifact — the same "earn your weight before you size"
path any source takes.

**VERIFY-BY next run:** `/api/social` `counters.corroborated` above **18**; at least one `signals[]` row on
a **tracked** name with `channels ≥ 2`; and `social` present in the `contributions[]` of at least one
`/api/fusion/targets` row — none of which has happened to date.

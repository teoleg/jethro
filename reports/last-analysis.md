ADR-0139 worked — organic social authors are credible again and a tracked name cleared the corroboration gate for the first time — but social still cannot size because an explicit owner decision, not a bug, holds it at zero; no code change while last cycle's fix is still being measured.

*(Every figure below is read from `/api/risk`, `/api/orders`, `/api/fusion/targets`, `/api/signals/telemetry`,
`/api/social`, the app's own config provenance and the scored ledger. None is authored here — invariant 7 /
ADR-0016.)*

## Situation

1. **Money.** `/api/risk` `.total`: `totalPnl` **-603.08012889**, unchanged **+0.00** since the last run and
   **+0.00** across the last three. The book is UNDERWATER but not *bleeding* — it is inert.
2. **Risk.** `grossExposure` **0.00000000**, `netExposure` **0.00000000** — **0.0%** of the firm gross cap
   $1,500,000, full headroom. That is the **DORMANT** flag, and per ADR-0132 it is a failure to attack, not
   safety. It is not the DANGER state: nothing is near a cap and no drawdown breaker is in play.
3. **Cause.** Last cycle's `d51f179a2` (ADR-0139) is **not yet scored** — `reports/.pending-baseline.json`
   exists and the ledger's newest row is still `629dbbdf8` (⚠️ INCONCLUSIVE, kept). The flat PnL is claimed
   by nothing: the JVM booted **13:50:39Z**, and the newest row in `/api/orders` is `createdAtMillis`
   **1785877247147** (**2026-08-04T21:00:47Z**), the MSFT leg of the cash-close liquidation. **No order has
   been placed in ~17 hours, across a restart.** Market conditions and my change both account for nothing
   here — this window is pure baseline.
4. **Danger.** None. Flat book, full headroom, no breaker.

## Step 0 — did ADR-0139 do what it claimed?

The running JVM booted immediately after last cycle's commit, so it *is* the ADR-0139 build. Two of the
three VERIFY-BY checks pass outright: **4 organic StockTwits authors now read `credible: true`**
(`cubie`, `dojidad`, `Etrading`, `peloswing`) where the pre-fix live read returned `official: false` for
**30 of 30** and no author could ever qualify; and **AAPL reached `channels: 2`, `BULLISH`,
`manipulationSuspected: false`** — a tracked name clearing the corroboration gate, which had never happened
in the register's history. The corroboration counter reads **16**, which looks *below* the old threshold of
18 until you notice it is an `AtomicLong` on `SocialLifecycle` reset at boot: the honest comparison is the
rate — **16 in ~900 s** now versus **18 in 64,010 s** at the previous boot. The mechanism is verified.

## The correction that matters

The third check — `social` in `/api/fusion/targets` `contributions[]` — reads **0 of 21**, and I am
**retiring it rather than fixing it**. `jethro.fusion.social.per-channel=0` carries its provenance in the
config itself: *"ADVISORY-ONLY ENFORCEMENT (Oleg, 2026-07-27) … RESTORES ADR-0049 ('a social subject can
NEVER originate an order')"*. With `perChannel = 0`, `SourceForecasts.fromSocial` yields exactly `0.0`,
`Forecast.hasView()` is false, and the registry excludes social **by construction**. There were always two
gates in series and last cycle's register saw only the first: ADR-0139 opened the one that was a genuine
defect; the second is an accepted owner decision, which the loop does not re-litigate. Social's measured
edge is unaffected either way — `signalTelemetry.record("social", …)` runs independently of the sizing dial,
so the telemetry stays honest while the source is sized at zero.

That converts must-fix #1 into a **decision request for Oleg, one concept only**: *may a corroborated social
signal contribute a sizing forecast — restore `jethro.fusion.social.per-channel` from 0 to 4.0, still behind
the ADR-0049 OOS edge gate?* Supporting evidence at the 3600 s horizon: `avgReturnBps` **8.855736**,
`resolved` **373**, `hitRate` **0.619**, `cohorts` **37**, `stdCohortMeanBps` **26.863**, positive at all
three horizons, highest fusion weight **1.5850**. The loop takes no action on it.

## Decision this cycle: no change

Two independent reasons, either sufficient. First, the contract: `d51f179a2` is still accumulating evidence,
and stacking a change on top destroys its attribution. Second, with social owner-gated to advisory-only,
**every source the desk is allowed to size on measures below its own trading cost** at 3600 s — `trend`
**+1.2373** bps (`cohorts` 97), `reversion` **-0.5929**, `xsreversion` **-3.6159**, and `momentum`
**+6.5493** on only **7** cohorts is far too thin to act on — against **1.009** bps/side of fee plus
**~0.75** bps of slippage per fill. Deploying harder into those to cure the dormancy would be a
forecastably losing trade. The honest next move, once `d51f179a2` is scored, is the one the standing
priority names: build and validate a **new** predictor through the ADR-0049 OOS gate, not another fusion
re-weight.

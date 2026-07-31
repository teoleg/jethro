Last cycle's "sign-inverted hedge" un-flipped on its own with nothing fixed — one name's position moved — while the real defect got worse: the hedge now sees 6.5% of the systematic risk it is meant to neutralise, and ADR-0133 is at 5/6 so no change was made.

*(Every figure below is read from `/api/risk`, `/api/hedging`, `/api/marks`, `logs/report.md` or the
live Postgres. None is authored here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** SITUATION header: total PnL **$457.39**, **-74.15** since last run and **-283.28** over
   three. Live `/api/risk` `.total` minutes later: `totalPnl` **457.61324815** = `realizedPnl`
   **605.62787143** + `unrealizedPnl` **-148.01462328**. Heartbeat `2026-07-31T15:41:42Z`:
   `pnl_growth_pct` **-28.04** vs `pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**,
   `underwater` **false**. Off target and bleeding mildly.
2. **Risk.** Gross **$124,652.20** = **8.3%** of the $1,500,000 firm cap, headroom **$1,375,348**; net
   **-$86,242.18** = **8.6%** of the $1,000,000 net cap. **Flags: none.** Gross rose **+50,996.19**
   run-over-run — with this much headroom that is the book deploying, which is the objective, not a
   concern.
3. **Cause.** Last cycle's change (`e61c7f5aa`, ADR-0133 band cap) is **held, unscored** —
   `scripts/score-change.py score` prints `still accumulating evidence (5/6 cycles)` and
   `reports/.pending-baseline.json` is present, so per the pending-baseline rule **no code change was
   made this cycle**. Deployed: fresh boot, `uptimeSeconds` **1078** at report generation and **1127**
   on a later direct read.
4. **Danger: no.** Bleeding, but at 8.3% of the gross cap with the breaker untripped and
   `/api/marks/quarantined` **[]**, this is not the DANGER state. De-risking here would be the
   status-quo trap CLAUDE.md names.

## What I verified

**Item #1 — ⚠️ STILL-BROKEN, and materially worse.** Live `/api/risk` positions × the live
`hedge_beta` rows: equity net **-89,268.235**, beta-covered net **-5,458.75**, **uncovered net
-83,809.485 = 93.9% of |net|** (last cycle: 71.3%). The hedge sizes off **Σ βᵢ·Eᵢ = -5,786.42**
against a **-89,268.24** book — it can see about **6.5%** of the systematic risk it is meant to
neutralise. The sign is negative again, i.e. *correct*, but nothing was fixed: NVDA's net went
**+10,371.04 → -393.20**, and that one name flipping is the entire difference. The sampling error
un-flipped by luck, which is exactly why coverage — not sign — is the thing to track.

**Item #2 — ⚠️ STILL-BROKEN, and now a harder failure.** Last cycle ES had a *frozen* mark; this cycle
ES is **absent from `/api/marks` entirely** (**36** marks returned, no ES among them). The HEDGE
book's ES position reads `hasMark` **false**, `mark` **0.00000000**, `markAgeMillis` **-1**,
`grossExposure` **0.00000000** — so the held hedge contributes **$0** to the firm total the loop
optimizes. `/api/hedging` EQUITY axis: `status` **WARMING**, `targetProxyQty` **null**,
`covarianceReady` **false**, on `netExposureUsd` **-87,968.53**. Sharpened this cycle: **NQ is priced**
(`providerTimestampMillis` **1785512939000**) and is already in the configured fallback list
(`jethro.hedge.equity-proxy-candidates=ES,NQ`), and `HedgeAdvisor.candidates()` does filter candidates
by live price — yet the axis still pins `proxyId` **ES** and sizes nothing. So the fallthrough exists
but is not yielding a candidate, with `covarianceReady` **false** as a co-blocker.

**Not a defect — discarded before it cost a cycle.** The report's Postgres log carries
`column "hedge_beta" does not exist` (15:30:46Z). That is a *past loop cycle's own* ad-hoc psql query
against an EAV table (`instrument_attributes` is `instrument_id | name | value`), the same class as
`relation "instrument_attribute" does not exist` at 15:06:33Z. No app code queries a `hedge_beta`
column.

## Attribution — market vs change

No change was made this cycle, so nothing is attributable to one. The desk is net short equity
(**-$87,970.89**) into a firm tape, and `unrealizedPnl` **-148.01462328** against `realizedPnl`
**605.62787143** is that mark-to-market: **market, not mechanism**. The gross rise is the ADR-0133 band
cap doing what it was built to do — still the scorer's call at 6/6, not mine.

## Next cycle

Once ADR-0133 scores, item #1 is the change: derive per-name betas **in code** from the durable mark
history (stated estimator, cited convention — never hand-authored, per the `β=1.0` lesson in
CLAUDE.md), and make the axis refuse to claim neutrality while coverage is partial. Item #2 stays
ranked *below* it: the dead proxy price is currently the only thing preventing a 6.5%-coverage hedge
from being traded, so restoring the price first would convert a passive gap into an active anti-hedge.

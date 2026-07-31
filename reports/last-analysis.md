The loop's own 30-minute redeploy is making the desk liquidate and re-buy names whose sensors cold-start — 3.09x the steady-state turnover for no change of view — and I found it by spending a mandatory hold cycle on measurement instead of waiting.

*(Every figure below is read from `/api/risk`, `/api/ops/jvm`, `/api/attribution`, `/api/hedging`,
`/api/signals/telemetry`, `/api/fusion/targets`, `logs/report.md`, `logs/jethro-app.log`, the scorer's
snapshot, or a SQL aggregate over the `fills` table. None is authored here — invariant 7 / ADR-0016.)*

## No change this cycle — the ADR-0116 hold

`python3 scripts/score-change.py score` prints `score: 4f67f0515 still accumulating evidence (2/6
cycles) — held, not scored this run`, and `reports/.pending-baseline.json` is present. Shipping now would
destroy the evidence on the pending revert, so this cycle is read-only by contract.

## Situation

**Money.** Total PnL **$122.48**, **-137.21** since last run and **-385.26** across the last three. The
heartbeat `2026-07-31T17:11:45Z` reads `pnl_growth_pct` **-51.14** against `pnl_target_pct` **1.0**,
`on_track` **false**, `stale` **true**. Down this run and down over the window — off target, not flat.

**Risk.** Gross **$81,646.13** — **5.4%** of the $1,500,000 firm cap, headroom **$1,418,354**. Net
**-$153.91**, **0.0%** of the $1,000,000 net cap. `Flags: none`. Deployed with room: neither the DANGER
state nor DORMANT.

**Cause.** The pending change (`4f67f0515`, the manual completion of the failed ADR-0133 auto-revert) is
**verified deployed** — `/api/ops/jvm` `uptimeSeconds` **1111** against `asOfMillis` **1785519050573**
puts boot at **17:12:19Z**, confirmed by `logs/jethro-app.log` `Starting JethroApplication … PID 334284`
at `13:12:20.633-04:00`, and `docs/adr/0133-*.md` reads `**Status:** Reverted`. Its *effect* is the
scorer's call at 6/6, not mine.

**Danger: no.** Not bleeding at a cap, breaker not near.

## What the window's money actually did

Over 17:00Z → 17:31Z, `realizedPnl` went **309.27313292 → 283.29807851** while `totalFees` went
**229.638225 → 248.070383**. The realized leg fell by *less than the fee bill grew* — the desk's
directional result was roughly flat and **transaction cost is what moved the number.** `unrealizedPnl`
went **-7.47426487 → -159.41954124** on a book whose net moved **-33,407.76400000 → -152.51500000**; the
book was re-planned inside the window, so that swing **cannot be cleanly split** into market vs mechanism
from these numbers, and I am not going to guess a cause for it.

## The finding — a cost that is entirely self-inflicted

The order tape pointed at the boot. The 17:12 minute — **39 seconds after the app restarted** — fired BAC
**BUY 441**, CAT **BUY 13**, JPM **SELL 77**, JNJ **SELL 54**, PG **BUY 1**, all FILLED, and CAT had been
**SOLD 12** at 17:11:30: a full reversal in **88 seconds**, across the reboot. That single minute did
**$81,499.01** of turnover against a whole-book gross of **$81,647.97**.

It is not a one-off. Grouping today's `fills` by minute and taking each of the 8 heartbeat times after
13:50Z as a boot (window `heartbeat … +2min`): post-boot minutes did **$277,330.06** of turnover over 11
minutes — **$25,211.82/min** — against **$1,144,024.38** over 140 other minutes, **$8,171.60/min**. A
**3.09x** ratio, and post-boot minutes are **9.9%** of fills but **19.5%** of turnover, so the post-boot
orders are much larger than normal — the signature of whole-position liquidations, not the ADR-0080
incremental path.

The mechanism is confirmed in the source, and it is three sound decisions composing into a bad one:
`FusionPlanner.plan` (ADR-0065) gives a held name with no fresh view an *implicit target of zero*;
`PositionBuffer` (ADR-0090) snaps a flat target's aim to zero and **does not buffer an exit**; and the
trend / reversion / σ sensors cold-start on every boot (this run's log still shows them cold for UNH,
EURUSD, META and GOOGL). So a name whose sensor has not finished re-seeding is read as *"the desk wants to
be flat"*, liquidated unbuffered, and bought back minutes later when the sensor warms. ADR-0065 cannot
tell *no view* from *not yet warm* — a condition the loop guarantees every 30 minutes. The boot schedule
is uncorrelated with the tape, so none of that turnover is a response to the market.

## What I did instead of changing code

Re-ranked `reports/must-fix.md`. This is the new **item #2**, absorbing the old #4 (BAC round-trip churn),
which it explains. **Item #1 stays the scorer's non-conflict-proof auto-revert** (`score-change.py:357`
still plain `git revert --no-edit`, and `run-status.json` still carries `"revert_failed": true`) — it is
armed *right now*, because the change under measurement is itself a revert, and it is a non-trading fix
that cannot perturb the live measurement. Item #3 (conviction concentrated in OOS-rejected names) is
unchanged at `tradable` **15** of `measured` **31**; note the fusion weights already lean on the two
positive-expectancy sources (reversion **1.6796828791749714**, social **1.5894668077730054**), which is
further evidence the combiner is not the lever. Item #4, the hedge overlay, **regressed** — `/api/hedging`
`covarianceReady` **false** and the EQUITY axis has fallen from STRUCTURAL to `status` **WARMING** with
`targetProxyQty` **null**.

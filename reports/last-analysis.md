No change this cycle — ADR-0133 is under measurement at 1/6 — and the reason the desk is flat is now pinned: it liquidates its whole book 19 seconds after every boot and then cannot rebuild, because the σ-cold veto holds all 19 names reduce-only until the sensor warms ~10–40 minutes later.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Why there is no change this cycle

`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (1/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Last cycle's change is being measured;
stacking a second one on top would destroy the evidence. So this cycle is diagnosis only.

## Situation

**Money.** `/api/risk`, read twice (14:00:46 and 14:04:24): total PnL **$738.64968836**, unchanged between
the reads. The 13:52:55Z heartbeat recorded **$731.92411373**, so PnL moved **+6.73** across the window.
`pnl_growth_pct` **-6.15** against `pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**. Not
bleeding, but well off the growth target.

**Risk.** Gross **$0.00000000**, net **$0.00000000** — **0.0%** of the $1,500,000 firm cap, headroom
**$1,500,000**; 0.0% of the $1,000,000 net cap. Flag **DORMANT**. This is a real flat book, not a marking
artefact: every book's `unrealizedPnl` in `/api/attribution` is **0.00000000** (ALPHA, HEDGE, MACRO) and
every `currentQty` in `/api/fusion/targets` is 0. The heartbeat 8 minutes earlier had gross
**$17,942.67878750**. Nowhere near a cap or the breaker — the opportunity case, not the danger case.

**Cause.** The book did not decay to flat; it was **liquidated in one step**. The only orders after the
process booted are five, all FILLED at 13:53:49 — 19 seconds after start (`ops_jvm.uptimeSeconds` **412**
at report generation ⇒ boot ≈13:53:30): JPM SELL 4, AAPL SELL 9, AMZN BUY 17, GOOG BUY 7, MSFT BUY 7.
Since then, ~20 re-plans have produced nothing.

**Danger.** None. Not bleeding, not near a cap, breaker not tripped.

## What the telemetry showed

I sampled `/api/fusion/targets` four times (`atMillis` 1785506422370 / 1785506573423 / 1785506603620 /
1785506663999). `insideBuffer` read **19/19, 19/19, 18/18, 19/19** — but the interesting field is
`aims`: **every one exactly 0.0**, with every `currentQty` 0 and every `deltaQty` 0, against targets as
large as XOM **-737.502983** and NEE **1181.951104**. A zero aim against a zero holding means the gap the
no-trade band polices is *zero*, so the band is never consulted — `insideBuffer` is counting an empty gap,
not a veto (Rule 167: look for what does *not* move).

Reading it back through the code: `FusionLifecycle.stopArmed` is `streamVol.sigmaPerSample(name).isPresent()`,
and the boot log carries **`risk-cut σ sensor still cold` for 18 names** at 13:53:49, each quoting the seed
against the **121** prices it needs (`jethro.fusion.risk-cut.vol-span=120` ⇒ `warmupPrices() = 121`): MCD
**38**, NEE **41**, BAC **41**, WMT **43**, KO **43**, GOOG **46**, NVDA **49**, MSFT **61**, AMZN **80**,
AAPL **99**. With `stopArmed` false for every name, `PositionBuffer.mayIncrease` is false for every name,
which takes the ADR-0064/0075 reduce-only branch: a wrong-side holding is classed `isTrappedExit` and worked
**in full** — those five fills — and thereafter the aim is re-seeded to `held + delta` = **0** every cycle.
`streamVolMeasuredNames` confirms it: **0, 0, 0** across the first three samples.

**It is transient, and that is what makes it expensive.** On the fourth sample, ~10.5 minutes after boot,
`streamVolMeasuredNames` ticked **0 → 1** and **exactly one aim went non-zero with it**. The sensor warms one
name at a time at the re-plan cadence; MCD, seeded at 38 of 121, needs ~83 more samples. The loop reboots the
app about every 30 minutes, so the book is liquidated at every boot and only the fastest-warming names are
re-opened before the next teardown. The veto is asymmetric in the damaging direction: an unarmed sensor does
not stop the desk *liquidating*, only *rebuilding*.

## The decision

This is item #1 in `reports/must-fix.md`, and it sits **upstream of the last two changes I shipped** —
ADR-0132's destination clamp and ADR-0133's band cap both act on the aim, and this path zeroes the aim
before either is reached. That is exactly why both were graded UNVERIFIABLE rather than verified: I spent
two cycles fixing a component the live book never reaches. No buffer work is measurable until this is fixed.

I am **not** changing it now (ADR-0133 is at 1/6). And the obvious fix is off the table: ADR-0131
(`efccc6502`, "a cold sensor re-seeds on its own warm-up cadence") is the same root cause, scored **❌ BAD**
and was reverted — never re-attempt it. The untried lever is the **asymmetry**: a sensor that cannot price a
stop is a good reason not to *open* risk, but a poor reason to *liquidate* a book the desk held and was
measuring one process earlier. That changes when a risk control fires, so it needs an ADR — and it is the
candidate for the cycle after ADR-0133 scores.

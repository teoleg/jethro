The no-trade band was priced at a forecast the combiner never produces, so a name's whole target could sit inside its own buffer and never open — the average position is now priced at the forecast the desk actually generates (ADR-0141).

*(Every figure below is read from `logs/report.md`, `reports/run-status.json` and the scorer's own
output. None is authored here — invariant 7 / ADR-0016.)*

## Situation — the live money, first

**Money.** Total PnL reads **-628.06833967**, unchanged **+0.00** since the last run and **+0.00**
across the last three. `pnl_growth_pct` **0.0** against a target of **1.0** — `on_track` **false**,
`underwater` **true**. Not bleeding; **stuck**.

**Risk.** Gross exposure **$0.00** — **0.0%** of the firm cap $1,500,000, with **$1,500,000** of
headroom; net **$0.00** of a $1,000,000 net cap. `breaker.halted` **false**, `var95` **0.00** with the
note `no positions`. This is the **DORMANT** flag, not DANGER: nothing is anywhere near a cap, so under
ADR-0132 the correct response is to deploy, not de-risk.

**Cause.** Last cycle's change (`3cc91bc46`, ADR-0140, the durable fusion aim) scored ⚠️ INCONCLUSIVE —
risk-adjusted return/cycle +0.000060 over 36 cycles, t=+1.23 against a 1.5 hurdle. The V51 migration
rename shipped alongside it **worked**: `ops_jvm` answers with `uptimeSeconds` **62475**, there is no
`FlywayException` in the log, and every endpoint that read `Connection refused` last run is live.
Must-fix item #0 closes ✅.

**Attribution — market vs change.** `recent_orders` shows no order since **2026-08-05 20:24:38Z** and
`orders_day.total` is **0**; the US session was closed for the whole window. The window is therefore
**100% market, 0% change** — with a flat book and a frozen tape there was nothing for either to move.
No credit or blame is claimed in either direction.

## What I found

ADR-0140's mechanism **did** work: the live `aims` map carries **-0.003064** for NQ, a real
partially-adjusted intent that survived a restart, which is exactly what it promised. The desk still
routed nothing — so the aim reaching the band was never the whole story. The arithmetic says why.

ADR-0094's band is `width × |target| × TARGET_ABS / |f|`, and ADR-0102 confines the aim to the interval
between flat and the target. A name opens only when `|aim|/|target| > width × TARGET_ABS/|f|`, and the
left side is bounded above by **1** — by ADR-0102, not by any dial. The condition is therefore
**unsatisfiable for every name whose combined forecast is weaker than `width × TARGET_ABS`**. Of the two
names carrying a view this cycle, NQ (`f` **-1.2137982837547245**) needs **0.8239** and NVDA (`f`
**-0.31439455218834894**) needs **3.1807** — greater than one, so no aim path of any length could ever
have opened it. `insideBuffer` reads **8** of **8**, and gross is zero.

The root is a units error. `TARGET_ABS` is what each **source** is normalised to — live `meanAbsClaim`
**8.933184091982607** reversion, **6.672769378384960** trend, **9.238246464313300** xsreversion, all
near it as designed — but the band is applied to the **combined** forecast, which the ADR-0076
multiplier and the ADR-0124 agreement scalar have already attenuated (NQ's **+18.529717715250550** and
**-20.0** average to -5.427; agreement **0.1954775485324326** takes it to -1.214). Both scalars were
specified as reductions in **size**. Feeding a shrunken forecast into a threshold calibrated for an
unshrunken one does not shrink the position — it deletes it. An 80% haircut to conviction became a 100%
haircut to the position, permanently, in a place neither ADR intended a tradability gate.

## The change

ADR-0141 prices the average position at the forecast strength the combiner actually produces: the mean
`|combined forecast|` over the names planned a view this cycle, capped at `TARGET_ABS`. No number is
introduced — it is the mean of forecasts the planner already computed. It is cross-sectional, so it
needs no estimator, no warm-up and no persistence and survives a restart intact, which is the failure
ADR-0138 and ADR-0140 were both spent repairing. The cap makes it strictly one-way: the band is never
*wider* than before, so this can release a trade the desk's own arithmetic already wanted but never
freeze one. The aim path, the target, the ADR-0102 clamp and the ADR-0101 width are untouched, and every
deterministic floor — edge gate, σ-cold veto, vol budget, gross cap, pre-trade guardrail, drawdown
breaker — still has the last word on anything released.

I deliberately did **not** touch ADR-0124's agreement scalar, though it is what zeroes the other six
planned names. Those six are single-source and their only source is **xsreversion**, whose measured
expectancy is negative at all three horizons (`avgReturnBps` **-1.6172582662041586** at 900 s over 152
cohorts, **-1.9673663434138422** at 3600 s, **-0.16422896669296588** at 225 s). Unlocking them would
deploy capital into the desk's worst-measured source. They are flat for the right reason, and the honest
read of the telemetry is still that **no source measures a significant positive edge** — nothing clears
its cohort hurdle. This change does not manufacture edge; it stops the desk being unable to act on
whatever edge it does form.

`-Pci test` green, five new tests including the dead-zone proof (NVDA's nominal band exceeds its entire
target) and the live frozen-NQ example.

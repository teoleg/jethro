The desk has traded nothing all session because its sensors were seeded once, at boot, into a store the outage had emptied — a cold sensor now re-seeds until it warms (ADR-0131).

*(Every figure below is read from `logs/report.md`, `logs/jethro-app.log`, the live endpoints,
`reports/run-status.json` or `flyway_schema_history` / `pg_constraint`. None is authored here —
invariant 7 / ADR-0016.)*

**Situation triage.**

1. **Money.** Total PnL **$126.87**, unchanged run-over-run and unchanged across the last three runs
   (**+$0.00** each). It is not bleeding — but it is not moving either, and the reason is that nothing is
   on. Fees for the day stand at $32.23 against **zero** orders placed (`orders_day.total = 0`).
2. **Risk.** Gross exposure **$0.00** — **0.0%** of the $1,500,000 firm cap, with the entire $1,500,000 of
   headroom unused; net $0.00 against a $1,000,000 net cap. The flag is **DORMANT**, which is a failure to
   attack, not a rest state.
3. **Cause.** Last cycle's change (the V49 asset-class constraint fix) is **✅ VERIFIED**: `/api/risk`
   answers HTTP 200 instead of `Connection refused`, `flyway_schema_history` shows V49 `success=t`, and
   `instrument` now holds 12 `INDEX` rows against 0. The app has been up since 07:47 ET. The change before
   that, ADR-0126, finally accumulated cycles and scored **⚠️ INCONCLUSIVE** (kept, not reverted), so a new
   change is due and `.pending-baseline.json` is clear.
4. **Danger.** None of the danger kind — no drawdown breaker (`halted: false`), no proximity to any cap.
   The live problem is the opposite one: a completely idle book with a full risk budget.
5. **Order-level post-mortem.** There is nothing to post-mortem: `recent_orders` and `orders_day` are both
   empty for the session. That absence *is* the finding, and it is what I chased.

**Diagnosis — the mechanism, not the symptom.** `/api/fusion/targets` shows every name with `sources: 1`,
`agreement: 0.0` and `combinedForecast: -0.0`, while the underlying raw source forecasts are large
(MSFT −20.0, AMZN −12.5, NQ −11.3, AAPL +6.4). That is ADR-0124 working exactly as designed — the
dispersion of one source is unestimable, so an uncorroborated view sizes at nothing. The real question is
why every name has only one source, and the log answers it: since boot there are **39 `trend sensor still
cold` warnings and 0 `trend sensor warmed`**, the same 39/0 for reversion, and 9/1 for the σ sensor. The
seeds read `0 of 193`, `1 of 241`, `0 of 121`. The one name that warmed is NQ, which prints overnight and
whose seed returned `232 of 241` — the natural experiment that pins the cause on a discontinuity in the
stored price series rather than on the sensors themselves.

The defect behind that is one line of policy: the ADR-0071 warm-restart seed runs **on first sight of a
name and never again**. First sight is boot, and this boot followed a 4h45m outage, so the single read
that mattered was taken at the one moment the store's tail was guaranteed to be empty. The sensors were
abandoned there — and an hour and three quarters later, with the store holding 378 AAPL prints over 81
minutes at a 1.1-second median (far more than any of those warm-ups needs), `forecastScalars` still
contains no `trend` key at all. ADR-0126's open veto reads the same failed σ, so it was independently
blocking every open for the same reason.

**The change (ADR-0131).** A cold sensor re-seeds. `SensorReseed` re-attempts the replay every
`warmupSamples()` sightings while a sensor is still cold, and retires a name permanently once it warms —
the cadence is read off the sensor's own warm-up length, so no dial and no number is introduced. The
replay goes into a state just dropped by a new `forget(instrumentId)` on the two forecasters and the σ
sensor, so prints already consumed are never double-counted; the reset touches only cold names, which
publish no view and arm no stop, so nothing live can be disturbed. `SensorWarmup` and every gate between a
forecast and a fill are untouched — this lets the sensors speak, it does not relax anything that decides
whether speaking becomes a trade. Tests green (`./gradlew -Pci test`), including an end-to-end case that
reproduces a truncated boot seed and shows the retry warming the same sensor.

**Attribution, honestly.** No loop commit deployed into this window and no position was live, so the
unchanged PnL is neither market nor change — there was nothing to move. I take no credit and no blame for
it. Whether ADR-0131 helps will be visible next run as `sources ≥ 2` and a non-zero `agreement` on
`/api/fusion/targets`, and only after that as exposure.

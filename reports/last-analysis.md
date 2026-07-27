The desk's positions sweep their whole range every ~20 minutes because that is the reversion sensor's own window — the window is now 40 minutes, halving the turnover the desk pays for one horizon of credit.

## Situation — read off the live endpoints and a SQL aggregate over `fills`; every figure is quoted, none computed here

**1. Money.** Total PnL `$128.01`, **up for a fourth consecutive run**: `+$12.01` on the window and
`+$182.92` across the last three. `run-status.json` reports `on_track` true and `pnl_growth_pct` far ahead
of the 1% target, with `underwater` and `stale` both false. The book is **not bleeding**.

**2. Risk.** Gross exposure `$13,531.44` against net `$2,701.97` — **down `-$8,622.67` this window** and
`-$15,836.61` over three. VaR95 `$108.96` / ES95 `$169.85` on `$13,515.33` covered; the firm drawdown
breaker is not tripped and is nowhere near it. Regime `CALM`, trend `CHOP`. **No danger state** — PnL rising
while exposure falls is the correct quadrant, so no de-risk override applies this cycle.

**3. Cause — last cycle's change worked, and its own contingent prediction resolved.** ADR-0094 (the
no-trade band measured against the aim) scored ⚠️ MIXED with risk-adjusted PnL per $1 gross improving
`0.00515 → 0.00957`. The finding I left last cycle said the band would be vindicated if turnover fell. A
5-minute bucket count over `fills` says it did: ALPHA went from ~25–35 fills and `$17–22k` traded per
bucket to ~11–15 fills and `$5–13k` after the deploy. **So the band binds now — its width is not the next
lever, and I did not touch it.**

**4. Order-level post-mortem, and what it ruled OUT.** Reconstructing each name's position path from
`fills` shows only 22 sign flips across 838 ALPHA fills — the churn is **not** reversals. It is oscillation
*within* a sign: JNJ walks `-104 → +16 → -84`, AAPL `-9 → +45 → -2 → +42 → -13`, JPM `+2 → +48 → +19 →
+47 → -8`, each sweeping its full amplitude and back on a **~20–25 minute period**. I also checked and
discarded two candidates: the HEDGE book is the firm's biggest loss line, but its loss is directional (short
ES into a rising ES) and offsets ALPHA's long — cutting it would be the classic hindsight error, not a fix;
and the boot-time "sensor still cold" warnings self-heal on the next live tick, so they are noise, not a
dead control.

**5. The mechanism.** `jethro.fusion.reversion.range-span=120` at a 10s cadence is a **20-minute** Donchian
window — the same period as the observed sweep. That is not coincidence: `pos` is a level bounded to
`[-1,+1]` that mechanically traverses the interval on the time scale of its own window, so the **window
length sets the position path's total variation**, and total variation is exactly what the desk pays fee and
spread on. Meanwhile the ADR-0082 ladder grades this source at 225/900/3600s and its measured expectancy is
*largest* at the long rungs and smallest at the short one. Defining "stretched" over a window near the
horizon where the edge is weakest is what makes the desk pay several round trips per horizon of credit.

**6. The change.** Range span `120 → 240` (a 40-minute range, still inside the 3600s grading window at 2/3
of it, so the stretch keeps room to revert there) with normalisation span `240 → 480` to hold the 2×
proportion. That proportion is what preserves the sensor's own `E|score| ≈ 1` calibration — so **the book's
size is unchanged by construction and only the sweep frequency moves.** This is the property ADR-0088 lacked
when it tried to slow the signal by averaging the fused conviction downstream and levered the book up
instead; that lever stays burned and I did not re-attempt it. The config comment already declared this ratio
"MINE and arbitrary — tune it on measured edge, not taste"; the measurement now exists, so this is that
tune. The factor of 2 is mine and arbitrary, and both dials are shape dials that size nothing.

**7. Change vs market — attributed honestly.** The window's `+$12.01` is **not** claimed for ADR-0094.
That change altered the execution path on every planned name, so there is no untouched control group to read
the market off and the PnL half cannot be split from the numbers alone. Only the *turnover* half is
separable and clearly the change's: the fills-per-bucket step-down lands at the deploy and on no other
boundary. I am claiming the turnover reduction, not the money.

**Verification.** `./gradlew -Pci test` green. Warm-restart arithmetic checked before shipping: the longer
warm-up (481 samples over a 2.7h lookback) sits well inside the 12h durable mark store, so the desk's one
profitable source still boots warm instead of going dark after a redeploy.

The buffer's no-trade band was wider than the entire position it was policing, so 20 of 20 names planned exactly nothing and the desk sat at 4.7% of its own target book — fixed by capping the band's scale at the target (ADR-0133).

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Situation

**Money.** The SITUATION header reads total PnL **$779.87**, unchanged since last run and over the last
three (**+0.00** both) — but it was snapshotted at `atMillis` 1785504593354, **seven seconds before the US
open**, and the three preceding heartbeats were all `market-closed`. So the flat reading is the overnight
freeze, not a stalled book. Sampling `/api/risk` directly once the session was live showed PnL and gross
both moving within three minutes of the open. `pnl_growth_pct` **0.0** vs `pnl_target_pct` **1.0**,
`on_track` **false**.

**Risk.** Gross **$0.00** at the snapshot — **0.0%** of the $1,500,000 firm cap, headroom **$1,500,000**;
net **$0.00**, 0.0% of the $1,000,000 net cap. Flag: **DORMANT**. Nowhere near a cap or the drawdown
breaker: this is the opportunity case, not the danger case.

**Cause.** Last cycle's change `c58e7bb83` (ADR-0132, the destination clamp) scored **⚠️ INCONCLUSIVE** —
+0.015004 risk-adjusted return/cycle over 37 cycles, t=+1.45 against a 1.5 hurdle — so it was kept, not
reverted. `reports/.pending-baseline.json` is gone and `score` prints no pending change, so a new change was
due. Its own claim could **not** be verified this cycle: with every `currentQty` at 0 there is no wrong-side
holding for the clamp to act on, so the check is vacuous rather than passed. Carried forward in the register.

**Danger.** None — not bleeding, not near a cap, breaker not tripped.

**Attribution.** The window spans a market-closed stretch and the open, and the scored change opens and
closes nothing. Market and change are **not separable** here; I claim neither (Rules 141/152).

## What the telemetry showed

Once the session opened I sampled `/api/fusion/targets` across four consecutive re-plans (`atMillis`
1785504804718 / 1785504834909 / 1785504865091 / 1785504895225). `insideBuffer` read **19, 18, 20, 20** of
**20** — two of the four planned **nothing at all** — while the desk held **$14,215** gross against a target
book it had itself computed at **$303,271**. It was holding 4.7% of the risk its own forecasts asked for,
with a $200k deploy budget unused.

Per Rule 164 I recomputed the buffer's published arithmetic rather than theorising about it; it reproduces
every `deltaQty` exactly. The defect is a **scale mismatch**, and it is structural. The band is
`width × |target| × TARGET_ABS / |forecast|`. The target is *linear* in the forecast, so the two
`|forecast|` factors cancel: the band is the name's position at a **full-strength** view and does not shrink
when conviction is weak. But ADR-0102 confines the aim it is tested against to the interval between flat and
the **current** target — and that interval's width *does* shrink with conviction. The band therefore
consumed `width × TARGET_ABS / |forecast|` of the entire interval the aim could ever occupy, and once
`|forecast| < width × TARGET_ABS` (= 1.0 at the shipped 0.10) it was **wider than that whole interval**.
From flat, `|gap| = |aim| ≤ |target| < band` at *every* aim the hour-long ADR-0080 path can ever reach, so
the delta was exactly zero **forever**.

Not a slow ramp — a permanent veto, live on **6 of 20** names (CAT 0.208, XOM 0.200, HD 0.175, PG 0.127,
JNJ 0.033, KO 0.000). The other 14 were locked out for 12–50% of their target: PFE's band was 377.467
against a gap of 7.959, NEE's 175.021 against 34.533, BAC's 132.757 against 22.772.

Carver's rule — which this class cites — cannot produce that: his distance is `|target − current|`, which at
a typical-strength forecast *is* the average position the band is a fraction of, so the two are commensurate
by construction. ADR-0094 correctly moved the test onto the aim but carried the average-position scale
across unchanged, and ADR-0102 later shrank the aim's interval with conviction. The incommensurability is
the residue of those two changes, not of either alone.

## The change

`PositionBuffer.band` now caps the position scale at `|target|` — one `min`, so a band can never exceed
`width` of the interval it sits inside. Strictly one-way: it can only ever **narrow** a band, never widen
one, so it can only permit a trade the desk's own aim path had already decided to make. Inert at
`|forecast| ≥ TARGET_ABS`. No number is introduced — the bound is the target the planner already computed,
the same provenance ADR-0102 uses for the intent and ADR-0132 for the destination. Shipped as **ADR-0133**
(`Status: Implemented`) with the config comment carrying the provenance.

Everything else is byte-identical: the ADR-0080 aim path, the ADR-0101 measured width, the ADR-0107 rated
view-change unwind, the ADR-0090 unrated same-side de-risking, ADR-0118's trapped exit, ADR-0132's
destination clamp, and the unbuffered full working of a flat target. The deterministic floor is untouched.

I first tried the larger fix — re-centring the whole no-trade region on the target, as Carver states it —
and **reverted it**: it broke ADR-0107's rated unwind (dumping a full 104-share holding where the rule
requires a rated step) and slowed ADR-0090's same-side de-risking. The narrow cap fixes the proven defect
without disturbing either.

**The honest risk.** Exposure and turnover are *expected* to rise — that is the point, per the ADR-0132
objective — but if the underlying edge is genuinely absent, that turnover is pure cost. This is the honest
test of whether the buffer was protecting the book or strangling it, and the ADR-0116 scorer is the check:
a bad answer gets graded ❌ and reverted.

**Verify next run:** `insideBuffer` well below 20/20, and gross climbing off ~5% of the
`/api/fusion/targets` target book. Full `./gradlew -Pci test` green.

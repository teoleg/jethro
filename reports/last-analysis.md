# The desk was voting AGAINST its own best-measured signal — trend (+2.32σ, 173 cohorts) sat at the weight floor while two measured-LOSING sources steered the book.

## Situation (live, read from this run's report — never authored)

1. **Money.** Total PnL **-$1,014.62**, down **$63.44** since last run and **$191.47** over the last
   three. Bleeding, and UNDERWATER. Fees **$267.63** — 26.4% of the cumulative loss, so unlike the
   07-28 → 08-07 stretch the majority of this loss is now **directional**, not churn.
2. **Risk.** Gross **$86,184.42** = **5.7%** of the $1,500,000 firm cap, **$1,413,816** of headroom;
   net **-$19,973.92** = **2.0%** of the $1,000,000 net cap. Gross rose **+$19,741.41**. Not danger —
   a book using 5.7% of its budget (ADR-0132) with the breaker cold.
3. **Cause.** `bc2a4dbf3` (ADR-0147) was scored **❌ BAD** and its auto-revert **landed cleanly** this
   time (`fdc948f`; the ADR file, `PositionBuffer` change and `wrong-side-share.py` are all gone, tree
   clean) — so the ADR-0143 path-restore fix that failed on the last nine verdicts is now ✅ VERIFIED.
   No logic has been deployed since; `uptimeSeconds` **60917** against an 18:30:01Z stamp is one
   continuous JVM.
4. **Danger.** No. Bleeding at 5.7% of the gross cap is the DORMANT-side failure, not the near-cap one.
5. **Change vs market.** Nothing was deployed this window, so the entire PnL/exposure move is market and
   pre-existing logic — creditable to no change.

## What I found

The standing priority asks first whether ANY signal predicts returns here. **One does, and this is new.**
LIVE `signal_observations` at 225 s now separate on large samples: **trend 0.542** (n=3,202),
**reversion 0.467** (n=2,957), **xsreversion 0.462** (n=3,271). Cluster-robust on cohorts: trend
**+0.5626 bps, t = +2.32 on 173 cohorts**; reversion **−0.4166, t = −1.67 on 161**; xsreversion
**−0.2475, t = −0.96 on 173**. That is one positive source and two measured-negative ones.

**And the desk is weighting them almost exactly backwards.** Live `fusion_targets` weights:
`trend 0.25` (the floor), `reversion 1.2026`, `xsreversion 1.1279`, `momentum 1.2438`, `social 1.1769`.
I reproduced that vector from the **3600 s** telemetry through `TelemetryWeights`' own arithmetic and it
matches to five decimals — so this is proven, not inferred. The `contributions` blocks show the cost:
on **MCD** trend said **+10.97** at weight 0.25 while reversion (−20.0) and xsreversion (−13.86) at ~1.15
carried the vote to −13.25 and a **short of 260**; on **NVDA** trend said **−10.60** and the book went
**long 183**.

**The mechanism.** `jethro.fusion.edge-gate.enabled=false`, and no rung's gate clears in any case, so
`HorizonLadder.select` rule 3 fires and the **base** rung stands — 3600 s, which in a rolling window is
by construction the rung with the FEWEST cohorts in the ladder (5–17 per source vs 44–173 lower down).
Not one of those five 3600 s readings is significant. The desk's entire conviction vector is a ranking of
noise, and it lands inverted relative to the rung it actually trades at.

## The change — ADR-0148

Estimate the combination **weights** on the best-DETERMINED rung (most independent cohorts, ties to the
longer horizon); leave the gate's rung and the ADR-0080 holding period exactly as ADR-0082 set them.
`TelemetryWeights` already separates these two questions on the cost axis — weights are gross of cost
because "cost decides whether to trade at all, not whose view counts" — and this carries the same
separation to the horizon axis. It is **not** ADR-0082's "buy significance by shortening the horizon"
error: that applies to the gate, whose cost term does not shrink with the horizon, while the weighting
statistic `Φ(avgReturn/stdError)` is dimensionless and does not inflate. The criterion is **outcome-blind**
(cohort count is measurement geometry, not a result), so no Bonferroni haircut is owed. No new dial, no
new number. Effect on the live vector, asserted exactly in `HorizonLadderTest`: trend **0.25 → 1.94**,
reversion **1.20 → 0.25**, xsreversion **1.13 → 0.34**.

The honest risk: grading/holding and weighting are now denominated over different periods, which
ADR-0082 set out to prevent. Bounded by the fact that this desk's *realised* hold is minutes (2,813
fills over 21 names) and that the measurement being displaced is insignificant, not competing. Because
the combiner normalises by Σweights, this **rotates** conviction and cannot scale the book — expect the
sign and composition of positions to change, not gross. `./gradlew -Pci test` green.

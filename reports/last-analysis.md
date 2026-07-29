The desk woke up and started trading for the first time — and the loss is concentrated in the one source that measures *negative* edge; no change this cycle because the previous one is still under measurement (1/6).

*Every figure below is read from the live endpoints, `reports/run-status.json`, the ledger, this run's
report or `ps`; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number that gates
money). The t-statistics quoted are computed by script from the gate's own published cohort dispersions.*

**0. Scoring state — this decides what I am allowed to do.** `scripts/score-change.py score` prints
`d9f8969cc still accumulating evidence (1/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present. Per the contract that forbids a new code change: piling one
on top would destroy the evidence for the deploy that *just* landed. Analysis and memory only.

**1. Money.** Total PnL **$-23.21** live (the report header, taken 37s earlier, read $-9.32). It was
`$0.61209817` — frozen at that value for the previous three runs. So the book has moved
**down** roughly twenty-four dollars in twelve minutes, after four days of not moving at all.
`pnl_growth_pct` 0.0 vs `pnl_target_pct` 1.0, `on_track=false`, and the flag is now **UNDERWATER**.

**2. Risk.** Gross **$10,772.39** = **0.7%** of the $1,500,000 firm cap; net **$2,915.32** = **0.3%** of
the $1,000,000 net cap. From `$0.00`. Breaker `halted:false`, and the drawdown limit is `maxFirmDrawdown`
$50,000 against a $23 loss. `riskCuts: []`, `bookVolBrake: 1.0`, `portfolioRiskMultiplier` 0.77.
**This is the DORMANT→active transition the loop has been trying to cause, not a danger state** — 0.7% of
cap with $1,489,228 of headroom. The contract is explicit that gross rising with room to spare is the goal.

**3. Cause — the prediction landed, exactly.** Last cycle predicted: after the ADR-0123 deploy the running
app should report `edgeGate` inactive and `deltaQty` should go non-zero. `ps` gives JVM start
`Wed Jul 29 09:48:18 2026` (13:48:18Z) — a fresh process, minutes after commit `fea8dac`. Live
`/api/fusion/targets` now returns **`edgeGate: null`** (`gateSupplier` null ⇒ gate off, per ADR-0122) with
`routing:true` over 11 instruments and non-zero deltas. First LIVE order in 22 hours fired at
**13:54:00Z**; 13 fills today against 7 in the book's entire prior history. **The dormancy was a delivery
bug, and it is fixed.** Rule 62 named the conflation in advance and it holds: this window contains two
deployments, and the behaviour on display is **ADR-0122's**, not the plumbing fix's.

**4. Danger.** No. Bleeding, yes — but at 0.7% of the exposure cap and 0.05% of the drawdown breaker.
De-risking a book that just came off zero because its first twelve minutes are red would be exactly the
overreaction the contract warns against.

**5. Order-level post-mortem — this is the finding.** Per-position, the loss is not spread evenly:

- **JPM +14 sh, gross $4,893, PnL −$18.05** — **~78% of the entire firm loss in one name.** That is
  roughly −37bps, far past any plausible spread. Accumulated 1 share at a time every 30s from 13:54:00.
- **NQ +0.004134, gross $2,301, PnL −$5.28** (~−23bps) — same drip pattern, order re-planned and
  re-posted larger each cycle.
- **AAPL −6 sh, PnL −$0.16** and **JNJ −7 sh, PnL −$0.47** — ≈1–2bps, i.e. entry cost. Fine.
- **ES (hedge) flat, +$0.96.**

So the damage is entirely in the two names the desk is *accumulating a long into*, and both longs are
driven by **`xsreversion`**: JPM shows `sources: 1`, `combinedForecast 15.02`, `xsreversion` alone, with
**nothing opposing it** — `trend` does not even contribute. The mechanism is the classic failure mode of an
unfiltered cross-sectional fade: as JPM falls further below its peer median, its residual grows, so the
forecast grows, so `targetQty` grows (89.68 vs `currentQty` 12) — **a falling price makes the model buy
more.** A self-reinforcing accumulator, no trend filter, no per-name stop (`riskCutStoppedNames: 0`).

**6. Memory.** Applied. Rule 61 (prove the JVM runs the code you're reading) is why I checked `ps` and
`edgeGate` first rather than trusting `application.properties` — and it is why point 3 is a verification
rather than a guess. Rule 62's warning about this window is honoured in point 3.

**7. Change vs. market — attributed honestly.** The book was flat at `$0.00` gross when the window opened,
so **100% of the $10,772 of exposure and every dollar of the move sits on positions the code opened this
cycle** — there is no untouched inventory for the market to have moved. But that does *not* make the
twelve-minute PnL evidence about any signal: the entries are minutes old, the marks are mid, and −$23 on a
$10.7k book is comfortably inside noise. **I am deliberately not drawing a conclusion from it.**

The durable evidence is elsewhere, and it is not noisy. `/api/signals/telemetry`, scored by t-statistic
computed from the gate's own published cohort dispersions (`avgReturnBps / (stdCohortMeanBps/√cohorts)`),
across days and hundreds of resolved observations:

| source | 225s | 900s | 3600s |
|---|---|---|---|
| **reversion** | +0.39 | **+1.76** | +1.39 |
| trend | −0.09 | −0.02 | −0.88 |
| momentum | −0.04 | +0.31 | −1.45 |
| social | −0.43 | −0.49 | +0.21 |
| **xsreversion** | −0.25 | −0.74 | −8.32 |

**`reversion` is the only source with positive expectancy at every horizon, and the only one clearing the
1.5 hurdle anywhere** (900s: +3.17bps over 291 resolved / 88 cohorts). **`xsreversion` is negative at
every horizon** — and it is the sole driver of the position holding four-fifths of the loss. The live
12-minute result is not the evidence; it is a *consistent illustration* of what the multi-day telemetry
already said. That distinction matters, and it answers the standing "work on EDGE" priority: yes, one
signal in this universe does predict returns, and it is `reversion`.

**Decision.** No change — the scorer holds the floor for five more cycles. Committing reasoning and memory
only. **Next cycle's candidate, stated now so it is falsifiable and not retrofitted:** demote or gate
`xsreversion` (weight 0.52, sole source on JPM/NVDA/NFLX) on its own measured expectancy, and let
`reversion` — the one source with real, significant, multi-day edge — carry the size. What I will check
first: whether JPM's loss persisted or mean-reverted over the full window, which distinguishes "the fade
was early" from "the fade was wrong."

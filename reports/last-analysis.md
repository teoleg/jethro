The revert re-verified on a fourth, independent JVM — every cold sensor logs exactly one seed line and no name is warmed twice, both proved by count over the whole log — and at 4/6 cycles into its measurement window I made no change; the cycle went instead to the thing that has now become urgent rather than merely diagnosed: ALPHA's fee-to-result ratio has deteriorated four consecutive runs, with `feesPaid` climbing and `totalPnl` falling on every one, while the `fusion_targets` rows show the desk stepping ~2 shares a cycle toward targets tens of times further away and cancelling the prior slice every 30s — the inverted ADR-0101 fallback is a compounding leak, not a static one.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Situation (answered first)

1. **Money.** The SITUATION header reads total PnL **$106.86**, and computes **-22.94** since the last run
   and **-35.44** across the last three. So the book *is* bleeding run-over-run at the firm total. The
   `run-status.json` heartbeat `2026-07-30T18:06:10Z` still reads `pnl_growth_pct` **3.63** vs
   `pnl_target_pct` **1.0** with `on_track` **true**, `stale` **false**, `underwater` **false** — but that
   heartbeat predates this report, and the fresher header is the one to trust. Underneath the total,
   `/api/attribution` reads `hedgePnl` **$135.03732831** carrying `strategyAlpha` **−$28.17919159** with
   `hedgeMasking` **true**: the strategy books are losing and the hedge is masking it.
2. **Risk.** Gross **$36625.58** is **2.4%** of the $1,500,000 firm cap with **$1,463,374** of headroom;
   net **$-13291.73** is **1.3%** of the $1,000,000 net cap. Flags: **none**. Not DORMANT — `fusion_targets`
   reads **20** instruments plus the ES hedge — and nowhere near the cap or the breaker. Note the net swung
   negative this window while gross barely moved, which is the reversion-dominated aim (KO `targetQty`
   **−412.71**, NEE **−351.01**, JNJ **−146.26**) pulling the book short; at 1.3% of the net cap that is a
   read, not a danger.
3. **Cause.** The pending change is the completed revert of the ❌ BAD ADR-0131 cold-sensor re-seed, and
   the scorer prints `64a7a6336 still accumulating evidence (4/6 cycles) — held, not scored this run`. Its
   *stated purpose* is ✅ VERIFIED on a fourth JVM (PID **3626405**, boot **14:06:34.573** local): keyed on
   lifecycle+name, no cold-sensor line repeats anywhere in the log and no per-name warm line repeats
   either; the only count>1 is the ADR-0089 rolling covariance rebuild, and the four post-boot warms are
   genuine first-seeds of NQ and TSLA.
4. **Danger.** No. Bleeding, yes — but with 97.6% of the gross cap unused and no flag set, the correct
   response is to fix the cost leak, not to de-risk (Rule 147).
5. **Order-level post-mortem.** The `recent_orders` tape is almost entirely ALPHA, with a re-plan landing
   every ~30 seconds (**18:22:50** through **18:29:54**) and most orders dying as `fusion re-plan — passive
   order superseded by a fresh target (ADR-0084)`. `orders_by_status` reads FILLED **3985** against
   CANCELLED **1289**. No single trigger opened a distinct loser; the loss is the *aggregate* of the
   re-plan cadence itself.
6. **Change vs market.** **Not separable.** A JVM boot at **14:06:34** local sits inside this window and
   the pending change is a revert that opens and closes nothing, so I claim neither credit nor blame for
   the **-22.94** (Rule 141/152). What *is* attributable independently of the window is the structural
   read: ALPHA has paid `feesPaid` **$73.682486** to show `totalPnl` **$7.64428496**, and that ratio is
   a cost fact, not a market move.

## What I decided, and why

No code change — the revert is at 4/6 and a new change would destroy its evidence. The cycle went to
hardening item #1 from "diagnosed" to "trending", which is what changes its priority. The ALPHA
PnL/fee pair across the last four runs is monotone in both directions — **$23.36357064**/**$56.444977** →
**$9.88820735**/**$60.552698** → **$8.61831343**/**$66.073455** → **$7.64428496**/**$73.682486**. A
four-point monotone sequence is a trend, not a blip, and it means the inverted no-trade-band fallback is
compounding.

The mechanism is now readable in a single `fusion_targets` row, which is the cleanest evidence yet: KO
holds `currentQty` **−69.0** against `targetQty` **−412.71** and moves `deltaQty` **−1.915**; JNJ **−14.0**
against **−146.26** at **−2.745**; NVDA **10.0** against **124.21** at **2.795**. The step is derived by
ADR-0080 from a 3600s e-folding time, but the aim is redrawn every 30s under `reversion` weight
**1.6024876487480502** against `trend` **0.42024570127205746** — so the desk never arrives and pays a round
trip each time the mean-reverting aim flips. `turnover_cost_by_name` confirms it in shares: JNJ traded
**326** gross to hold **14**, NVDA **292** to hold **10**, PFE **528** over **18** fills.

The defence that should stop this is configured on but inert. ADR-0101 widens the no-trade band to
`max(fraction, min(1, 2C/mu))`, falling back to the convention when expectancy is unmeasured — and
`strategy_diag.edgeGated` reads **13** names with `no positive OOS edge` on every one, so mu is never
measured and every name takes the narrow **0.10** branch. mu ≤ 0 makes `2C/mu` unbounded; the fallback
should be the *widest* band, not the narrowest. That inversion is the change queued for the cycle after
the revert scores, with its ADR in the same commit.

The revert re-verified on a fifth independent JVM, and at 5/6 cycles I made no change — instead I spent the cycle re-testing my own diagnosis and found it was wrong: the no-trade band I have blamed for four consecutive runs is NOT inert (`insideBuffer` reads 13 of 20 names suppressed every cycle), and the ADR-0101 fallback I had queued a fix for is not "inverted" — it is Carver's cited convention for exactly the unmeasured-edge case, so widening it would have meant authoring a risk number that nothing measures. The real defect is one level up and I measured it directly: sampling the target book across three consecutive 30s re-plans, AAPL's `targetQty` goes -10.75 → +129.53 → +67.19 and HD goes -1.48 → +42.78 → +1.39 — the targets re-randomise rather than drift, so no buffer width and no adjustment rate can ever absorb them.

*(Every figure below is read from the live endpoints, `logs/report.md`, `reports/improvement-ledger.md`
or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

## Situation (answered first)

1. **Money.** The SITUATION header reads total PnL **$117.52**, **-6.43** since the last run and **+3.04**
   across the last three — so the book is roughly flat, not bleeding, at the firm total. The
   `run-status.json` heartbeat `2026-07-30T18:34:15Z` reads `pnl_growth_pct` **-12.89** against
   `pnl_target_pct` **1.0** with `on_track` **false** and `stale` **true** — the objective flags flipped
   off-track since last run, when they read `on_track` true. Underneath the total, `/api/attribution` reads
   `firmTotal` **$116.30055246** carried entirely by `hedgePnl` **$135.03832909**, with `strategyAlpha`
   **-$18.73777663** and `hedgeMasking` **true**: the strategy books lose, the hedge masks it.
2. **Risk.** Gross **$37668.89** is **2.5%** of the $1,500,000 firm cap with **$1,462,331** of headroom;
   net **$-12290.58** is **1.2%** of the $1,000,000 net cap. Flags: **none**. Not DORMANT — **20**
   instruments in `fusion_targets` plus the ES hedge, and an actively filling tape — and nowhere near the
   cap or the breaker.
3. **Cause.** The pending change is the completed revert of the ❌ BAD ADR-0131 cold-sensor re-seed; the
   scorer prints `64a7a6336 still accumulating evidence (5/6 cycles) — held, not scored this run`. Its
   stated purpose is ✅ VERIFIED on a fifth JVM (PID **3646490**, boot **14:34:38.573** local): keyed on
   lifecycle+name, no cold-sensor line repeats anywhere in the log; keyed on the full lifecycle class, no
   per-name warm line repeats either; `SensorReseed` is absent from the source.
4. **Danger.** No. With 97.5% of the gross cap unused and no flag set, the correct response is to fix the
   cost leak, not to de-risk (Rule 147).
5. **Order-level post-mortem.** `orders_by_status` reads FILLED **4052**, CANCELLED **1364**, REJECTED
   **83**. Every cancellation on the `recent_orders` tape carries the same reason —
   `fusion re-plan — passive order superseded by a fresh target (ADR-0084)` — and the clearest single
   trace is HD, posted BUY **1** → **2** → **2** → **3** → **4** across five consecutive re-plans
   (**18:57:28** through **18:59:59**), each slice cancelled before it filled and re-posted larger. No
   individual trigger opened a distinct loser; the loss is the re-plan cadence itself.
6. **Change vs market.** **Not separable.** A JVM boot at **14:34:38** local sits inside this window and
   the pending change is a revert that opens and closes nothing, so I claim neither credit nor blame for
   the **-6.43** (Rule 141/152).

## What I decided, and why

No code change — the revert is at 5/6 and a new change would destroy its evidence. So the cycle went to
re-testing the diagnosis I have carried for four runs, and it did not survive.

**The band is not inert.** `/api/fusion/targets` exposes `insideBuffer`, incremented in
`PositionBuffer.apply` exactly when a planned delta is zero. It reads **13** of **20**. I had been asserting
the opposite from indirect evidence without ever reading the counter that answers it directly.

**The queued fix was wrong and is withdrawn.** I had planned to widen `PositionBuffer.widthFor`'s fallback
on the argument that unmeasured μ makes `2C/μ` unbounded, so the narrow branch is "inverted". But **0.10 is
Carver's published convention for precisely the desk that has not measured its edge**, and the method's own
contract says that with no measurement there is no claim to make. Widening it would have been inventing a
number that gates money — the exact failure invariant 7 / ADR-0016 exists to prevent. Shipping it would
have been a self-inflicted violation dressed up as a fix.

**What is actually wrong, measured rather than inferred.** Sampling `/api/fusion/targets` across three
consecutive re-plans (`atMillis` **1785438150584**, **1785438180812**, **1785438210932**), the target book
re-randomises: AAPL **-10.75** → **+129.53** → **+67.19**, HD **-1.48** → **+42.78** → **+1.39**, NVDA
**-0.08** → **-6.69** → **-50.91**, KO **-324.08** → **-231.96** → **-45.38**, GOOG **+0.29** → **+1.01** →
**-3.44**. Sign flips and order-of-magnitude swings in 30 seconds. The buffer's band is
`|target|·TARGET_ABS/|forecast|`, so it swings *with* the target it is supposed to filter — which is why no
width, and no adjustment rate, can absorb this.

The driver is visible in the same payload: `weights` reads `reversion` **1.5810674747889952** and
`xsreversion` **0.9850309963910409** against `trend` **0.39443196542948245**, so two fast mean-reversion
sources dominate a 30s re-plan. And `strategy_diag.edgeGated` reports `no positive OOS edge` on every name
it lists — MSFT `momentum -37.36954202 … mean-rev -73.69953186`, AMZN `-46.40665676`, GOOG
`mean-rev -11.15007399`, SAP `-39.20256473 … -65.12971133`. The desk is paying round trips to chase sources
with no measured edge, faster than those sources decay. That is the standing priority — work on edge, not
the combiner — and it is now backed by a direct measurement rather than an inference.

**I am also retracting my own trend call.** Rule 154 upgraded ALPHA's cost ratio to a four-run trend. It
broke immediately: ALPHA `totalPnl` read **$15.27790493** and then **$17.08569992** this cycle, against the
prior **$7.64428496**. PnL is rising. Only `feesPaid` is still monotone (**$73.682486** → **$78.463912** →
**$79.469996**). Four monotone points were not enough to call a trend on a book this noisy, and acting on
it would have been the overfitting the contract warns against.

Next cycle, once the revert scores, item #1 is the target instability itself — damping the combined
forecast over a horizon matched to its own decay, or re-planning at that horizon — with an ADR in the same
commit, since it changes how every target is formed.

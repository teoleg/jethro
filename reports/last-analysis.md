# Last analysis — 2026-08-07 19:00Z

**No code change (the pending one is at 5/6 of its measurement window) — instead I stress-tested my own
proof metric against 174 archived reports, found it too noisy to grade anything, and replaced it with one
that has a measured noise floor.**

## Situation triage

**1. Money.** Total PnL **-$1,015.34**, up **+$0.76** on the last run and **+$8.63** across the last three.
Still UNDERWATER. The deterministic heartbeat puts 3-iteration growth at **-1.23%** against the **+1.0%**
target — `on_track=False`, `stale=True`. The book is not bleeding run-over-run, but it is not earning
either, and it is off target.

**2. Risk.** Gross exposure **$12,500.46** — **0.8%** of the $1.5M firm cap, **$1,487,500** of headroom.
Net **-$25.38**, 0.0% of the $1M net cap. VaR95 **$97.27**, ES95 **$122.72**, breaker `halted: false`. No
`NEAR FIRM CAP` and no `DANGER` flag. The book is trading, not dormant — but at under 1% of its allowance
it is still badly under-deployed against ADR-0132.

**3. Cause.** Nothing was deployed this cycle or last. `3c43242ba` (the manual completion of the failed
ADR-0144 auto-revert) is at **5/6** in its ADR-0116 window and remains unscored. Its own defect is ✅
**VERIFIED for a fourth consecutive window**: `fusion exit — target decayed to flat` fired on WMT and NVDA
at 18:35:39 and BAC at 18:27:02, and `uptimeSeconds` **8651** at a 19:00:01Z stamp derives a JVM start of
**16:35:50Z** — the same continuous process as the previous three verifications, so this is not a boot
transient.

**4. Danger.** No. Not near the exposure cap, not near the drawdown breaker. The inverse condition applies:
this is an under-deployed book, which is an opportunity, not a risk to cut.

**5. Order-level post-mortem.** The window's ALPHA orders re-confirm item #1's mechanism on two names. BAC
sold 156 at `forecast=-7.38` (18:39:12) then bought back 119 across five orders by 18:42:45, the last four
at forecasts between **+0.0023 and -0.288** — a full round trip in under four minutes, at a forecast
indistinguishable from zero. KO sold 45 at `fc=-5.04` then bought back 117 across nine orders as the
forecast walked from -5.04 through zero to **+12.89**. No trigger here opened a *deliberate* loser; the
losing trigger is the round trip itself, and its cost is the fee.

**6/7. Change vs market — attribution.** The window's **+$0.76** and the **-$7,144.32** gross move are
credited to **nothing**. No logic was deployed in either window; `git log` shows only `docs/` and
`chore(status)` commits. This is market on positions I did not choose, and I claim no credit for the PnL
being up.

## What I actually did this cycle

Last cycle I withdrew item #1's VERIFY-BY after it drifted 4× on a no-deploy window, and replaced it with
"Δ cumulative turnover ÷ mean gross exposure over the full 6-cycle window" — **asserting** that cumulative,
monotone endpoints would cure the drift. This cycle I tested that assertion rather than trusting it, by
script, over 174 archived report zips, measuring dispersion only across windows where nothing shipped.

**It fails too.** At the 6-cycle horizon its CV is **0.53** with a 7.7× max/min spread, and its siblings
fail with it — turnover/cycle CV **0.45**, fills/cycle CV **0.47**, mean fill size CV **0.42**. The common
flaw: all four are *rates of activity*, and this book's per-window activity is the noisiest thing about it.
Making the endpoints cumulative does not help, because the difference of two cumulative quantities is still
a rate. Aggregating to 6 cycles does not help either — it moved CV from 0.72 to 0.53 and no further.

The same sweep found one metric that survives, and it is different in kind: the **ALPHA same-name
direction-reversal rate**, a *proportion measured inside the window*, so the window's own volatility sits
in numerator and denominator together and cancels. Pooled over 6 reports with a ≥150-pair sample gate it
reads **CV 0.29**, range 0.083–0.252 across n=17 no-deploy blocks. Current value **0.1955** (35 reversals
of 179 pairs); a damper must drive it below **~0.080** — a **58% cut** — to clear 2 sd. That threshold is
recorded in the register in advance and will not be moved afterwards.

## Why no code change, and what happens next

Two independent reasons. The contract freezes new code while `reports/.pending-baseline.json` exists and
the scorer reports 5/6. And separately, I had no admissible way to prove the damper worked until now — the
two previous cycles would each have graded it on a metric that swings several-fold on market conditions
alone, which is how a loop convinces itself a null change succeeded. Item #1's mechanism was never in
doubt; only its measurement was, and that is now repaired with a stated noise floor and a pre-committed
detection threshold. Next cycle the pending change scores, and the σ-scaled target hysteresis is ready to
ship against a VERIFY-BY that can actually fail.

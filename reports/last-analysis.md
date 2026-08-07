# Shipped ADR-0145: the desk could be OPENED only at full conviction but CLOSED at none — that asymmetry is the round-trip machine paying half our loss in fees.

## Situation (live, read from this run's report — never authored)

1. **Money.** Total PnL **-$1,188.69**, down **$176.69** since last run and **$163.82** over the last three.
   The book is bleeding, and it is UNDERWATER.
2. **Risk.** Gross **$41,355.64** = **2.8%** of the $1,500,000 firm cap, **$1,458,644** of headroom; net
   **-$25,490.04** = **2.5%** of the $1,000,000 net cap. Gross rose **+$29,438.14** this window. That is not
   danger — it is a book coming off dormant into an almost entirely unused budget (ADR-0132). VaR95 **$714.07**,
   ES95 **$961.45**, breaker not halted.
3. **Cause.** The change under measurement, `3c43242ba`, was **scored this cycle: ❌ BAD**, and its auto-revert
   failed on a git conflict. **I did not complete that revert, deliberately** — `3c43242ba` *is* the revert of
   ADR-0144, which the scorer graded ❌ BAD one window earlier. Reverting it would put the condemned ADR-0144
   code back in the running book. Two mutually exclusive changes cannot both be reverted, and re-instating a
   reverted idea is the one thing the contract forbids. Recorded in `reports/must-fix.md`.
4. **Danger.** No. Bleeding, yes — but at 2.8% of the gross cap with the breaker cold, this is the DORMANT-side
   failure (unused budget), not the near-the-cap one. De-risking would be the wrong move.
5. **Change vs market.** Nothing deployed in the last two cycles (`git log` shows only `docs/` and
   `chore(status)`); `uptimeSeconds` **10452** at a 19:30:01Z stamp derives a JVM start of **16:35:49Z**, one
   continuous process across five verifications. **So the entire window's PnL and exposure move is market and
   pre-existing logic — none of it is creditable or blameable on a change.**

## What I found, and why it is different from the last four cycles

Four cycles have confirmed item #1's *mechanism* — "the fusion target tracks a ~90-second mean-reverting
forecast 1:1" — without ever finding its **cause**. The cost is not in dispute: `totalFees` **$488.284665**
against `firmTotal` **-$1,188.68635665** is **41.1%** of the whole cumulative loss (**49.0%** on ALPHA alone,
$465.190115 of -$949.70770444), across **$5,716,068.55** of LIVE turnover and **3,319** fills — while the three
large-n LIVE hit rates sit at **0.490 / 0.501 / 0.498**. Half the loss is paid in fees to trade a coin flip.

The cause is one line of `FusionLifecycle.tick`: the ADR-0059 conviction floor is applied **only to
`!reducing`**. A name may be **opened** only at `|f| ≥ 5.0` and **closed at nothing at all**. Because
`TargetPlanner.targetQuantity` is *linear* in the forecast, a forecast that merely **decays** toward zero
collapses the target and unwinds the whole position — at a strength that would not have been allowed to open a
single share of it. Read from this window's own FILLED LIVE ALPHA orders: **AMZN `BUY 34` at f=+9.25 (18:54:25),
`SELL 26` at f=+0.0688 (18:59:59)** — the forecast never changed *sign*; 82% sold back five minutes later.
**BAC `SELL 156` at f=−7.38 → `BUY 1`×4 at f≈+0.002…+0.10 → `BUY 115` at f=−0.288**, inside four minutes.
`f ≈ 0` is the combiner saying it has *no view*. No view is a reason to **hold**, not to liquidate.

## The change

**ADR-0145 — apply the conviction floor to the EXIT as well as the entry.** A Schmitt trigger: enter on
conviction, exit on conviction, do nothing in between. It gates **only** the reduction the *forecast* authored;
a reduction a *risk control* authored always routes in full, separated exactly by capturing the planner's
target before any control runs. It introduces **no number** — the threshold is
`jethro.fusion.min-forecast-to-route` itself. A flat target returns before any of it, so the ADR-0086
chandelier cut, the ADR-0065 unwind and the ADR-0027 breaker keep exact semantics; the whole deterministic
floor is untouched. Deliberately **not** ADR-0133, which widened the *band* and was scored ❌ BAD — a wider
band vetoes weak-conviction *names*; this filters weak-conviction *exits*.

The honest risk: losers are held longer. That is what the ADR-0086 trailing σ cut exists for, and it is exempt
here — so this moves the exit decision **from the forecast to the risk sensor**, which is the owner's stated
thesis. Expect gross to RISE; that is the ADR-0132 intent at 2.8% of the cap, not a relaxed limit.

## How it gets graded

`scripts/reversal-rate.py` (new, committed) computes the register's proof metric from `recent_orders` so the
loop never authors it: the ALPHA same-name direction-reversal rate, pooled over the full 6-report window.
Baseline **0.1975 (32 of 162 pairs)**; it must fall **below ~0.080** against a measured no-deploy noise floor
of mean 0.190 / sd 0.055; pooled sample gate **≥150 pairs** or NO VERDICT. Guards: gross must not fall and
`firmTotal` must not deteriorate. `./gradlew -Pci test` green.

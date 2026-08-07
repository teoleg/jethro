# Last analysis — 2026-08-07 17:30Z

**No change (the ADR-0116 window on `3c43242ba` is still open) — but the cycle bought a real diagnosis:
the desk has traded ~$5.49M of turnover to carry an $11.4k book, and the fees on it are 46% of the
entire cumulative loss, while not one signal source measures better than a coin flip.**

## Situation (live, read from this run's report — never authored)

1. **Money.** Total PnL **$-1,019.60**. Down **$15.88** on the window and **$110.82** across the last
   three runs. Bleeding slowly, UNDERWATER, and off the +1%/3-iteration target (`pnl_growth_pct`
   **-9.25%**, `on_track=False`, `stale=True`).
2. **Risk.** Gross **$11,433.64** = **0.8%** of the firm cap $1,500,000, headroom **$1,488,566**; net
   **$-614.90** = **0.1%** of the $1,000,000 net cap. Gross fell **$5,696.43** on the window. No
   `NEAR FIRM CAP` flag, no breaker. The book is barely deployed — under ADR-0132 that undeployed
   capital is the failure, not the safety.
3. **Cause.** Last cycle's change was the hand-completed revert of ADR-0144 (`3c43242ba`). It is still
   **ungraded** — the ledger's newest row is `403a95ffd` at 16:30:09Z and `reports/.pending-baseline.json`
   is present. The contract freezes new code while a change is under measurement, so I made none.
4. **Danger.** No. Bleeding, but at 0.8% of the cap with $1.49M of headroom. The danger state
   (bleeding *at* the cap) does not apply; the opposite problem does.

## Step 0 — `3c43242ba`: ✅ VERIFIED, and this cycle closes last cycle's qualification

Last cycle I passed this on a categorical count but flagged honestly (Rule 470) that all six
`fusion exit — target decayed to flat` rows fell inside the first six minutes of the process, so the path
was proven *reachable* but not *operating*. This window settles it. The JVM started **16:35:57Z**
(uptime **3251s**), and exit rows land at **17:22:09** (`WMT SELL 4`) and **17:26:12** (`KO BUY 1`) —
roughly **46 and 50 minutes** into a warmed process, not in the boot transient. The restored leg works in
steady state. Qualification closed; no follow-on defect.

## The finding that should set the agenda: the book is paying to be a coin flip

Two facts, each read straight off the report, that only matter together.

**No source has edge.** `signals_telemetry` mean returns are a rounding error against their own
dispersion: trend `avgReturnBps` **+0.583** on `stdReturnBps` **52.51** (n=836), reversion **+0.254** on
**50.30** (n=792), social **+2.838** on **96.34** (n=307), momentum **-0.717** on **51.96** (n=77). The
big `signal_observations` samples say the same in hit-rate terms — trend **0.501** on n=13,966,
reversion **0.498** on n=13,053, xsreversion **0.489** on n=14,228. Coin flips, at n in the tens of
thousands.

**And it trades enormously.** `turnover_cost_by_name` records **3,191 LIVE fills** and roughly
**$5.49M of turnover** — cross-checked, the per-name fills sum exactly to the 3,191 in `fills_by_day`, and
the per-name fees sum to the **$467.71** `/api/attribution` reports as `totalFees`. That is **~480×
churn** against a gross exposure of **$11,433.64**, at 1.00 bps a side on equities.

Put together: **fees are $467.71 of a $1,019.60 cumulative loss — 46% of it.** Gross of fees the book is
roughly half as underwater. When the signal is a coin flip, PnL is a random walk minus costs, and the
drift *is* the fee rate. That also explains the wall of ⚠️ INCONCLUSIVE verdicts better than any
combiner hypothesis has: no re-weighting of sources that don't predict can outrun a deterministic cost
line, so every such change measures as noise — correctly.

This re-ranks the register. Cost is the only drain here with a **measured dollar figure** and a certain
fix; the entry-cancel ratchet is real but its cost is inferred, and a new signal is speculative. Cost
takes **#1**.

## Item #1 re-measured (nothing edited — a free observation, and it cuts against me)

The ratchet holds in kind: **13 `fusion entry` orders CANCELLED against 9 FILLED**, while **32 of 32**
`fusion reduce` and **3 of 3** `auto-hedge EQUITY` rows FILLED — still zero cancels outside the entry leg.
But my stated VERIFY-BY was survivable cancels (same side, no-smaller size) **falling from 4 toward 0**,
and with no code changed they read **5** this window. It moved the wrong way on its own. That is Rule 461
landing on me a second time: a count this small drifts on sample size, and I should not have set a
threshold on it. Six of the 13 cancels had **no successor entry at all** — the order was pulled and never
replaced — which is the more damning category and the one I will measure going forward.

## Attribution — change vs market, honestly

The window's **-$15.88** and **-$5,696.43** of gross are credited to **nothing of mine**: I deployed no
logic, and the running commit only *removed* code. The PnL is market on positions I did not choose. The
gross decline is the mirror image of last window's **+$5,106.59** rise, and the two together show the
sawtooth plainly — restart, σ warms and the fusion book re-deploys, then the reduce leg (32 fills)
outruns the entry leg (9 fills, 13 cancelled) and grinds it back down. Net across the pair the book is
roughly where it started. I claim neither leg of that; it is the environment operating, observed across
cycles in which I changed nothing.

## What I do next cycle

`3c43242ba` should reach 6/6 and, per Rule 465, I want to see **which clause** decides it — its baseline
opened at `gross_exposure: 13194.49`, non-zero, so for the first time the risk-adjusted return test is
not pre-empted by the exposure clause (item #3's VERIFY-BY, one of two observations in hand). Then the
one change targets **#1, the cost drain**. The leading candidate is the fusion re-plan cadence itself:
entries POST passively at the mark and are swept by the next 30s re-plan while reduces cross, so
lengthening the cadence (or letting a passive entry rest across re-plans when its side and size still
agree) would cut turnover *and* the cancel ratchet with one coherent change. I am not committing to that
shape until the pending verdict is in.

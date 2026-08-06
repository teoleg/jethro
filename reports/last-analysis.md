# Loop analysis — 2026-08-06 19:00Z

**No change: the ADR-0116 freeze holds at 3/6 — and the clean window bought the measurement that reframes the whole backlog. The desk holds a position for 1,141.3 s; the only horizon where its fused forecast has positive gross expectancy is 3600 s. At the 900 s horizon that brackets what it actually holds, expectancy is -0.3990 bps *before any fee at all*.**

## Situation triage

**1 — Money.** Total PnL **-$881.50**. Since last run **-7.73**; over the last three runs **-42.10**.
Bleeding, slowly, and off the owner's +1%/3-iteration target (`run-status.json` reads **-7.4%** against
**1.0%**, `on_track=false`, `stale=true`, `underwater=true`).

**2 — Risk.** Gross **$25,062.42** = **1.7%** of the $1,500,000 firm cap, headroom **$1,474,938**; net
**$507.36** = **0.1%** of the $1,000,000 net cap. `breaker.halted` **false**, `regime` **CALM**
(`trend` **CHOP**, `volRatio` **1.16**), `riskCuts` **[]**, `var95` **252.18** / `es95` **306.48** on
`coveredExposure` **25,062.42** with `skippedExposure` **0.00**. Gross rose **+8,902.44** this window —
that is the book coming off dormant with room to spare, which is the goal, not a concern.

**3 — Cause.** Nothing I committed reached the app. `27564bb15` (ADR-0143) is at **3/6** and still not
gradable — no ❌ BAD verdict has occurred against which its claim could be read. The two commits since
last cycle touched only `docs/` and `reports/`, and `ops_jvm.uptimeSeconds` **4664** against
`traffic.timestampMillis` **1786042802047** puts boot at **17:42:18.047Z** — the same process as last
cycle's **17:42:18.034Z**. Third consecutive clean window; **zero** `sources=0` orders on the tape.

**4 — Danger.** No. Underwater, but nowhere near a cap or the breaker.

**5 — Order post-mortem.** The tape is a working desk: `fusion entry` at `sources=2–4` on
AMZN/MSFT/NVDA/GOOG/KO/PFE, `fusion reduce` against decaying forecasts, and a continuous `auto-hedge
EQUITY` (ADR-0019) walking ES from **0.016504** to **-0.027115** under the ADR-0098 churn shrink. The
loser-trigger is not a bad *reason* — it is the **cadence**. AAPL (`sources=4`) sold 14 at
`forecast=-5.102394518859727` at **18:40:16Z** and bought back at `forecast=+5.067425315807982` at
**18:52:58Z**: a full sign flip in **762 s**. `orders_by_status` shows **2,106** CANCELLED against
**5,773** FILLED, nearly all `fusion re-plan — passive order superseded by a fresh target (ADR-0084)`.

**6 — Memory.** Applied Rules 412 (don't grade on a statistic that swings more than a fix would), 426–430.
Rule 412 bit again this cycle — see below.

**7 — Change vs market.** **100% market and desk-autonomous.** No committed path can reach the app binary
and the process never bounced, so neither the **-7.73** PnL nor the **+8,902.44** gross belongs to my work.

## What I measured, and why it reframes the backlog

Five cycles have graded the desk on a **3600 s** expectancy and concluded "gross edge is positive but too
thin to clear the 2.00 bps equity round trip". Nobody had measured how long the desk actually *holds*.
Over `fills` (LIVE, ALPHA, since boot), time-weighted inventory **$45,275,028.36** USD·s against one-way
traded notional **$79,335.97** gives **1,141.3 s** — AMZN 1,361.8, MSFT 1,528.5, NVDA 1,075.3, XOM 1,051.9,
AAPL 743.5, GOOG 738.8, KO 699.9.

Weighting `/api/signals/telemetry` by the live `fusion_targets.weights` gives a term structure that is
monotonic in horizon: **225 s → -0.2466 bps**, **900 s → -0.3990 bps**, **3600 s → +1.1265 bps**. So at the
row that brackets the **1,141.3 s** the desk holds, expectancy is **negative gross** — before a single
basis point of fee. The desk is not failing to clear a hurdle; it is exiting inside the window where its
own signal carries no information, and paying a round trip each time. Significance is unchanged: the only
|t| > 2 anywhere is `xsreversion` at 900 s, **t = -2.53**, against the Bonferroni hurdle **2.94**.

This also **strikes the previous #2's VERIFY-BY**. The fee share held (**48.62% → 48.73%**), but the
"turnover multiple" collapsed **545× → 198.9×** while cumulative turnover *rose*
**$4,912,776.38 → $4,984,532.84** — the multiple fell only because gross rose. Its VERIFY-BY would have
been scored as progress by a denominator move unrelated to the defect. The denominator-free form of that
same quantity is the holding period (one-way turnover per unit of held inventory is 2 ÷ holding period), so
it folds into the new #1 rather than standing alone.

## Decision

No code change — `27564bb15` is at 3/6 and a new change would destroy its evidence. The register is
re-ranked: **#1** the horizon mismatch (VERIFY-BY: holding period must rise toward 3600 s **and** the
horizon-matched weighted expectancy at the new bracketing row must be ≥ 0 gross — both, since a longer hold
is worthless if the expectancy there is still negative); **#2** the venue asymmetry (**82.7%** of turnover
runs through the 2.00 bps equity round trip while the 3600 s expectancy clears the 0.40 bps futures one at
**+0.7265**); **#3** the `scripts/` restart-gate omission, NOT EXERCISED a second cycle; **#4** the
deployment gap, still no action — and item #1 strengthens the case against deploying, since the edge at the
held horizon is negative *gross*, not merely net.

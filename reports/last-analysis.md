# Fees are 48.62% of the entire loss — the cost side of the edge/cost inequality is the half I can actually attack

**No change this cycle:** `27564bb15` (ADR-0143) is at **2/6** under the ADR-0116 freeze, so the contract
forbids a new change while it accumulates evidence.

**Money.** Total PnL **$-875.66**, down **-24.98** since last run and **-62.78** over the last three —
UNDERWATER and off the +1%-per-3-iterations target.

**Risk.** Gross **$10,169.58** is **0.7%** of the firm gross cap (headroom **$1,489,830**); net
**$3,381.56** is **0.3%** of the net cap. `breaker.halted` false, `regime` CALM / `trend` CHOP,
`volRatio` **1.06**, `riskCuts` empty. Not a danger state — underwater, but nowhere near a ceiling.

**Cause.** Last cycle's commits reached only `docs/` and `reports/`, and the boot instant confirms it:
`ops_jvm.uptimeSeconds` **2864** against `traffic.timestampMillis` **1786041002034** puts the boot at
**17:42:18.034Z**, the same process as last cycle's **17:42:18.442Z**. The app never restarted, so the
whole window is market and desk-autonomous — my changes earn neither credit nor blame. That also **verifies
the ADR-0142 gate as a mechanism**: it passed two exempt commits without bouncing the JVM. Item #1 is the
prefix it omits (`scripts/`), not the gate itself.

**Order post-mortem.** **60** orders, and the only two carrying `sources=0` are the **17:42:57Z** XOM/MCD
REJECTED pair from last cycle's restart — no new one since, so Rule 422's contamination fingerprint is
absent and this window is clean. Entries fired at `sources=3–4`; three exits (BAC, WMT, NEE) fired at
`sources=1` — the desk still dismantles on thinner evidence than it builds on.

**What I spent the clean window on.** The composition item is confirmed a **fifth** time — aim-weighted
3600 s expectancy **+0.1655** bps gross, **-1.8345** bps net of the 2.00 bps equity round trip, against
**-1.7898 / -1.60 / -1.9464 / -1.7605** before it; no cell of 15 clears the Bonferroni hurdle **2.94**
(best |t| is `xsreversion` 900 s at **-2.64**). Notably the aim went **majority trend-following for the
first time** (trend **53.36%**) and the aggregate did not move — Rule 412 again. So rather than re-narrate
the left operand, I measured the right one: `totalFees` **424.560443** against `firmTotal`
**-873.24062320** is **48.62%** of the loss, on **$4,912,776.38** of cumulative LIVE turnover against a
**$10,169.58** book — **545×**. Equities pay **1.00 bps/side**, futures **0.20**. Cost is now **#2**,
above composition, because it is the half of the inequality that needs no new edge to attack.

**And an inversion worth recording.** The desk holds **2.86%** of its own aim (**$10,250.80** against
**$358,644.26**). The mission's dormancy rule would call that the biggest opportunity here. At **-1.8345**
bps net it is the opposite: deploying the aim would scale a negative edge ~35× and multiply the turnover
that already eats half the PnL. Deployment stays **#4** with no action until the aggregate turns positive.

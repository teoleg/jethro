ADR-0133 was scored ❌ BAD and the scorer's own auto-revert failed on a git conflict, so the rejected code was still trading — this cycle completes that revert by hand, and the lesson is that unsticking low-conviction names buys spread, not return, while nothing upstream has edge.

*(Every figure below is read from `/api/risk`, `/api/ops/jvm`, `logs/report.md`, the scorer's ledger row
or the live app. None is authored here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** SITUATION header: total PnL **$227.68**, **-280.06** since last run and **-433.47** over
   three. Live `/api/risk` `.total` minutes later: `totalPnl` **192.23986225** = `realizedPnl`
   **402.49713476** + `unrealizedPnl` **-210.25727251**. Heartbeat `2026-07-31T16:04:57Z`:
   `pnl_growth_pct` **-31.45** vs `pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**,
   `underwater` **false**. Bleeding and off target.
2. **Risk.** Gross **$167,392.97** = **11.2%** of the $1,500,000 firm cap, headroom **$1,332,607**; net
   **-$24,228.68** = **2.4%** of the $1,000,000 net cap, and by the later live read net has crossed to
   **+2,801.04706250**. **Flags: none.** Gross **+85,198.66** run-over-run.
3. **Cause — named, and it is a mechanism, not the tape.** Last cycle's change `e61c7f5aa` (ADR-0133,
   the no-trade band cap) closed its ADR-0116 window and the scorer graded it **❌ BAD**: risk-adjusted
   return per cycle significantly negative against the hurdle, with gross grown **17,957 → 167,401**.
   That gross is exactly what the ADR was built to unlock. It worked mechanically and failed
   economically.
4. **Danger: no.** Bleeding, but at 11.2% of the gross cap with the breaker untripped. De-risking on
   that basis would be the status-quo trap CLAUDE.md names. The danger here is different and worse.

## Step 0 — what I verified, and the defect it exposed

**The graded-BAD code was still live.** The fresh ledger row carries the note `⚠️ REVERT FAILED (git
conflict): the BAD commit is STILL LIVE and needs a manual revert`. I reproduced it: `git revert
e61c7f5aa` conflicts on `docs/loop-findings.md`, `reports/last-analysis.md` and `reports/must-fix.md`
— the loop's **own memory files**, which every cycle rewrites — while the three **code** hunks apply
cleanly. `/api/ops/jvm` `uptimeSeconds` **1646** confirms a fresh JVM was running the rejected code.

This is structural, not bad luck. The scorer reverts with `git revert`, which touches every path the
original commit touched; the loop writes its analysis and findings into that same commit; so **any**
revert attempted a cycle later is guaranteed to conflict and abort. It has now happened three times
(`efccc6502`, `e61c7f5aa`, plus a `revert-failed` heartbeat run). The measurement system's verdict was
being silently discarded — which makes every ❌ BAD grade decorative.

**Verification outranks novelty**, so that is this cycle's one change.

## The change

Reverted the ADR-0133 mechanism from the running code only: `PositionBuffer.band(...)` loses the
`.min(target.abs())` cap and its javadoc, `application.properties` loses the ADR-0133 provenance
paragraph, and `PositionBufferTest` returns to its prior expectations — all byte-for-byte pre-ADR-0133.
**Kept:** `docs/loop-findings.md`, `reports/`, and ADR-0133 itself, now `Status: Reverted` with a "Why
it was reverted" section. Findings are append-only durable memory; a revert must never erase them.
`./gradlew -Pci test` green.

## What the window actually taught — and why this is not a wasted cycle

ADR-0133's derivation was **correct**: the band really was scaled to a full-conviction position while
the interval it was tested inside shrank with conviction, and capping it really did unstick six
permanently-vetoed names. The desk deployed off DORMANT as predicted. It then lost money. So the honest
reading is the opposite of the ADR's: the buffer was **not** strangling a profitable book — it was
incidentally suppressing turnover on names with **no measured out-of-sample edge**, and removing that
suppression converted a dormant book into one paying spread on every name it opened. An execution dial
had been doing a conviction floor's job.

That is a direct restatement of this loop's standing priority: **work on edge, not the combiner or its
execution dials.** Widening what may trade, while no source has demonstrated positive expectancy, buys
turnover and not return — and now it has been measured rather than argued.

## Attribution — market vs change

Both legs are separable this cycle. `unrealizedPnl` **-210.25727251** against `realizedPnl`
**402.49713476**, on a book now near flat net (**+2,801.04706250**), is mark-to-market — **market**. The
**realized** leg is the change: a ~9x gross put on against no demonstrated edge pays spread on every
name it opens, and the scorer's t-statistic over the full window is what separates that from noise, not
my reading of one print.

## Next cycle

Verify the revert landed (`grep -n 'ADR-0133' PositionBuffer.java` returns nothing; `insideBuffer`
returning true across the low-conviction names; gross falling back off its post-ADR-0133 level). Then
the register's item #1 is the hedge's coverage ratio — the hedge sizes off ~6.5% of the systematic risk
it is meant to neutralise. Track **coverage**, never sign.

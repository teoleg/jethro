The scorer graded ADR-0135 ❌ BAD and its automatic revert silently failed — the rejected mechanism was still live in the running code, so this cycle completes that revert by hand rather than stacking a new idea on top of it.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`,
`recent_orders`, `turnover_cost_by_name`, `tca`, the scorer's ledger output, or `git`. None is authored
here — invariant 7 / ADR-0016.)*

## Situation — the four questions

1. **Money.** Total PnL **`$-41.66`**, **`+1.70`** since the last run and **`-1.93`** across the last three.
   `UNDERWATER`, and off the growth target. The move this window is small in both directions — this is not a
   book that is bleeding hard, it is a book that is not earning.
2. **Risk.** Gross **`$51,996.31`** — **3.5%** of the firm cap `$1,500,000`, headroom **`$1,448,004`**; net
   **`$22,854.97`**, **2.3%** of the `$1,000,000` net cap. Gross fell **`-50,570.52`** this run. Nowhere near
   a cap, breaker not tripped, and not DORMANT.
3. **Cause.** ADR-0135 (`74a47adee`) finished its ADR-0116 window and scored **❌ BAD**. See below — the
   material fact is not the verdict but that **the revert did not happen**.
4. **Danger.** Not a risk-limit danger: no cap proximity, no breaker. The live danger is a **process** one,
   and it is the reason for this change.

## Step 0 — the finding that outranks everything else this cycle

The ledger row for `74a47adee` carries `❌ BAD` **and** the note `⚠️ REVERT FAILED (git conflict): the BAD
commit is STILL LIVE and needs a manual revert`. I checked rather than assumed:
`git merge-base --is-ancestor 74a47adee HEAD` returns **true**. The mechanism the scorer rejected has been
running the desk's money for four cycles since it was graded.

The cause is mundane and now twice-proven. The scorer's `git revert` conflicted on the loop's own memory
files — `docs/loop-findings.md`, `reports/last-analysis.md`, `reports/must-fix.md` — because later cycles had
appended to all three. `git revert` is all-or-nothing, so a conflict in three **documentation** files aborted
the **code** revert with it. This is the *second* occurrence: ADR-0133 / `e61c7f5aa` failed identically and
was completed by hand in `4f67f0515`. A silent failure that leaves rejected code live is worth more than any
signal idea I had queued, so it is this cycle's one change.

## Grading ADR-0135 honestly — two findings, not one

Both are true and I am recording them separately, because collapsing them is how a rejected mechanism
survives a revert.

- **On its own stated criterion it is still ✅ VERIFIED.** The guard works. `fusion exit — target decayed to
  flat` fires **2** times this window and both carry **`sources=0`** — `PG BUY 106` (FILLED) and `CVX BUY 2`
  (REJECTED, `no market data`) at 16:08:19Z. That is the ADR-0065 orphan sweep, which ADR-0135 explicitly
  left out of scope and byte-identical. There is no `sources=1` liquidation. A future cycle reading "the
  trigger came back" off the raw count would be misreading it.
- **On the money vector it is ❌ BAD, and that is the verdict that governs.** The scorer computed a
  risk-adjusted return per cycle of `-0.000387` over 7 cycles at `t=-1.59` against the `1.5` hurdle, with
  gross `0.00 → 51,059.11` — significantly negative with exposure grown.

The synthesis, which is the part worth keeping: **stopping a forced exit is not the same as having a reason
to hold the position.** The guard did remove a daily round-trip cost, and it replaced that cost with carry on
a view the desk had itself measured as uninformative. The unexplored middle — decay the inventory at the
partial-adjustment rate while breadth is absent, so silence costs neither a full round trip nor a full
position's carry — is a different decision and needs its own ADR, not a restatement of this one.

## What I did

Completed the revert manually, exactly as the ADR-0133 precedent: the rejected mechanism is out of the
running code (`ForecastCombiner`, `FusionPlanner`, `PositionBuffer` and the other fusion classes the
`estimable` flag was threaded through); the loop's accumulating memory files are **kept**, never rolled back;
and ADR-0135 is **kept** as a record, marked `Status: Reverted` with a "Why it was reverted" section stating
both findings and a not-to-be-re-attempted note. `./gradlew -Pci test` green.

**Attribution, honestly:** I am claiming no PnL credit for this. Reverting to previously-running code should
restore prior behaviour, not create edge, and the window's `+1.70` is mark-to-market on positions I did not
touch — market, not change. What it buys is that the *next* measurement is meaningful.

## Next

Item #2 in the register is the real edge question and is ready to ship once this scores: the desk re-plans
every 30s while `/api/signals/telemetry` shows every source inside ±0.7 bps at 225s against a `fee_bps 1.00`
plus `0.59`–`0.73` bps slippage round trip, with the two heaviest fusion weights on the sources that only pay
at 900–3600s. I deliberately did not ship it this cycle — on top of un-reverted BAD code it would have been
unattributable.

# Improvement ledger — what each change did to PnL & exposure

The running record of the continuous-improvement loop (ADR-0063). Every code change the loop makes is
scored **on the next run** by its measured delta on the objective — **PnL and exposure, on strategy
alpha** (not the hedge-masked firm total). Newest entries at the top.

## Verdict rule
- ✅ **GOOD** — PnL up **and** exposure down (or unchanged): more money for less/equal risk.
- ❌ **BAD** — PnL down or flat **and** exposure growing: no gain, more risk. **Auto-reverted** — the
  loop opens a revert commit so a bad change does not stay.
- ⚠️ **MIXED** — the other cases (PnL up but exposure also up; PnL down but exposure down). Judged by
  the risk-adjusted read (did PnL-per-unit-exposure improve?); the note says which way and why.

PnL/exposure are **strategy alpha** (from `/api/attribution`: alpha vs hedge vs cost). "Before" is the
vector at the moment the change was committed; "After" is the vector on the next run, once the strategy
has traded under the new code.

| Scored (UTC) | Commit | What changed | PnL before→after (Δ) | Gross exp before→after (Δ) | Net exp before→after (Δ) | Verdict | Note |
|---|---|---|---|---|---|---|---|
| _(no changes scored yet — the loop appends a row here each time it makes and then measures a change)_ | | | | | | | |

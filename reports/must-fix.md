# MUST-FIX register — the loop's carried-forward, verified backlog

Maintained by the improvement loop every run (see `ops/improve-prompt.md` Step 0). The point is to CLOSE
the loop: a defect is not "done" until a later run has VERIFIED, from live telemetry, that the fix landed
and worked — so the same problem can't bleed money run after run.

**How it works**
- Newest verification block on TOP. Each open item = a specific defect + a rank + a concrete **VERIFY-BY**
  (the exact metric/endpoint/number that proves it fixed next run).
- Every run: mark each open item ✅ VERIFIED / ⚠️ STILL-BROKEN / 🔴 REGRESSED with the proving number read
  from live telemetry; strike VERIFIED items (move them below the line); re-rank what remains, most-costly
  first. The ONE change per run targets item **#1**.
- Every number here is READ from live telemetry, never authored (invariant 7 / ADR-0016). The scorer still
  owns the PnL verdict; this register owns "did the specific defect get fixed".

---

## OPEN (ranked, most-costly first)

Seeded 2026-07-29 from the loop's own live analysis after the book came off dormant under exploration mode
(ADR-0122) and began bleeding on churn: gross $0 → ~$32.6k while total PnL went +$0.61 → −$80.77.

1. **[OPEN] Churn / round-trip cost IS the loss.** The loop's 2026-07-29 analysis: *"the loss is entirely
   in round-trips — the book the desk still holds is up."* The desk pays spread/impact on turnover faster
   than positions earn. Exploration mode removed the edge gate, which had been the churn suppressor — so a
   churn control has to come from somewhere that is not "prove significant edge before any trade."
   - **VERIFY-BY:** round-trip cost's share of total loss (from `/api/tca` + the turnover-cost report) FALLS
     run-over-run, and total PnL stops bleeding with gross held non-zero. Fixed = PnL flat-or-up over 3
     consecutive runs while gross > 0.

2. **[OPEN] Delayed-price feed cohort loses money.** The loop: *"the delayed-price cohort is 29% of
   turnover and 82% of the loss."* The desk routes on stale/delayed prints and loses on them.
   - **VERIFY-BY:** the delayed-price cohort's loss share falls toward its turnover share (≈ parity) once
     delayed prints are excluded from routing or their round trips are gated. Fixed = loss share ≈ turnover
     share, not ~3× it.

3. **[OPEN] Agreement scaler degenerate at a single source.** The loop: *"the agreement scaler is
   degenerate at one source — full conviction where there is no corroboration."* A single-source name gets
   the same combined-forecast magnitude as a many-source corroborated one, so a thin, uncorroborated view
   sizes as if proven.
   - **VERIFY-BY:** in `/api/fusion/targets`, a single-source name's `|combinedForecast|` is measurably
     DISCOUNTED versus a multi-source corroborated name — not equal. Fixed = single-source magnitudes sit
     below corroborated ones on the same cycle.

---

## VERIFIED / CLOSED
_(none yet — items move here, struck through, once a later run confirms the fix from live telemetry.)_

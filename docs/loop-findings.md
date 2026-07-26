# Loop findings — accumulating trade/change lessons (append-only memory)

Each improvement cycle appends **one** concise, dated finding here: what the window's **orders** and any
**change** did to total PnL and exposure, the **trigger** behind a bad (or good) move, and the **rule for
next time**. The loop reads the recent entries every cycle (situation triage) so lessons **compound**
instead of being relearned. Newest at the bottom. Keep each entry short and specific — this is memory, not
prose. Full per-cycle reasoning lives in `reports/last-analysis.md` and the ledger; this file is the
distilled, durable lessons.

Format per entry:
```
### <UTC timestamp> — <commit sha or "no-change">
- Situation: <PnL/exposure move this window, in $>.
- Cause: <which order trigger / change drove it — name the reason>.
- Lesson / rule: <what to do or avoid next time>.
```

When this grows past what fits in a prompt, it becomes the corpus for **RAG retrieval (ADR-0035)** — embed
each finding + trade outcome and retrieve the relevant ones per situation instead of reading all of them.

---
<!-- findings appended below, one per cycle, oldest first -->

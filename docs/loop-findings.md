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

### 2026-07-26T16:45Z — ADR-0069 (hedge no-trade band)
- Situation: PnL -$866.49 (-$3.26 this window, -$843.66 over 3 runs), gross $6,885.14 / net $4,897.35, both
  flat. The 3-run loss is almost entirely the `fb9273505` round trip's spread+fee cost (185 fills, $130.90
  fees), already scored ❌ BAD and reverted — sunk, not an ongoing bleed. Last cycle's `c12099fea` traded
  nothing at all, so this window's move is 100% mark drift on untouched positions: **market, not change.**
- Cause: `$5,791` of the `$6,885` gross (84%) was a single ES hedge leg the advisor itself wanted at
  `$1,093`. Trigger: ADR-0039's `$10k` absolute min-trade guard. Once the hedged book shrank below the
  guard, *every* delta a small hedge can produce — including its own full unwind — fell under it, so the
  position was untradable in both directions and stranded permanently.
- Lesson / rule: **an absolute-dollar threshold that gates trading becomes an absolute barrier below its
  own scale — always express it relative to the thing it gates, and take the MIN of absolute and relative,
  never the max.** Same failure class as ADR-0068's risk dials. When a `holding`/`ON-TARGET` rationale
  cites a fixed dollar threshold, check it against the *current* book size, not the book it was written for.
- Rule 2: the ledger's PnL-per-$1-gross annotation is degenerate while PnL is negative — cutting unwanted
  exposure makes the ratio look worse. Judge de-risking on the vector (PnL flat, exposure down), not the ratio.

### 2026-07-26T17:15Z — ADR-0070 (continuous mean-reversion forecast source)
- Situation: PnL -$867.83 (+$8.82 this window), gross $6,145 → $785, net $5,418 → $56. The whole gross
  collapse is ADR-0069's hedge unwind — the only orders in the window — so exposure is **100% change**;
  the PnL move is mark drift on untouched stubs plus one commission, i.e. **market**. Book now flat:
  two 1-share stubs and an ON-TARGET hedge leg. Not bleeding; the failure is that it cannot trade.
- Cause: the ADR-0064 gate is reduce-only because every routed source is measured negative (trend
  several SE below zero over 46 obs; momentum and social too) vs a 7.05 bps round trip. Root cause is
  the **source set's composition**: trend/momentum/social are all *continuation* bets, and the desk is
  in CHOP (ER 0.09–0.48; the walk-forward selector picks mean-reversion on every chop name it trades).
  The mean-reversion algo is a *threshold detector* — 1 firing in this feed mode, so it contributes
  nothing to fusion and generates no evidence about itself.
- Lesson / rule: **a threshold detector cannot serve as a fusion source — it is silent exactly when its
  regime is on, so it never accumulates the telemetry the edge gate needs.** Anything the gate is meant
  to judge must publish a reading every cycle. Corollary: when the whole source set is one signal family
  and it measures negative, the fix is a source built on a *different* statistic for the observed
  regime — never the losing source negated (that inherits its biases with the sign flipped).
- Rule 2: attribute exposure and PnL separately. A change can own 100% of the exposure move and 0% of
  the PnL move in the same window; crediting it with both is how a hedge unwind gets mistaken for alpha.

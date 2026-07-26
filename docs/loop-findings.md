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

### 2026-07-26T17:30Z — ADR-0071 (sensors warm-restart from durable mark history)
- Situation: PnL -$867.78 (-$0.38 this window, -$4.55 over 3 runs), gross $785.55 / net $56.36 (-$0.41).
  **Zero orders in the window**, so 100% of both moves is mark drift on untouched stubs — market, not
  change. Last cycle's ADR-0070 reversion sensor scored ⚠️ MIXED "no material change" because there was
  literally nothing to measure. Not bleeding; the failure is that the desk cannot open a position.
- Cause: **sensor warm-up exceeds process lifetime.** `reversion` needs 241 prices × 10s ≈ 40 min of
  continuous uptime before it publishes; `trend` needs 193 × 5s ≈ 16 min. The loop redeploys every
  14–55 min (uptime at report time: 172s) and all sensor state is heap. So reversion *never* speaks and
  is invisible to the edge gate, and every one of trend's 46 observations — the t=−6.9 that holds the
  gate reduce-only — came from a barely-warmed instrument. A deployment defect masquerading as a signal
  result. Fixed by replaying the already-durable LMDB mark history (12h, ~1 Hz, continuous across
  restarts: 1787 pts/92 min, max gap 60s) into each sensor on first sight of a name.
- Lesson / rule: **any stateful sensor must have its warm-up compared against the deploy cadence, not
  just against its own span.** A warm-up longer than the process lifetime is silent dead code, and a
  warm-up comparable to it produces permanently minimum-sample calibration — both look like "the signal
  doesn't work" in the telemetry. When adding a sensor, state its warm-up in wall clock and check it.
- Rule 2: **a source that publishes nothing cannot be falsified.** Before concluding a new source has no
  edge, verify it actually appeared in `fusion_targets.weights` / `signals_telemetry`. Absence from the
  weights map is the tell — ADR-0070's `reversion` never appeared there once.
- Rule 3: seed a sensor from history through its *ordinary* update path, thinned to its own cadence, and
  never record seed prices as telemetry calls — replaying at the tape rate redefines its horizon, and
  counting historical prices as calls fabricates track record for the gate that is about to judge it.

### 2026-07-26T17:45Z — ADR-0071 correction (seed anchored on the feed clock)
- Situation: PnL -$867.28 (-$0.10 this window, +$9.36 over 3 runs), gross $785.99 / net $56.85 (+$0.79).
  **Zero orders in the window** — 100% of both moves is mark drift on two untouched stubs: market, not
  change. The DANGER/"EXPOSURE RISING" flag fired on $0.79 against a $786 book; against VaR95 $6.87 and an
  untripped breaker that is a threshold artifact, not a risk build. Not bleeding — the desk simply cannot
  open a position.
- Cause: last cycle's ADR-0071 warm restart **never actually ran**. It computed the seed window from
  `System.currentTimeMillis()` while the mark store is keyed by **provider** timestamps, and this feed runs
  ~30 min behind wall clock (lag confirmed growing monotonically across four report snapshots). The window
  therefore sat in the feed's future and admitted a ~20 s sliver — 4 samples against warm-ups of 193
  (trend) and 241 (reversion). Reversion still never appeared in `fusion_targets.weights`.
- Lesson / rule: **any lookback, retention or staleness window applied to a provider-keyed store must be
  anchored on provider time, never wall clock.** The two clocks differ by the feed's delay (invariant 5 is
  why both stamps exist; `/api/feeds` reports `delayed`/`delaySeconds` because lag is normal). The failure
  is silent, it reports success, and it looks exactly like "the signal doesn't work".
- Rule 2: **the loop was structurally blind to the platform's own logs.** `system-report.py` captured only
  `docker compose logs`, and the app runs on the host — so every WARN/stack trace the trading platform
  emitted was missing from the report. The evidence for this bug was printed on every boot for two cycles
  and read by nobody. Fixed here. When a change "does nothing", check the app log directly before
  concluding anything about the signal.
- Rule 3: a fix that only restores *measurability* will still score "no material change" — expect it, say
  so up front, and judge it next cycle on whether the source now appears in the weights map, not on PnL.

### 2026-07-26T18:00Z — ADR-0072 (per-name execution cost in the edge gate)
- Situation: PnL -$867.73, unchanged run-over-run and effectively flat over three. Gross AND net
  exposure now **$0.00** — the window's only orders were the ADR-0065 planner's last flattening leg
  (1-share buys closing the ALPHA shorts + matching HEDGE ES trims) under a reduce-only gate. Not
  bleeding, no danger state, nothing to de-risk: with zero exposure the ONLY way PnL can move is to
  trade, and the gate forbids it. `on_track: true` is an artifact of the hedge unwind three runs ago.
- ADR-0071 verdict, honestly: the warm restart now **does run** (app log: seeded 60/193 trend,
  175/241 reversion — up from 4). Replaying the seed algorithm against the live LMDB series yields
  193/236 today, so the binding constraint was never the code after the clock fix — it was the store's
  **provider-time depth at boot**, which grows each cycle and has now passed both warm-ups. Sensors
  boot warm from here; `reversion` should publish for the first time. Plumbing is done — stop working it.
- Change: the gate charged ONE blended round trip (6.9270 bps) across a universe whose measured costs
  span 69:1 (ES 0.2912 vs GOOGL 20.1056). Once a source passes at e.g. +18 bps gross, the planner sizes
  GOOGL too — **−2.11 bps per round trip by arithmetic**. Now each name is re-tested against its own
  measured round trip; it can only subtract permission, never grant it.
- Lesson / rule: **a gate that compares edge to cost must compare them at the granularity cost is
  incurred.** A blended hurdle is only defensible when the cost cross-section is homogeneous; at 69:1 it
  is simultaneously too strict (suppresses surviving edge in cheap names) and too loose (admits certain
  losers in expensive ones). Check the dispersion before pooling any per-name quantity into one number.
- Rule 2: **when exposure is zero and the gate is shut, no change can move the vector — say so up front
  and spend the cycle on what makes the FIRST trades good, not on faking activity.** Expect ⚠️ "no
  material change"; the honest test is next cycle's order-level post-mortem, not this cycle's PnL.
- Rule 3: **do not "fix" an input that also happens to relax your own test in the same change.** GOOGL's
  10 bps slippage looks like a provisional 20 bps refdata spread on a mega-cap, and repairing it would
  lower the cost hurdle. That is a real data-quality item — make it on its own merits, separately, or it
  is indistinguishable from tuning the measurement until it passes.

### 2026-07-26T18:30Z — ADR-0073 (the daily close series is feed-mode scoped)
- Situation: PnL -$867.73, unchanged run-over-run and flat over three; gross AND net exposure $0.00,
  VaR "no positions", breaker untripped. Not bleeding, no danger state. The window's only orders were
  the ADR-0065 flattening tail (1-share ALPHA closes + matching HEDGE ES trims) under a reduce-only
  gate — **no trigger opened a position**, so 100% of the (nil) move is mark drift + prior policy:
  market, not change. ADR-0072 scored ⚠️ MIXED "no material change", which is what it predicted of
  itself; with a shut gate it can only subtract permission, so there was nothing for it to act on.
- **`reversion` finally publishes** — first appearance in `fusion_targets.weights` and in per-name
  contributions, 23 open / 0 resolved. ADR-0070 + ADR-0071 both work; its calls resolve one signal
  horizon out, so the edge gate can judge it in a cycle or two. Plumbing is DONE — stop working it.
- Change: `daily_close` was the one EOD artifact never scoped by feed mode. `EodService.rollover()`
  and `MarketHistoryRecorder.recordOnce()` each write `firm_equity` WITH `feed_mode` and, three lines
  away, `daily_close` WITHOUT it — so LIVE and SIM closes shared one series. The handover return is
  then the ratio of two unrelated price levels: AAPL 190.00 (seed) → 326.95 (live) = a fabricated
  +72% day, then −43%, +76%, −42%, all inside the last eight observations that EWMA weights hardest.
  Result: measured daily vol overstated 10.1× (GOOG), 10.7× (AAPL), 12.4× (JPM), 11.9× (ES) — and that
  vol vol-targets position sizing and feeds VaR.
- Lesson / rule: **when two writes sit in the same method and only one carries `feed_mode`, that is the
  bug — go look.** Both writers had `firm_equity` correct and `daily_close` wrong, three lines apart.
  Grep every persisted series against invariant 8 the same way; a missing mode column is silent and
  reads exactly like volatility.
- Rule 2: **row filtering alone does not remove a cross-mode artifact — the boundary PAIR does.** After
  restricting to the running mode + seed, the one return spanning the handover is still there. A return
  is admissible only when both endpoints come from the same stream. Costs one observation per boundary;
  a gap the estimator handles, unlike a fabricated 72% day.
- Rule 3: **a private clean copy of a shared series is a bug report.** `training_bars` (ADR-0038) exists
  partly because the runtime series is "sim-contaminated at the tail" — the workaround was written and
  the cause left in place for every other consumer. When you find a duplicate store justified by "the
  original is dirty", fix the original.
- Rule 4: backfill a provenance column from the platform's OWN record (`firm_equity.feed_mode`), never
  from a rule about prices — a "returns bigger than X are fake" threshold is an invented number gating
  risk, and it would silently eat the real gap risk VaR exists to measure. Rows whose mode cannot be
  established are inadmissible observations, not rows to guess at.

### 2026-07-26T19:00Z — ADR-0074 (credibility counts the sample the estimate was made from)
- Situation: PnL -$867.73, identical run-over-run and flat over three; gross AND net exposure $0.00,
  VaR "no positions", breaker untripped, hedge axis FLAT. Not bleeding, no danger state. The window's
  only orders were the ADR-0065 flattening tail (1-share ALPHA closes in AAPL/GOOG + matching fractional
  HEDGE ES trims) under a reduce-only gate — **no trigger opened a position**, so 100% of the (nil)
  move is mark drift + prior policy: market, not change. ADR-0073 scored ⚠️ MIXED "no material change",
  exactly as it predicted of itself with a flat book.
- Change: the published fusion weights were `reversion/mean-reversion/momentum/social = 1.0`, `trend
  = 0.2732572` — every source at FULL trust except the one with the largest sample. Mechanism: the
  evidence statistic Φ(t) is built from `avgReturnBps`/`stdErrorBps`, which average over ALL resolved
  observations (flats included, correctly — a flat call earned nothing). The Bühlmann credibility term
  counted only wins+losses. Flat rates are a property of a sensor's horizon and dead-band, not of its
  evidence: momentum 61% flat (7 decisive of 18), social 67% (4 of 12), mean-reversion 100% (0 of 2) —
  all under the hard min-sample floor, all pinned at 1.0 while carrying measured-negative expectancy
  (−11.30 / −8.17 / −0.48 bps). Fixed: credibility counts `resolved`; the floor is removed.
- Lesson / rule: **a credibility/confidence term must count the observations its own estimate averaged
  over.** Measuring confidence in statistic A with the sample size of statistic B is silent and reads
  exactly like "that source has no track record". Whenever a method uses two counts, check they are the
  same set — here `stdErrorBps()` divided by `resolved` three lines from a credibility term dividing by
  `wins+losses`.
- Rule 2: **a hard floor at the NEUTRAL value is not neutral.** Clamping a below-average source to 1.0
  is strictly MORE trusting than its own shrunk estimate, so a "safety" floor laundered measured-negative
  evidence into no-evidence. If a continuous shrinkage term already handles thin samples, a floor on top
  is a second, asymmetric copy of it — delete it rather than tune it.
- Rule 3: **check whether a gating counter is one the subject can structurally never satisfy.** A
  threshold detector resolves ~every call FLAT, so `mean-reversion` had zero decisive observations and
  could never have differentiated no matter how long it ran — the same shape as the ADR-0071 lesson
  (warm-up longer than process lifetime). Ask what the counter looks like in the limit, not just today.
- Rule 4: with a flat book and a shut gate nothing can move the vector — expect ⚠️ "no material change"
  and say so up front. The honest test is what the FIRST trades look like once `reversion` resolves,
  not this cycle's PnL.

### 2026-07-26T19:30Z — ADR-0075 (per-name cost on BOTH sides of the edge gate)
- Situation: PnL unchanged run-over-run and flat over three; gross AND net exposure zero, VaR "no
  positions", breaker untripped, hedge FLAT. Not bleeding, no danger state. The window's only orders
  were the ADR-0065 flattening tail (1-share ALPHA closes + matching fractional HEDGE ES trims) under a
  reduce-only gate — **no trigger opened a position**, so 100% of the (nil) move is mark drift + prior
  policy: market, not change. ADR-0074 scored ⚠️ MIXED "no material change", as it predicted of itself
  (it rotates conviction between sources and cannot scale exposure).
- **New fact: `reversion` is the first source with a POSITIVE measured expectancy** — 23 resolved, 7W/2L,
  and 23 still open, so it clears the 30-observation minimum within a window or two. Every other source
  is significantly negative and cannot be rescued by anything. The gate's behaviour on a passing source
  stopped being academic this cycle.
- Change: the gate compared each source's expectancy to ONE blended round-trip cost. ADR-0072 had already
  established that cost is a per-name property and fixed the half where the blend *under-charges*
  expensive names (the veto). The other half was still live: the blend *over-charges* the cheap names, so
  an edge that survives the index future's round trip is refused everywhere because the AVERAGE name costs
  a large multiple of it — and `reversion`'s measured expectancy sits exactly in that band. Now one rule:
  a name may increase when some source's expectancy survives THAT name's own measured round trip at the
  same t-hurdle and minimum sample; the desk-wide verdict is the same test at the cheapest round trip the
  desk can actually pay; an unfilled name is charged the measured blend (never an invented cost).
- Lesson / rule: **when you fix half of a granularity defect, write down which half you left.** ADR-0072
  recorded the correct rule — "compare edge to cost at the granularity cost is incurred" — then applied it
  to only one of the gate's two comparisons, and the unfixed half was the one that kept the book flat for
  four cycles. A one-sided fix to a two-sided error reads as done.
- Rule 2: **before loosening any gate, prove the monotonicity out loud.** New ⊆ old for every name costing
  more than the blend (significance replaces a raw comparison of means); identical for unmeasured names;
  only a name measured CHEAPER than the blend can gain permission. And a measured-negative source clears
  nothing at any cost, since its surplus is negative even at a zero round trip. That is what distinguishes
  this from the continuous risk-appetite gate that scored ❌ BAD — that one let below-hurdle sources size.
  If you cannot state the containment, you are tuning, not fixing.
- Rule 3: **two bars for the same question is a defect even when both look reasonable.** The desk-wide test
  was a t-test and the per-name veto a raw comparison of means, so a name that ate 94% of the edge passed
  the per-name bar while a larger surplus could fail the desk-wide one. Whenever the same comparison is
  written twice, check they are the same shape.

### 2026-07-26T19:45Z — ADR-0076 (diversification multiplier on the weights actually used)
- Situation: PnL unchanged run-over-run and across three; gross AND net exposure zero, VaR "no positions",
  breaker untripped, hedge FLAT. Not bleeding, no danger state. The window's only orders were two 1-share
  ALPHA buys with matching fractional HEDGE trims under the reduce-only gate — **no trigger opened a
  position**, so 100% of the (nil) move is mark drift on a flat book: market, not change. ADR-0075 scored
  ⚠️ MIXED "no material change", as it predicted of itself.
- **The gate is not the binding constraint, and five cycles were spent as if it were.** `reversion` has 23
  resolved observations against a min-sample of 30, and its t-statistic against a **zero** round trip is
  still under the 2.0 hurdle. No amount of cost refinement — ADR-0064/0072/0075, three cycles of it —
  reaches a source that fails at zero cost. Rule: **before refining a gate again, evaluate it at its most
  permissive input.** If it still refuses at cost = 0 / hurdle = floor, the binding constraint is the
  evidence, not the gate, and further gate work is guaranteed inert.
- Change: `ForecastCombiner` took the diversification multiplier from the COUNT of contributing sources,
  under the precondition its own javadoc stated — "for n *equally-important* forecasts". Since ADR-0067
  made trust evidence-driven that has been false: weights span [0.25, 3.0] and the live vector was
  `reversion 2.52` vs `trend 0.25`, i.e. 91% of the vote on one source drawing the full two-equal-source
  multiplier. Now `DM = 1/√(Σwᵢ² + ρ(1−Σwᵢ²))` — the same formula at the inverse-Herfindahl EFFECTIVE
  number of sources. No new dial.
- Lesson / rule: **a javadoc precondition is a live assertion, not prose.** "For n equally-important
  forecasts" was written when weights *were* equal and quietly became false when ADR-0067 shipped. When a
  change makes an input non-uniform, grep every consumer that assumed uniformity — the count-based DM was
  three commits downstream of the change that invalidated it and nobody re-read it.
- Rule 2: **a floor that keeps a bad source "contributing" also keeps it *counting*.** ADR-0074 recorded
  that MIN>0 leaves "the active-source count, and with it the diversification multiplier, unchanged" — and
  read that as a neutral property. It was a cost: the worse a source is measured to be, the more
  concentrated the weights, and the more a count-based breadth over-levers. Whenever a knob is described
  as leaving something "unchanged", ask whether unchanged is *correct*, not just safe.
- Rule 3: monotonicity stated out loud (per the ADR-0075 rule): Σwᵢ² ≥ 1/n by Cauchy–Schwarz and the
  denominator is increasing in Σwᵢ² for ρ<1 ⇒ the new DM is never LARGER than the old, identical at equal
  weights (cold start, `weights.mode=equal`), and never below 1. Exposure can only fall. That is what
  distinguishes it from the continuous risk-appetite gate that scored ❌ BAD.
- Known-but-not-shipped: every source emits its whole cross-section in ONE ~200ms batch per horizon (23
  names at 19:08:21.449–19:08:21.656), yet `stdErrorBps = σ/√23` treats them as 23 independent draws.
  Effective n is nearer 3 after cross-sectional correlation, so every t-statistic on this desk — the edge
  gate's and the weights' alike — is overstated ~2.8×. Not shipped this cycle because the correction only
  makes an already-shut gate stricter; it belongs with whatever change re-opens trading.

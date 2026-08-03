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

### 2026-07-26T20:05Z — ADR-0077 (expectancy standard error across emission cohorts)
- Situation: PnL frozen and entirely realised; gross AND net exposure zero — the book is completely flat,
  no positions, last fill ~2h ago. Not bleeding, no breaker risk, but no forward earning power either.
  The window's orders were two 1-share ALPHA buys with matching HEDGE trims under the reduce-only gate —
  **no trigger opened a position**, so there is no market component and no change component to attribute:
  with zero exposure neither could act. ADR-0076 scored ⚠️ MIXED "no material change", the **eighth**
  consecutive inert cycle inside the fusion/gate/weights subsystem.
- **The previous cycle's known-but-not-shipped item was worse than estimated, and it was about to cost
  real money.** Grouping resolved observations by entry time: `trend`'s 115 are FIVE hourly bursts of 23;
  `reversion`'s 23 — the only positive source, the one the gate waits on — are **ONE** burst (18:08:17,
  resolved 19:08). `σ/√resolved` divided by √23 a dispersion measured across names *within the same hour*.
  Measured understatement on the live trend cohorts: `12.8985/√5 = 5.7684` vs `27.7797/√115 = 2.5905`,
  i.e. **2.23×**, t −2.31 read as −5.25. The estimate was 2.8× — the truth was worse for trend and total
  for reversion (no standard error exists from one draw).
- Change: `SignalScoring.aggregate` groups observations into cohorts by entry time (`jethro.signals.
  cohort-window-seconds`, 60s, mine — merging is the conservative direction) and estimates Fama–MacBeth:
  mean of cohort means, `stdErrorBps = sd(cohort means)/√B`, zero when B<2. Staggered emitters
  (momentum/social/mean-reversion) come out byte-identical; equal-sized cohorts leave the point estimate
  exactly unchanged. No dial value changed; `resolved`/`minSample`/`tHurdle` untouched.
- Lesson / rule: **the timing of observations is part of the sample, not metadata.** A source that scores
  its whole cross-section in one sweep produces ONE draw, not N. Before trusting any t-statistic on this
  desk, `group by entry time` first and count the *bursts* — if B is small, the t is decoration. The
  previous cycle flagged the mechanism from the log timestamps but deferred it as "only makes an already-
  shut gate stricter"; that read missed that the gate was one resolution from OPENING on it.
- Rule 2: **"inert" is not the same as "harmless to defer".** Eight cycles of gate refinement scored no-
  material-change because nothing traded, and the tempting conclusion was that anything in that subsystem
  is inert. But an inert path becomes live the moment the gate flips, and the flip was scheduled by an
  accruing counter, not by a decision. When a change is deferred because "nothing trades right now", ask
  what *starts* trading and when — a deferral is only safe until that date.
- Rule 3: an overstated standard error is not conservative in either direction. The same inflation that
  was about to certify `reversion` also made `trend` look decisively anti-predictive (t −5.25 vs −2.31)
  and pinned it at the MIN weight floor. Fixing the statistic corrects both readings at once.
- Next lever (deliberately NOT this change): evidence now accrues at one independent draw per source per
  hour, which is slow and is the real binding constraint. Raise the **emission rate** — overlapping
  cross-sections on a ~10-min cadence against the same 1h horizon — with a Newey–West/Hansen–Hodrick
  correction for the induced overlap. Order matters: raising the rate on the i.i.d. estimator would have
  inflated significance faster still, so it had to come second.

### 2026-07-26T20:35Z — ADR-0078 (fusion sizes in the instrument's own contract terms)
- Situation: the book traded for the first time in nine cycles and it **worked**. Flat at both endpoints
  of the window, so there is no mark-drift component whatsoever — 100% of the ~+$42 move is attributable
  to the trades, 0% to the market. Only MSFT + its ES hedge leg traded (~33 fills, 20:08–20:21) and the
  round trip netted positive after fees. The trigger was ADR-0077 behaving exactly as designed:
  `reversion` had ONE resolved cohort, so its standard error read zero, the gate could not certify it,
  and when the second cohort landed a real standard error appeared and re-shut the gate on every name but
  ES. Nothing here to revert.
- **The danger was not where the flags pointed.** The BLEEDING flag fired on a −$31 move that was just an
  intra-window unrealised peak being closed out; meanwhile the fusion target book was quietly carrying
  `ES targetQty −8.799423` with a queued `−4.399712`-contract delta. ES multiplier is **50**, so that one
  delta is ~$1.2M of gross against an intended `unit-notional` of $50k, on a book whose recorded exposure
  peak is ~$415k. `TargetPlanner.targetQuantity` divided cash-at-risk by `price` when it had to divide by
  `price × contractMultiplier` — the money value of one unit. `PositionRisk` (`qty · mark · multiplier`),
  `Positions.applyFill` and the ADR-0039 hedge advisor (`qty = −Σβᵢ·Eᵢ/(price × multiplier)`, on this very
  instrument) all had it right; the fusion sizer was the only place that didn't. Error: 50× ES, 20× NQ,
  1000× the note futures, 45,000–80,000× the swaps. Equities and FX (multiplier 1) were always correct.
- Change: `targetQuantity` divides by `price × multiplier` so `|qty| × price × multiplier` equals the cash
  asked for in every asset class; `FusionPlanner`/`FusionLifecycle` take a `multiplierFor` wired to
  `InstrumentRefSource` (the same source risk-pnl values the position with, so sizer and risk engine
  cannot disagree); an unknown spec plans FLAT rather than sizing as if it were a share; and
  `tradableQuantity` rounds toward zero in contract terms — whole units at multiplier 1 (byte-identical
  for every equity/FX name), order-scale for a contract.
- Lesson / rule: **when two code paths trade the same instrument, diff their sizing arithmetic — the one
  that has been running longest is usually right.** The hedge advisor and the fusion planner have both
  been sizing ES for weeks, one dividing by `price × multiplier` and one by `price`, and nothing flagged
  the disagreement because each was internally consistent and equities (multiplier 1) made them look
  identical on 33 of 35 names. Before trusting any sizer, evaluate it on the instrument where the units
  differ most, not on the typical name.
- Rule 2: **a cost-based gate is also an instrument SELECTOR, and it selects adversarially.** ADR-0075
  admits the name whose round trip is cheapest; the cheapest names on this desk are index and rates
  futures; those are exactly the names with the largest multipliers. So the gate reliably steered the
  desk into the single instrument where the sizing bug was worst. Whenever a filter ranks names by a
  property, ask what *else* correlates with that property — cheap-to-trade and big-contract are the same
  fact about a future.
- Rule 3: **a "situation flag" measured between two runs is not an attribution.** BLEEDING fired because
  PnL fell run-over-run, but the earlier reading included open MTM. Flat-to-flat is the only comparison
  that has no market component in it — when both endpoints are flat, attribution is exact and free. Look
  for those endpoints before believing a delta means what the flag says it means.
- Next lever (deliberately NOT this change): evidence still accrues at ONE independent cohort per source
  per hour, because `SignalTelemetry.record` refuses a new call while one is open and the horizon is
  3600s. That is the real reason the desk sits flat. The honest fix is NOT faster sampling of the same
  horizon (overlapping draws add almost no independent information, and Newey–West would correctly
  refuse to credit them) — it is a **measurement horizon matched to the actual holding period**, which
  the MSFT round trip says is ~13 minutes, not an hour. Grading sources on a horizon the desk never holds
  is both statistically slow and economically the wrong question.

### 2026-07-26T21:05Z — ADR-0079 (portfolio diversification multiplier on the target book)
- Situation: nothing traded this window — flat at both endpoints, zero orders, PnL and exposure both
  unchanged. Attribution is therefore exact and empty: 0% market, 0% change. ADR-0078's ⚠️ MIXED score
  is **unmeasured, not refuted**; a correctness fix on an untraded book can only score zero. Nothing to
  revert. Growth is still above the 1%-per-3 bar on realized PnL.
- **The danger was in the planned book, not the held one — for the second cycle running.** Every one of
  the 23 fusion targets was SHORT (combined forecasts −13 to −18), because `reversion` was pinned at its
  −20 cap on name after name simultaneously. The telemetry says the same thing in the other units: 46
  resolved observations in **2 cohorts**. That is two draws of the market counted 23 times. Each name
  was still sized at a full per-name budget (~$65k–$89k), so the intended book was ~$1.8M gross and
  essentially the same net, against a firm exposure history that peaks near $415k.
- Change: `PortfolioRiskNormaliser` scales the whole covered book by `PDM = min(1, σ_indep/σ_actual)`
  off the EWMA(λ=0.94) daily-return covariance that already prices parametric VaR and the ADR-0038 hedge
  advisor. `σ_indep` is not a new budget — sizing each name alone IS the independence assumption, so it
  is the risk the per-name dial already claimed. Uncorrelated ⇒ PDM 1 (byte-identical book); N perfectly
  correlated ⇒ 1/√N; capped at 1 so an internally-hedged book is never levered up; uniform and positive
  so no name flips side. Uncovered names are neither summed nor scaled.
- Lesson / rule: **a control that measures concentration WITHIN a name says nothing about concentration
  ACROSS names, and the two are easy to mistake for each other.** ADR-0076 spent a whole cycle getting
  the source-level diversification multiplier right, and its existence made the book *look* like it had
  correlation handling. It did not: every name was still sized as if it were the only position. Before
  believing a risk control covers an axis, check which index its sum actually runs over.
- Rule 2: **when a source reads its CAP on many names at once, that is a common-mode reading, not
  breadth.** The cross-section's width flatters the book — 23 names looks diversified — while the cohort
  count (2) tells the truth about how many independent draws are in it. Whenever a forecast source pins
  at its bound across the universe, treat the book as ONE position until proven otherwise, and go read
  the cohort count rather than the name count.
- Rule 3: order of operations, again. Last cycle's "next lever" was a faster expectancy measurement
  horizon to open the gate sooner. That had to wait: opening the gate faster on a book with no
  cross-name correlation control would have *accelerated* the arrival of $1.8M of one-way exposure. Fix
  the sizing of what gets let through before speeding up what lets it through.
- Next lever (deliberately NOT this change): the measurement horizon, now unblocked. Evidence still
  accrues at ONE cohort per source per hour because `SignalTelemetry.record` refuses a new call while one
  is open and the horizon is 3600s, while the desk's only observed round trip held ~13 minutes. Grading
  sources on a horizon the desk never holds is both statistically slow and economically the wrong
  question. Also watch `portfolioRiskMultiplier` / `covarianceCoveredNames` on the target book: coverage
  is 13 of 35 names, so this control currently bites unevenly and reshapes the cross-section.

### 2026-07-26T21:20Z — ADR-0080 (holding period derived from the evidence horizon)
- Situation: nothing traded this window — flat at both endpoints, zero orders, PnL and exposure both
  unchanged. Attribution is exact and empty: 0% market, 0% change. ADR-0079's ⚠️ MIXED is **unmeasured,
  not refuted**; nothing to revert. The desk is not bleeding — it is **frozen**: the edge gate is
  reduce-only and the book is flat, so the reduce-only projection returns zero on every one of the 23
  targets, and that state sustains itself until the gate opens on its own evidence.
- **The trigger behind the last real orders was a units mismatch, not a bad view.** The 20:08–20:21
  window put eleven MSFT legs through in thirteen minutes — buys and sells netting to *exactly* zero —
  each paired with an ES hedge clip. Nothing was gained or lost on the position; the whole move was
  spread and fees. That cadence is not a mystery: `adjustment-rate=0.5` at a 30s cycle gives an exposure
  time constant of `-30/ln0.5 = 43s`, so a round trip every ~70s is what the policy *asks for*.
- Change: the rate is no longer a dial. `TargetPlanner.adjustmentRateFor` returns
  `a = 1 - exp(-cycle/horizon)`, the unique fraction whose time constant `τ = -cycle/ln(1-a)` equals
  `jethro.signals.horizon-seconds` exactly, read from the same property the telemetry is configured by.
  And only the **risk-increasing** part of a delta is rated: the walk to flat trades in full in one
  cycle, where it was previously an asymptotic ~7-cycle grind paying a round trip per step.
- Lesson / rule: **a gate that compares a return to a cost is only sound if the desk holds for as long
  as the return was measured over.** The edge gate credits one horizon of expectancy and charges ONE
  round trip; at a 43-second time constant the desk was paying ~83 of them against that single credit,
  so a source clearing the hurdle by 10× was still losing an order of magnitude. Whenever a rule
  compares a *per-trade* cost against a *per-period* return, go find the period the desk actually
  holds — the mismatch is invisible in both components and lives only in their ratio.
- Rule 2: **when two config values describe the same physical quantity, derive one from the other
  instead of dialling both.** `horizon-seconds` and `adjustment-rate` were both "how long is a view
  good for", set years apart by different reasoning, and neither was wrong on its own terms. A
  derivation makes the disagreement impossible to reintroduce; two dials guarantee it comes back.
- Rule 3: **smoothing that is symmetric is a risk control pointed the wrong way.** The partial-adjustment
  rate exists to stop the desk paying spread to chase noise INTO risk. Applied to exits it only makes
  the desk slower to cut — the opposite of the thesis. Check every rate/band/floor for which direction
  it should bite; the ADR-0065 gates already had this asymmetry, the sizer did not.
- Next lever (deliberately NOT this change): the measurement horizon itself. Evidence still accrues at
  ONE cohort per source per hour (`SignalTelemetry.record` holds one open call per source+instrument at
  a 3600s horizon), so `reversion` has 69 resolved observations in 3 cohorts and its standard error
  cannot shrink at any useful rate — the gate is structurally slow to open even on a real edge. That is
  now a pure measurement question, unentangled from cost: shortening the horizon shrinks per-observation
  return against a fixed round trip, so it must be justified by evidence that the edge is fast, not by
  a wish for more samples. Note the trading rate now FOLLOWS that property automatically.

### 2026-07-26T21:50Z — ADR-0081 (the hurdle read against the distribution the statistic follows)
- Situation: nothing traded this window — flat at both endpoints, zero orders, PnL and exposure both
  unchanged. Attribution is exact and empty: 0% market, 0% change. ADR-0080's ⚠️ MIXED is **unmeasured,
  not refuted**; nothing to revert. Tenth consecutive cycle scored on a zero move.
- **The danger was a permission, not a position.** The held book is flat and safe; the *planned* book is
  23 sized targets held at `deltaQty = 0` solely by the reduce-only gate. The one thing holding it back
  was `reversion` sitting a hair under the fixed 2.0 t-hurdle — **on three resolved cohorts**. One
  favourable burst flips a binary switch and the whole planned book arrives at once. Read the gate's
  margin, not just the book's exposure: a control about to release is a live risk state.
- Change: `EdgeGate` converts `t-hurdle` once into the confidence it always claimed
  (`α = 1 − Φ(t-hurdle)`, 2.0 ⇒ 0.02275, unchanged) and tests the surplus against α under Student's t on
  `cohorts − 1` df, via a new pure `Significance` class. Exactly a no-op as df → ∞; strictly conservative
  below it. Evidence ordered by p-value, the only statistic comparable across differing df.
- Lesson / rule: **when you change what a standard error is estimated FROM, you have changed which
  distribution the ratio follows — go re-check the critical value in the same breath.** ADR-0077 correctly
  moved the denominator to a Fama–MacBeth estimate over a handful of cohorts and left the numerator's
  hurdle at a normal quantile. Both halves were individually defensible; the mismatch lived only in their
  comparison, and it ran one way — at 3 cohorts a "97.7%" gate was operating at roughly 91%.
- Rule 2: **a minimum-sample floor must count the index the statistic's precision actually depends on.**
  `min-sample = 30` counts *observations*; 69 observations in 3 cohorts passes it comfortably while
  supporting almost no precision. Whenever a threshold guards a statistic, check that it is denominated
  in the same units as that statistic's degrees of freedom — ADR-0079 recorded the identical mistake one
  level up (per-name vs across-name), and this is its within/across-time twin.
- Rule 3: **prefer a continuous correction to a second discontinuous floor.** The alternative here was a
  hard minimum cohort count; the t-distribution already penalises thin samples continuously and correctly,
  and adding a floor beside it only creates two mechanisms that can disagree (the ADR-0074 lesson).
- Next lever (deliberately NOT this change, for the fourth cycle): the measurement horizon. It is now
  unambiguously the binding constraint — permission accrues at `1 / horizon` cohorts per hour, and no
  statistical fix can speed that up. But its own stated condition still holds: shortening the horizon
  shrinks per-observation expectancy against a fixed round-trip cost, so it must be justified by evidence
  that the edge is FAST, not by a wish for more samples. The honest way to get that evidence is to measure
  expectancy at a ladder of horizons and let the data choose — with a multiple-testing haircut for the
  number of horizons tried. That, not a horizon guess, is the next change to build.

### 2026-07-26T22:20Z — ADR-0082 (the evidence horizon is measured, not dialled)
- Situation: eleventh consecutive cycle on a zero move — flat at both endpoints, no orders, PnL and
  exposure unchanged. Attribution is exact and empty: 0% market, 0% change. ADR-0081's ⚠️ MIXED is
  **unmeasured, not refuted**. Not a danger state (nothing is held), but a hard failure against the
  growth target, and the loop has now spent five cycles refining a gate that never gets to speak.
- **The gate is starved, not wrong.** Three of five sources are measurably negative and correctly
  refused forever; the one positive source is blocked purely by statistical POWER. A cross-sectional
  source emits its whole book in one burst, so it produces exactly **one independent cohort per
  measurement horizon** — at 3600s the gate accrues one degree of freedom per hour, and `reversion`
  has three. ADR-0077/0079/0080/0081 were each individually right and each landed on zero, because
  every one of them refined the *test* while the *sample rate* was the binding constraint.
- Change: `HorizonLadder` grades every call over `base, base/4, base/16` (3600/900/225s), selects the
  rung with the smallest best p-value, and `EdgeGate.Params.alpha()` divides by the rung count
  (Bonferroni). The selected rung drives the gate verdict, the source weights AND the holding period
  (ADR-0080's identity, now evaluated per cycle).
- Lesson / rule: **when a control never fires, ask what rate its EVIDENCE arrives at before refining
  the test it applies.** Five cycles were spent sharpening a hurdle whose input accrued one degree of
  freedom per hour. A test and its sample rate are different objects; a p-value that cannot fall is a
  measurement problem wearing a statistics problem's clothes. Check the denominator's *arrival rate*,
  not just its formula.
- Rule 2: **a search over m variants is m tests — pay for it in the same change that introduces the
  search.** The ladder is exactly the procedure the backtest-overfitting literature warns about, and
  the haircut must be shipped WITH it, not "added later once it works". Note the direction: this makes
  the base rung strictly harder too, which is the property that makes the change safe to ship blind.
- Rule 3: **shortening a measurement window is not a way to buy significance, and the thing that keeps
  it honest is the cost term, not the p-value.** Expectancy scales with the period; the round trip
  charged against it does not. Any future change that increases sample count by shrinking a horizon
  must be checked against a FIXED per-trade cost before it is believed.
- Observed but NOT this change (next lever): **forecast saturation.** `fusion_targets` shows `trend`
  pinned at +20.0 and `reversion` at −20.0 on nearly every name, so GBPUSD and MSFT carry the
  bit-identical combined forecast −16.9296. Two consequences: the cross-section carries no selection
  information at all (23 names, one bet), and the combined forecast is the small difference of two
  constants, so a hair of movement flips the sign of the whole book — that is the mechanism behind the
  20:08–20:21 MSFT churn. Worth attacking once the gate can actually trade; sizing quality is
  unmeasurable while the book is flat.

### 2026-07-26T22:45Z — ADR-0083 (the per-name budget is split by measured volatility)
- Situation: twelfth consecutive cycle on a zero move — PnL and exposure both unchanged, no strategy
  orders at all, every fusion target `deltaQty: 0` against `currentQty: 0`. Attribution is exact and
  empty: **0% market, 0% change**. ADR-0082's ⚠️ MIXED is **unmeasured, not refuted** — it shipped ~11
  minutes before the report. Not a danger state (nothing held, nothing to cut), but a hard miss on the
  growth target.
- **ADR-0082 is visibly working and was deliberately left alone.** Its new 225s rung already carries 46
  resolved `reversion` observations against the 3600s rung's 92 accumulated over many hours — roughly
  16× the cohort arrival rate, which is exactly the quantity that was blocking the gate. Stacking a
  sixth consecutive gate change on top would have made both unattributable and risked breaking a fix
  mid-flight.
- Change: new pure `VolatilityBudget` splits the per-name cash budget by each name's own MEASURED daily
  σ — `kᵢ = σ_ref/σ̃ᵢ`, `σ_ref` = harmonic mean of the winsorised σ over the covered names — from the
  same EWMA covariance that already prices parametric VaR, the ADR-0038 hedge advisor and the ADR-0079
  multiplier. Applied before the correlation control. Measured dispersion on the live universe: EURUSD
  0.435%/day to NVDA 3.236%/day, **7.4×**.
- Lesson / rule: **a flat per-name cash budget is a volatility bet nobody placed.** Equal dollars means
  risk contribution proportional to σ, so a 23-name "cross-section" at 7.4× σ dispersion is a handful of
  names plus rounding — and it is invisible in gross notional, which is the number the operator watches.
  Whenever a budget is stated in CASH, check what it implies in RISK before believing the book is
  diversified.
- Rule 2: **when a control corrects one term of a product, check whether the other term is also
  unequal.** ADR-0079's own derivation contained the answer in plain sight — its independence benchmark
  `σ_indep = √(Σ eᵢ² Σᵢᵢ)` is unequal across names *precisely because* `Σᵢᵢ` is. The correlation fix and
  the volatility fix are orthogonal halves of one concentration problem, and shipping only the first
  leaves the second looking handled. Read a control's own algebra for the terms it does NOT touch.
- Rule 3: **the reference point is where a redistribution smuggles in a money number — pick the one that
  makes it provably neutral.** Any `σ_ref` produces `1/σ` weights; only the HARMONIC mean makes
  `Σkᵢ = |C|`, leaving the owner-set `unit-notional-usd` untouched and its meaning intact ("cash for a
  name of typical volatility"). Plus a one-way gross cap, because budget neutrality is neutrality of the
  BUDGETS and realised gross also carries each name's forecast. An estimated σ must never be the reason
  the desk carries more exposure.
- Rule 4: **document the range where a robustness device does nothing, rather than tuning until the test
  passes.** Nearest-rank winsorisation only clips the lowest name above `100/p` covered names; below
  that the honest protections are the structural `Σkᵢ = |C|` bound and the gross cap. Stated at the call
  site and in the ADR's negatives — a silent cap reads as "covered" when it isn't.
- Observed but NOT this change (next lever, still): **forecast saturation.** `trend` sits at the ±20 cap
  on ~26% of names and `reversion` on ~22%, with `trend` skewed almost entirely positive and `reversion`
  almost entirely negative — so 20 of 23 combined forecasts point the same way. That is one macro bet
  wearing a cross-section's clothes, and it is a *selection* defect this change does not touch (this one
  equalises how much risk each name brings, not which names are chosen). Worth attacking once the gate
  actually trades and sizing quality becomes measurable.

### 2026-07-26T23:45Z — ADR-0084 (the desk POSTS to enter and CROSSES to exit)
- Situation: thirteenth consecutive cycle on a zero move — PnL frozen, gross and net exposure exactly
  zero, no orders since 20:21, every fusion target `deltaQty: 0` against `currentQty: 0`. Attribution
  is exact and empty: **0% market, 0% change**. Not a danger state (nothing held to cut), but a hard
  miss on the growth target and the fifth consecutive change left unmeasurable by a book that will
  not trade.
- **The gate was NOT the blocker this time.** `reversion` clears at the ADR-0082-selected 225s rung on
  391 observations / 17 cohorts, p = 0.0006 — ADR-0082 is working. The block is one layer down, in the
  ADR-0075 per-name cost test: at reversion's measured standard error the largest survivable round trip
  is ~1.95 bps, and every name's measured round trip is its full spread because the desk crosses on
  both legs (MSFT 2.87 → GOOGL 20.11, unfilled names 6.34). The ONLY name that clears is ES at 0.35 bps
  — and ES is the ONLY name the ADR-0049 OOS selector never evaluates, so it is vetoed for having no
  verdict. A closed loop with exactly one exit, and that exit sealed by an unrelated gate.
- Change: risk-INCREASING fusion deltas are posted as DAY LIMITs at the instrument's own arrival mark
  (measured implementation shortfall zero by construction); risk-REDUCING deltas still cross as MARKET.
  Working fusion orders — scoped by the `fusion:` key prefix — are retired at the top of each planning
  tick. No hurdle, α, haircut or df was touched.
- Lesson / rule: **when a gate is a comparison, check BOTH sides before changing either.** Five
  consecutive cycles re-specified the statistics of `edge vs cost` — cohorts, degrees of freedom,
  multiplicity, shrinkage — and every one of them was working on the term that was not binding. The
  cost side had never been touched at all. Before tuning a test, compute what the test would need on
  *each* input to flip, and attack the one with the most headroom.
- Rule 2: **a desk's EXECUTION STYLE is a strategy parameter, not plumbing.** "Submit as MARKET" set
  the hurdle every future signal must clear, and it did so invisibly — it appears nowhere in any dial,
  ADR or config, only in a `OrderType.MARKET` literal. Any measured cost that is suspiciously equal to
  the configured spread is telling you the desk chose to pay it.
- Rule 3: **crossing to enter a mean-reversion trade pays away the exact premium the signal earns.**
  Reversion says the price over-extended and will come back — which is a statement that the desk should
  SUPPLY liquidity to the flow pushing it away. The correct execution style is implied by the signal,
  and getting it wrong is not a small tax: on this book it was the entire edge.
- Honest limitation, not hidden: a posted entry may not fill (opportunity cost, measured nowhere), and
  a passive fill's cost migrates from the price into adverse selection (visible only in realised PnL).
  Both are deferred-register rows. If the next verdict is ❌ BAD with fills happening, adverse selection
  is the first suspect and the near-touch/mid choice is the first dial to revisit — NOT the gate.
- Observed but NOT this change (next lever, still, third cycle running): **forecast saturation** —
  `trend` and `reversion` sit at the ±20 cap on ~a quarter of names each and with opposite signs, so
  20 of 23 combined forecasts point the same way and the cross-section carries almost no selection
  information. Still unmeasurable until the desk actually trades.

### 2026-07-27T00:45Z — ADR-0086 (the fusion desk cuts on its own volatility)
- Situation: the book **traded and made money** for the first time in fifteen cycles — three MACRO/ES
  fusion fills, `+$2.32` on `$0.136` of fees, gross `$0 → $956.98`, firm PnL `-826.06 → -823.80`. Every
  equity line flat with zero unrealised, so attribution is unusually clean: **~100% change, ~0% market**.
  Not a danger state (one small short, VaR95 `$11.04`, breaker far away).
- **The scoreboard has a structural trap, and it fired.** ADR-0085 — the change that produced those
  fills — was scored ❌ BAD and auto-reverted. Not for losing money: off a flat book the exposure
  deadband is 1% of **zero**, so any position reads "exposure up", and `+$2.45` sits inside the `$50`
  PnL deadband, so it reads "PnL not up". **Any** change that ends a flat book is mechanically BAD
  unless it earns >$50 in one 30-minute window. Logged for Oleg; the scorer and ledger are off-limits
  to me and I did not touch them. Rule: when a verdict disagrees with the fills, read the fills — but
  fix the trade, never the scoreboard.
- Change: the fusion path had **no per-name risk control at all**. Every control on it asks whether risk
  may be put ON (edge gate, conviction floor, budget split, adjustment rate); nothing asks whether a
  held position has gone wrong. The legacy path has had a stop since ADR-0019; the firm breaker is a
  whole-book halt, not an exit. Added a chandelier exit: cut to flat when the mark retraces from the
  position's peak by more than `3σ` over the desk's **derived** holding horizon, then stand aside one
  horizon. It can only ever set a target flat — it cannot lever the book up.
- Lesson / rule: **σ has to cover the names you actually hold.** Every risk number the desk prices with
  comes from `daily_close`, which covers 3 of 23 planned names and **none** of the held ones — the
  parametric VaR reports its whole covered exposure as *skipped*. A risk sensor silent exactly where
  risk sits is not a risk sensor. Before keying a control on a measurement, check its **coverage on the
  live book**, not its correctness.
- Rule 2: **a mean-reversion book without a stop is an unhedged short option.** Reversion is the only
  source clearing the gate, and its payoff shape is many small wins ended by one large loss. Fifteen
  cycles of work went into deciding *whether to enter*; none into *when to leave*. When a desk's
  measured edge changes character, re-ask which controls that character needs.
- Honest limitation: at `3σ` over a 900s horizon the trigger is wide, so it may not fire inside a
  measurement window — an unmeasured cycle is possible and is not evidence against it. If cuts do fire
  and PnL worsens, the first suspect is cutting winners (raise `sigma-multiple`), not the sensor.
- Still open (fourth cycle running, still unmeasurable): **forecast saturation** — `trend` and
  `reversion` sit at the ±20 cap with opposite signs on many names, and `trend` is measured
  significantly NEGATIVE at every rung (t ≈ −2.2 to −3.6 on 184–500 obs) yet is pinned at the `0.25`
  weight FLOOR, above its own shrunk value of ~0.13. The floor's stated justification — preserving the
  source count for the diversification multiplier — was invalidated by ADR-0076. That is the next lever;
  it was not taken this cycle because removing the floor **raises** forecast magnitude and so exposure,
  which is the wrong trade to make in the same window as a new risk control.

### 2026-07-27T02:15Z — ADR-0089 (the desk measures correlation on the stream it trades)
- Situation: first genuine **DANGER** state in many cycles — bleeding AND adding exposure. Gross went
  from roughly ten thousand to `184,719.88` in one window (net `-75,988.06`, a large one-sided short)
  while PnL fell `-45.30`. No configured limit is close (VaR95 `1,157.61`, firm gross limit `1,500,000`,
  breaker untripped) — the damage is entirely to PnL *per unit of exposure*, which is the objective.
- Order post-mortem: pure thrash. MSFT +49 → −27/−16/−12 → +34/+16/+11/+9; AAPL +5/+9/+8 then
  −56/−36/−27/−23/−19 four minutes later. `reversion` is pinned at its ±20 cap on most names and flips
  sign inside minutes; every flip pays a round trip, and fees are a large share of the realised loss.
- **The mechanism was not the last change.** ADR-0088 scored ❌ BAD and was reverted, but exposure was
  ramping ~×9 per window *before* it and kept ramping after. The ramp is ADR-0080 walking the desk
  toward a planned book far bigger than anything the risk controls priced. Blaming the scored change
  would have been the wrong post-mortem; the ledger verdict answers "did this help", not "what is the
  mechanism".
- **The finding.** ADR-0079's concentration multiplier and ADR-0083's volatility budget — the only two
  controls that look at the BOOK rather than at a name — both read a `daily_close` covariance. Under
  ADR-0073 that series admits an instrument only after several consecutive sessions **in the running
  feed mode**, and this stream has two. So the estimate covers nothing, both controls hit their
  "no measurement, no claim" branch and silently no-op, and a book short nearly every name at once
  carries N independent per-name budgets of what is arithmetically ONE position. Change: measure it on
  the mark stream (synchronised per-cycle snapshots, seeded on one bucket grid of the feed's clock),
  choosing per cycle whichever estimator covers more of the planned book.
- **Rule: a control that falls back to "make no claim" fails SILENTLY and looks safe.** Every such
  branch is a place the desk can be running with the control absent while the code reads as if it were
  present. When diagnosing a risk that a control should have caught, check its COVERAGE on the live
  book first — before questioning its logic. This is the second time the same root cause has cost a
  cycle (ADR-0086 found it for σ; this is the same gap on the pairs). Next time: grep every
  `Optional.empty()` fallback in the sizing path and ask what fraction of the live book it is returning.
- **Rule 2: measure the ramp, not just the level.** Exposure rising ~×9 per window for three consecutive
  cycles was visible in the run-status history the whole time and none of those cycles named it, because
  each window's absolute level was still under every configured limit. A limit is a backstop; the
  trajectory toward it is the signal.
- Honest attribution: the window's PnL fall is mostly *market* on positions the ramp had already opened;
  the exposure rise is *mechanism*, not market. This change gets credit for none of it — it went in
  after the measurement. Expected next: gross falls on an unchanged view. If gross does not move, the
  estimator is not covering the book and the SEED is the first suspect, not the control.
- Still open (fifth cycle): **forecast saturation** — `reversion` pinned at ±20 with `trend` measured
  negative at the weight floor means the cross-section carries almost no selection information, which is
  *why* every name points the same way. The concentration haircut treats the symptom honestly; the
  saturation is the cause and is the next lever. Note that removing the weight floor (ADR-0087) was
  already tried and reverted — the lever to try is re-scaling the reversion forecast so the cap stops
  binding, not re-weighting the sources.

## 2026-07-27 — a change of view was being executed as a danger cut (ADR-0090)

- Situation: the opposite of last cycle. PnL up on the window and up over three runs, gross collapsed,
  net near flat, VaR and the breaker nowhere near binding. No danger state — so the cycle went at the
  structural cost problem instead of de-risking.
- **Honest attribution on last cycle's ✅ GOOD.** The exposure collapse was mechanism, but not the
  mechanism ADR-0089 claimed: the book was unwound at the process restart, when every sensor was cold
  and the held names had no view, down the ADR-0065 orphan path. The PnL rise was mostly market on
  positions the loop never touched. The ledger verdict answers "did the vector improve", not "did this
  code cause it" — and this is the second consecutive cycle where those two answers differ. **Rule:
  when a change lands together with a restart, assume the restart until the orders say otherwise.**
- Order post-mortem, and it is one shape on every name: AAPL bought in 3-share steps every 30s for
  seven minutes, then sold 101 shares in ONE order, then immediately rebuilt the other way. JPM and JNJ
  identical. Against the attribution, fees are the large majority of the firm's total loss and the desk
  is close to flat gross-of-fees. **The desk was not losing on its views; it was paying them away.**
- **The finding.** ADR-0080 set the entry rate so exposure e-folds toward target in one measurement
  horizon, but rated only the risk-INCREASING half of the gap and traded every reduction in full.
  `orderDelta` cannot see WHY the target moved, so a mere change of view was executed as a danger cut —
  and the dominant source is mean-reverting, so it crosses the held position many times inside one
  horizon. τ_in = h, τ_out = 0: the desk paid the full cost of a round trip while never reaching the
  size at which a single-digit-bps expectancy could pay for it. Change: only a FLAT target exits at
  full speed; everything else is worked at the same rate in both directions.
- **Rule: an asymmetry justified by risk must be conditioned on a risk SIGNAL, not inferred from the
  arithmetic.** "Reducing" is a property of the delta's sign, not evidence that anything is wrong. Every
  control here that genuinely means "get out" sets the target FLAT — so flatness, not direction, is the
  test. When you next find a control that behaves differently for cuts, check what it is actually
  reading to decide something is a cut.
- **Rule 2: cost and edge do not scale together.** Turnover cost scales with notional traded; edge
  scales with position SIZE. Any policy that trades fast but sizes slowly loses by construction,
  whatever the signal is worth. Compare the fee bill to gross exposure — 100× the book in a window is
  the tell, and it was visible for several cycles before this one named it.
- Note for next cycle: this is the first change in a while that could plausibly RAISE gross (positions
  now persist instead of being zeroed each flip). Expected effect is the opposite — an oscillating aim
  smooths to a small position — but if gross rises with PnL flat, the scorer will call it ❌ BAD and the
  right follow-up is the no-trade band (`buffer-fraction = 0.5` is measured against the GAP, so it
  never binds while the desk is far from target), not a re-attempt of this lever.

## 2026-07-27 — the desk was netting against its own hedge (ADR-0091)

- Situation: PnL up again on the window and up over three runs; gross roughly DOUBLED (`64,650.20`,
  `+33,984.70`). VaR95 `516.76`, breaker untripped, nothing near a limit — so no danger state, and the
  cycle went at the exposure mechanism rather than de-risking.
- **Honest attribution on last cycle's ⚠️ MIXED (ADR-0090).** It did what it was built to do: the
  equity trace is visibly calmer, single-digit steps and no more 100-share single-order reversals.
  Positions now persist instead of being zeroed on every crossing, and persistence is *part* of why
  gross grew — but it is not why gross grew this much. The dominant term predates it and is structural.
  Credit for the calmer equity trace; no blame for the gross.
- **The finding, and it is the biggest single item on the balance sheet.** `FusionConfig` handed the
  fusion lifecycle two suppliers that disagreed about who owns the hedge book: `heldInRoutedBooks`
  EXCLUDED it (its javadoc even says why — "two legs where there was one, gross exposure up, and the two
  loops fighting each other every cycle") while `firmPositions`, three lines below, SUMMED every book.
  So the planner's `current` included the hedger's leg. Closed positive feedback: the hedger buys `h` to
  offset the strategy books, the planner reads its gap as `target − (s + h)` and opens `−h` in a STRATEGY
  book, and since that sale does not move the cash-equity exposure the hedger measures, its target is
  unchanged and its leg stays on. Fixed point `s = target − h`: the firm holds `|target − h| + |h|` where
  the economics call for `|target|`, the hedge is EXACTLY cancelled, and the offsetting leg grows with
  `h` — **no fixed point in gross at all.**
- Order post-mortem that exposed it: `MACRO` selling ES every cycle while `HEDGE` bought ES four seconds
  later, all window. Resulting book `MACRO −0.060884` / `HEDGE +0.050697` = `$30,408` gross (47% of the
  firm) for `−$2,776` net (9%). The hedge book was the firm's single worst position — its loss larger
  than the whole firm's — and almost all of it REALISED, i.e. paid on round trips, not lost on a view.
  Change: net against the books the layer routes into; skip the hedge book.
- **Rule: when two code paths answer "is this position ours?", they must be the same code path.** The
  bug was not that either supplier was wrong on its own — it was that they disagreed, three lines apart,
  in the same method call. Next time a control has a scope (which books, which names, which modes), grep
  for every other place that scope is decided and check they agree.
- **Rule 2: read the planner's own `currentQty` against the book, not just its targets.** The tell was
  sitting in `fusion_targets` for cycles: `ES: current = +0.011200` — a POSITIVE current on a contract
  the strategy book was short by five times that. A `current` that does not match any book you can trade
  is a scope bug, and it is visible without any new instrumentation.
- **Rule 3: a hedge that costs money and moves no net is not a hedge.** Compare the hedge book's PnL
  against the firm's, and its REALISED against its unrealised. Realised-dominant on an overlay means it
  is round-tripping, and round-tripping against your own strategy book is the first thing to check.
- Expected next: gross falls by the hedger's notional on the proxy and keeps falling as the ratchet
  unwinds; firm net moves TOWARD flat because the hedge finally offsets. If gross does not fall, the
  next suspect is not this change but the planned book itself — the target book is currently sized at
  roughly an order of magnitude above the held book and is ramping into it, which is the standing open
  item once this loop is closed.
- Still open (sixth cycle): **forecast saturation** — `reversion` dominant at weight 2.87 and running
  near its cap on most names while `trend` is measured significantly NEGATIVE at the weight floor, so
  the cross-section carries little selection information and every name points the same way. Removing
  the weight floor (ADR-0087) was tried and reverted; the untried lever is re-scaling the reversion
  forecast so the cap stops binding.

## 2026-07-27 — the forecast cap had become the forecast (ADR-0092)

- Situation: third consecutive up run — PnL `-91.34` (`+41.42` window, `+294.75` over three), gross
  `18,920.07` (`-36,084.08` window), net `2,354.41`, VaR95 `153.18`, breaker far. No danger state, so
  the cycle went at a mechanism.
- **Honest attribution on ADR-0091's ⚠️ MIXED.** The verdict is a deadband artefact, not a judgement:
  gross fell by two-thirds and net by 95%, and PnL rose `+46.87` — three dollars under the `$50` band
  that would have made it ✅ GOOD. What unwound is exactly the double leg the change stopped creating,
  so the exposure half is the change's own doing and not the market. The PnL half is inside the noise
  band and I claimed no credit for it.
- **The finding.** All four phase-2 scaling constants are CLAIMS about how big a source's readings are,
  and nothing had ever checked one. Measured on the live target book: trend `E|f| = 15.17` against a
  promised 10, **median 18.86, 11 of 23 names pinned exactly at the ±20 cap**; reversion `14.68` with 6
  of 23 pinned. Over a third of the cross-section clipped. Fixed by measuring each source's own scale on
  the stream (expanding mean of |claim|, Carver's forecast scalar) and rescaling back to TARGET_ABS —
  one-way, so it can only ever shrink the book.
- **Rule: a cap that binds often is not a cap, it is the forecast.** Carver's ±20 is a defence against
  rare extremes. At a 37% clip rate the source has degenerated into a sign function: every clipped name
  reads the identical number, so the planner cannot tell a 1.8-sigma name from a 2.4-sigma one and the
  cross-sectional selection that justifies 23 names instead of one is gone. **Check the clip RATE of
  every bounded quantity in the system, not just its value** — the tell was sitting in `fusion_targets`
  for six cycles as "every name points the same way", and one line of arithmetic over the payload
  (`E|f|` and the at-cap count per source) named it.
- **Rule 2: a number a component "promises" about itself is an assumption until something measures it.**
  The sensors claim a self-normalised score with E|score| ≈ 1; the mapper multiplied by 10 on that
  promise for six cycles. When a comment says a constant SHOULD be estimated from telemetry, that is a
  live bug report, not a nice-to-have.
- **Rule 3: read the cumulative fee number before blaming turnover.** `/api/attribution` `totalFees`
  spans the whole feed-mode history — `297.01` against a `-91.34` PnL reads as "costs are 3× the loss"
  and is nearly all yesterday's much larger book. The fills table for the live 25 minutes says `$10.40`
  on `$93k` traded. Always re-derive the cost per window from `fills` before targeting cost.
- Order post-mortem, worth keeping: within ALPHA the winners (`AAPL`, `MSFT`, `JPM`, `JNJ`) are exactly
  the names with `0.59–0.72 bps` measured slippage and the losers (`GOOGL`, `GOOG`, `SAP`) the three
  worst round trips (`10.05`, `2.07`, `4.08 bps`). **Cost, not view, sorted that book.** The ADR-0072/0075
  per-name cost gate has since flattened GOOGL and SAP — that trigger is already fixed.
- Expected next: gross falls as the planned book shrinks toward the size `unit-notional-usd` was set for,
  and turnover falls with it. If gross does NOT fall, the suspect is not this change but the ADR-0089
  stream covariance — the boot log still shows it cold, so the concentration multiplier and the
  volatility budget are both silently no-op, and that is the next lever rather than a re-attempt here.
- Still open: `MACRO -0.002530 ES` against `HEDGE +0.002526 ES` — two legs in one instrument cancelling
  at the firm level for `$1,378` of gross and ~0 net. It nets `+92.22` and costs about a dollar a cycle,
  so it was logged rather than chased; the fix when it is worth doing is netting the hedge target
  against the proxy the firm holds ANYWHERE, not just in the hedge book.

## 2026-07-27 — the no-trade band could never bind (ADR-0094)

- Situation: fourth consecutive up run — PnL `67.76` (`+26.67` window, `+200.52` over three), gross
  `26,197.73` (`+3,162.22` window, `-28,806.42` over three), net `3,527.47`, VaR95 `216.31`, breaker far.
  The single EXPOSURE RISING flag is ADR-0093's, which scored ❌ BAD and was auto-reverted — so the
  culprit was already gone and no de-risk override applied. **Attribution: I claimed credit for none of
  the window's PnL.** ADR-0093 re-weighted every source, so every name was touched and there is no
  untouched control group to read the market off; the `+26.67` cannot be split from the numbers alone and
  I said so rather than guess. Only the exposure half is separable, and it is the change's.
- **The finding.** The ADR-0055 no-trade band is `|target| × bufferFraction` compared against the gap TO
  the target — and ADR-0080 partial adjustment deliberately never takes the desk to its target, so the
  gap is ~0.9 of the target every cycle and the comparison has exactly one answer. **It has never
  suppressed one order.** The tell was one line of arithmetic over `fusion_targets`: `deltaQty / (target −
  current) = 0.032784` on ALL thirteen planned names — the derived rate to seven digits — while the desk
  held 5–30% of its own target (AAPL −7 against −142, JNJ 47 against 260, GOOG 38 against 131).
- **Rule: a control is dead until you have seen it FIRE.** Check the bind RATE of every gate, band and
  veto, not just its configured value — the sibling of ADR-0092's "check the clip rate of every bounded
  quantity". `buffer-fraction=0.5` reads like a deliberately wide low-churn band and was cited as the
  reason the policy is cost-aware; it was inert for every cycle this desk has ever run.
- **Rule 2: match the policy to the COST STRUCTURE you actually pay.** The fee here is a fixed fraction of
  notional and slippage is quoted in bps, so total cost is a function of QUANTITY traded, not order count
  — suppressing small orders saves nothing, only suppressing the target's oscillation does. Under
  proportional costs the optimal policy is a no-trade REGION traded at its boundary (Constantinides 1986;
  Davis–Norman 1990); Gârleanu–Pedersen's smooth partial adjustment, which this desk implements, is the
  QUADRATIC-cost solution. The desk had the wrong prescription for its own cost.
- **Rule 3: when a book holds a small lagging fraction of its own target, the cost is 100% and the edge is
  that fraction.** `ALPHA` paid `268.61` of fees against `~283` of gross alpha — 95% of the gross — while
  never reaching the risk it decided to take. Before blaming the signal, check `currentQty/targetQty`.
  `HEDGE -323.33` and `MACRO +376.99` are both realised history and both flat now; only ALPHA trades.
- Expected next: turnover and the ALPHA fee line fall materially with gross roughly unchanged (the aim
  path IS the position the old policy converged to, so exposure is unchanged by construction). If gross
  falls and PnL does not improve, the buffer is too WIDE and the next lever is its width. If turnover does
  NOT fall, the suspect is the target's own oscillation — the reversion sensor's span — not the execution
  policy, and that is the next lever rather than a re-attempt here.
- Do NOT re-attempt: source-weighting (ADR-0087 weight floor, ADR-0093 measured-edge weights) — reverted
  twice now. Trend and momentum still measure significantly NEGATIVE (`-9.69`/`-4.69` bps at 3600s, hit
  rates 0.23/0.31 on n=299/69) at the 0.25 weight floor against reversion's `+12.42` bps at weight 3.0;
  that asymmetry is real but the weight lever is burned, so any future attempt must come at it from a
  different direction (e.g. horizon selection or dropping a source outright, not re-weighting it).

## 2026-07-27 — the gate and the weights disagreed, and the weights won

- **What the window did.** Total PnL `$267.21` (`+34.22` on the window, `+105.87` over three runs, on
  track) while gross exposure went `+36,404.57` to `$50,876.08` — EXPOSURE RISING for the sixth run
  running. `recent_orders` is the same four names traded the same direction every 30 s (`JPM SELL`,
  `AAPL SELL`, `JNJ BUY`) with a `HEDGE ES BUY` chasing behind: a monotone ramp toward a target book an
  order of magnitude above what is held, not a strategy firing. Attribution: `ALPHA +339.59`,
  `MACRO +376.99`, `HEDGE −451.51`, fees `$356.76`.
- **Attribution honesty.** Last cycle's `d82aea4c8` was scored ❌ BAD and reverted at 06:30, and gross is
  still `$50,876` *after* the revert — so the ramp is the desk's own mechanism, not that change. It gets
  credit for none of the PnL move and blame for none of the exposure move. **Rule: when the same flag
  fires on six consecutive commits with different content, stop scoring the commits and find the process.**
- **The trigger.** The desk runs ONE statistic through TWO consumers with two answers. The edge gate
  passed exactly one source (`reversion`, t = 10.65) and failed `social` (t = 1.82, p = 0.039),
  `momentum` (−2.80) and `trend` (−4.96) — yet the combination weights read `reversion 2.106`,
  **`social 1.757`**. On `JPM`, the desk's LARGEST position, social's `−16.00 × 1.757` out-voted
  reversion's `+1.29 × 2.106` ten to one and reversed the sign of the only view with a demonstrated edge.
- **Rule 1: a probability is not an effect size.** `Φ(t)` has essentially all its dynamic range in
  `t ∈ [−2.5, 2.5]`. Any statistic used to RANK sources must stay informative past the hurdle; Φ is flat
  there and reads t = 1.7 and t = 10.6 as near-equals. Where a bounded, never-negative weight is wanted,
  keep Φ but gate ADMISSION separately.
- **Rule 2: the desk must obey its own tests.** If a test is trusted to decide whether risk may be taken,
  it must also decide whose view directs it — otherwise the desk has built rigour and then routed around
  it. Fixed by `EdgeGate.demonstratesEdge` (the same test at zero cost — cost decides *whether* to trade,
  never *whose view counts*, so it is not charged twice) holding non-admitted sources at the MIN weight.
- **On the burned lever.** The previous entry recorded source-weighting as burned (ADR-0087, ADR-0093 both
  reverted) and named "dropping a source outright" as the permitted different direction. This is the safe
  form of exactly that: held at MIN rather than 0, so the active-source count and the ADR-0076 multiplier
  are untouched, and — unlike both reverted attempts — it is **strictly one-way** and can only lower a
  weight. Taken deliberately with that history in view.
- **Expected next.** Planned book and the ramp shrink (`JPM` DM 1.1944 → 1.1299, fused view −7.66 → ≈−1.5,
  under `min-forecast-to-route`). **If this scores BAD the weight lever is definitively closed** — do not
  return to it in any form. The next levers, in order: the HEDGE book (`−451.51` on `$8,306` of gross, the
  firm's single worst position, structurally beta-sized from ASSIGNED betas while the measured ρ² fails
  the effectiveness floor — but note ADR-0095 already tried unwinding it and was reverted, so come at the
  cost/chase side, not the ρ² side), then the reversion sensor's own span.
- **Standing observation, not yet acted on.** `turnover_cost_by_name` in the report has been erroring
  (`| error |`) for several cycles — the loop is blind to per-name cost. Worth a report-only fix on a
  cycle where no trading lever is clearly better.

## 2026-07-27 — the hedge's loss is directional, not execution cost, and the target was noise

- **What the window did.** Total PnL `$327.26` (`+88.17` on the window, `+142.58` over three runs,
  on track) with gross `$37,162.38` — up only `+718.89` against `+36,404.57` the window before, so
  **ADR-0097 stopped the six-run exposure ramp**. `fusion_targets` confirms it: `reversion 2.091`,
  every other source pinned at the `0.25` MIN. Attribution `ALPHA +458.94`, `MACRO +376.99`,
  `HEDGE −509.21`: the strategy books make `+835.94` and the hedge hands back 61% of it.
- **Attribution honesty.** The window's PnL sits on ALPHA positions last cycle's weight change
  directly re-signed, so that is a change effect. The HEDGE line is not: it has gone
  `−323.33 → −451.51 → −509.21` over three cycles regardless of what changed above it. MACRO is
  unchanged to the cent for a third cycle — still frozen, still not trading.
- **Rule 1: separate cost from direction BEFORE choosing a lever.** The findings memory had queued
  "come at the hedge's cost/chase side". The numbers refuse it: HEDGE fees `$40.02` plus 355 ES fills
  at a measured `0.204` bps is under `$50` of the `$509.21` loss. The rest is **directional**. A
  wider no-trade band trades the same wrong position less often and leaves the loss in place. Always
  divide a losing book's PnL into `fees + slippage` vs `the rest` before picking the fix — a
  turnover remedy is only correct when turnover is where the money went.
- **The trigger.** ADR-0039 holds the book target-flat with `equity-rebalance-floor-usd = 0`, so the
  target is `−Σβ·E` at every evaluation however small that is. Live: `Σβ·E = −$195.49` against
  `$37,162` of gross — **0.5%**, i.e. the book is already nearly beta-neutral — while the desk sent
  one ES order per cooldown, alternating BUY/SELL at `$250`–`$3,000`, on a held position of `$312`.
  It was taking a signed proxy bet on a number smaller than that number's own step.
- **Rule 2: a hedge target built from a book's net inherits that book's signal, inverted.** `−Σβ·E`
  on a mean-reversion book is long the proxy after a rally and short after a selloff — a **momentum
  position on ES**, i.e. the desk's worst-measured view (`trend`, `−8.61`/`−6.51`/`−3.51` bps across
  the ladder). Whenever an overlay is sized off a book with a measured edge, check what view the
  overlay is implicitly expressing; if it is the negation of the edge, the overlay must be small or
  it eats the alpha.
- **Rule 3: a band measured against a quantity that moves with the thing it bounds bounds nothing.**
  ADR-0069's band is `25%` of `max(|target|,|held|)`, so a hedge oscillating around zero has a band
  near zero and trades every cycle — the same defect ADR-0094 fixed on the fusion side. The fix is
  the target's own churn: `T' = sign(T)·max(0, |T| − k·σ)`, `σ` an EWMA of the target's step between
  the moments the hedge can act. One-way by construction, so σ can only shrink the hedge.
- **Expected next.** The HEDGE fee line and its directional bleed both fall; firm gross a little
  lower. **If this scores BAD, the sizing side of the hedge is closed** — do not tune the overlay
  again. The next lever is the question ADR-0098 explicitly deferred: does a mean-reversion book want
  a beta overlay at all, given ADR-0095's unwind-on-ρ² was already reverted for a different reason?
  After that: the frozen MACRO book, and the still-erroring `turnover_cost_by_name` in the report
  (the loop has been blind to per-name cost for several cycles now).

## 2026-07-27 — the desk's two biggest losers are its two most expensive names to trade

- **What the window did.** Total PnL `$467.95` (`+133.49` on the window, `+234.95` over three runs,
  on track) with gross `$58,663.42`, **down** `−3,245.34` on the window after `+44,191.91` over three.
  Net `−$1,087.77`: the firm is nearly beta-flat and still earning, so the structure works.
  Attribution `ALPHA +641.96`, `MACRO +376.98` (frozen to the cent for a fourth cycle),
  `HEDGE −550.99`. Last cycle's ADR-0098 scored ✅ GOOD and `/api/hedging` confirms it live —
  `ON-TARGET`, delta inside the band, the hedge has stopped churning.
- **Attribution honesty.** The window's gain sits on ALPHA positions held for several cycles in names
  the last change never touched; market and change cannot be separated there from the numbers alone,
  so the change is credited with none of it. What IS attributable: the hedge going quiet and gross
  falling. Stated rather than guessed.
- **The trigger.** `GOOGL −$161.84` and `SAP −$85.46` — together over half of what the whole firm has
  made — are also the desk's two most expensive names ever filled (`20.11` and `8.16` bps measured
  round trip, against every other name at `0.41–2.09`) versus a passing source measured at `+9.07` bps.
  ADR-0075 tests each name against its own cost, but a name is only measured AFTER it trades, so an
  unfilled name is charged the desk BLEND (`1.48` bps). The veto is correct and arrives one discovery
  loss too late — every time, by construction.
- **Rule 1: a gate keyed on a measurement that only exists after the fact is a gate that always pays
  once.** When a control tests X per name and X is only observed by trading, look for an ex-ante
  observable of X on the stream. Here it was sitting in the `QuoteCache` all along: the quoted touch
  predicted the realised round trip at ratio `0.99` on GOOGL and `0.98` on SAP.
- **Rule 2: check whether the loss that already happened is queued to happen again before choosing a
  lever.** Six names the desk has NEVER filled (`BRK.B NFLX ORCL GS TSLA GOOGL`) quote the same `20.0`
  bps touch with ~`$120k` of planned gross behind them. A repeat-in-waiting outranks a one-off.
- **On the diminishing marginal return.** The ledger's risk-adjusted column went `0.01627` at `$14,470`
  of gross to `0.00798` at `$58,663` in three runs — the book scales gross faster than PnL. Some of
  that dilution is exactly this: cheap-looking names entering the planned book at full size.
- **Expected next.** The wide names drop out of the increasable set, gross stops growing into them,
  and the GOOGL/SAP bleed does not recur in their successors. **If this scores BAD, the cost side is
  closed** — do not tune the cost model again. Next levers, in order: (a) the diminishing marginal
  return itself — the planned book is `~$379k` gross against `$58.7k` held, so the desk is converging
  on a book 6.5× its size with no book-level statement of how much risk it should carry (vol targeting
  is the literature's answer and this codebase is Carver-shaped but has no volatility target); (b) the
  ADR-0086 chandelier exit has fired **zero** times (`riskCuts: []`) — a risk-reactive exit that never
  reacts is either mis-scaled or dead code; (c) `turnover_cost_by_name` in the report is STILL erroring
  (several cycles now — the loop remains blind to per-name cost, and this cycle only worked around it
  by reading TCA off the live endpoint instead).

## 2026-07-27 — the hedge's target does not just churn, it wanders across zero: the level was fixed, the rate was not

- **What the window did.** Total PnL `$572.09` (`+66.45` on the window, `+333.01` over three runs,
  on track, no danger flags) with gross `$19,422.48` — **down** `−16,180.65` on the window. Last
  cycle's ADR-0099 scored ✅ GOOD (risk-adj `0.01446 → 0.02948`) and `fusion_targets` confirms the
  mechanism live: the wide-quoted names (`GS`, `BRK.B`, `NFLX`, `ORCL`, `TSLA`) still carry large
  planned targets with `deltaQty: 0` — planned, not entered. Attribution `ALPHA +794.32`,
  `MACRO +376.99` (frozen to the cent for a **fifth** cycle), `HEDGE −599.22`.
- **Attribution honesty.** The window's gain is on ALPHA names ADR-0099 never opened or resized — it
  only removed names from the increasable set — so market and change cannot be separated there and
  the change is credited with none of it. Attributable to it: the gross collapse and the wide names
  staying out. The HEDGE line is attributable to neither cycle's work.
- **The trigger.** One HEDGE ES order per cooldown, eleven in a row: BUY `.0068`, BUY `.0097`,
  BUY `.0022`, BUY `.0060`, BUY `.0037`, SELL `.0046`, SELL `.0050`, SELL `.0008`, SELL `.0064`,
  SELL `.0036`, BUY `.0058` — `0.0545` contracts traded to end holding `−0.00494`. **Runs** of buys
  then runs of sells, not alternation: the target is not noise, it is a large quantity that wanders
  across zero because it is minus the net of a book that re-signs its names.
- **Rule 1: fixing the LEVEL of a control does not fix its RATE — check which one the live numbers
  indict.** ADR-0098 subtracts one σ of the target's step and scored GOOD, but here the raw target is
  `−$6,039.12` against `σ_step = $958.51` — **6.3 σ** — so it removes 16% and the overlay chases
  freely. A soft-threshold on the level is invisible to a target that is large and directionless.
  When a control still misbehaves after a level fix, ask whether the defect was ever about size.
- **Rule 2: when a path is missing an identity another path already earned, port the identity before
  inventing a new control.** ADR-0080's asymmetric partial adjustment (slow the risk-increasing leg,
  cut in one cycle) had never been applied to the hedge overlay. Porting it needed only a *rate* with
  provenance, and the owner's own thesis names one — the efficiency ratio. `E = |EWMA(step)|/EWMA(|step|)`
  at the same `λ = 0.94`, zero-initialised so the `(1−λⁿ)` bias cancels in the ratio. No dial, no number.
- **Rule 3: a monotone five-reading bleed outranks any single-window signal.** HEDGE went
  `−323.33 → −451.51 → −509.21 → −550.99 → −599.22` across five scored cycles, independent of
  everything changed above it, while handing back 51% of what the strategy books made. That is the
  definition of signal, and it beat the other queued levers on size alone.
- **Expected next.** The overlay's travelled distance falls (steady-state round trip goes from the
  full `2×|target|` to `2·E×|target|`); the HEDGE directional bleed and firm gross fall with it.
  **If this scores BAD, the hedge's sizing AND rate sides are both closed** — do not tune the overlay
  again. The next question is the one ADR-0098 and ADR-0100 both deferred: does a mean-reversion book
  want a beta overlay at all? After that, in order: (a) the frozen MACRO book — five cycles unchanged
  to the cent, a stranded `0.000029` ES position carrying `+376.99` of realised PnL that nothing is
  managing; (b) no book-level volatility target, which is the literature's answer to a planned book
  that scales gross faster than PnL; (c) the ADR-0086 chandelier exit still shows zero fires; (d)
  `turnover_cost_by_name` in the report is STILL erroring — several cycles now, the loop remains blind
  to per-name cost from Postgres and works around it off the live TCA endpoint.

## 2026-07-27 — the desk was flat on the window while filling 2,254 orders: the buffer's WIDTH was the last unmeasured number in the cost chain

- **What the window did.** Total PnL `$626.57` (`+2.07` on the window — effectively flat — `+292.11` over
  three runs, on track, no danger flags) with gross `$34,531.90`, **down** `−27,584.72`. Last cycle's
  ADR-0100 scored ⚠️ MIXED (risk-adj `0.00996 → 0.01809`) and the live axis confirms the mechanism:
  `trackingRate 0.52696`, `ON-TARGET … under the 4104.24 no-trade band, holding`, **one** HEDGE ES order
  this window against eleven last. Attribution `ALPHA +879.66`, `MACRO +376.99` (frozen to the cent for a
  **sixth** cycle), `HEDGE −630.08`.
- **Attribution honesty.** The `+2.07` is noise on positions ADR-0100 never touched; it is credited with
  the gross collapse and the overlay going quiet, and with none of the PnL. HEDGE's `−30.86` is the market
  against a short ES leg — `412` ES fills at a measured `0.207` bps and `$43.57` of fees, so directional,
  not execution, and attributable to neither recent cycle.
- **The trigger.** No trigger opened a loser: `GOOGL −161.84` and `SAP −85.46` are closed and flat (fixed
  by ADR-0099) and every live position is a winner. The leak is the price of the *good* triggers — JNJ
  bought thirteen times in twelve minutes, GOOG bought five then sold eight, AAPL sold eleven, walking
  toward a target never reached. **ALPHA paid `$355.89` of fees to make `$879.66`: two fifths of what the
  desk makes is handed to the cost of getting there.**
- **Rule 1: when every input to a control is measured but the control's own constant is not, that constant
  is the bug.** ADR-0094 fixed WHAT the no-trade band is measured against and left its WIDTH at Carver's
  published `0.10`. But the desk measures both inputs the width is a function of, every cycle, *for the
  edge gate* — each name's round trip (`0.49–1.01` bps + 1 bp fee a side) and the passing source's gross
  expectancy (`reversion 8.82` bps, 500 resolved, 65 cohorts, `t = 9.3`). `2C/μ` is several times `0.10`.
- **Rule 2: derive the constant from a first-order condition and the parameter you never stated cancels.**
  Closing a gap is worth its cost when `½λσ²g² > C|g|`; substituting the aim's own condition
  `a = μ/(λσ²)` gives `band = a·(2C/μ)`. The risk aversion `λσ²` disappears — so no invented number, where
  a hand-picked wider fraction would have been the `$250k` hedge-cap mistake in miniature.
- **Rule 3: prefer the lever that moves PnL at CONSTANT risk over the bigger lever that moves posture.**
  `HEDGE −630.08` is the larger number (47.5% of firm gross, more than the whole firm's PnL) but its sizing
  and rate sides are both closed and what remains is the posture question; standing the overlay down would
  take firm net `$940 → $17,349` on the strength of a market that has been rising. Turnover is the same
  order of magnitude and costs no risk posture at all.
- **Expected next.** Fills and fees per dollar of PnL fall; gross and net are unchanged by construction
  (the aim path is untouched), so this should read as PnL up at flat exposure. **If this scores BAD, the
  cost chain is fully closed** — model (ADR-0099), rate (ADR-0080), band location (ADR-0094) and band width
  (ADR-0101) — do not tune turnover again. Next levers, in order: (a) the overlay POSTURE question ADR-0098
  and ADR-0100 both deferred — does a mean-reversion book want a beta overlay at all, and if so, hedged
  down to what, given `equity-rebalance-floor-usd = 0` hedges from the first dollar with no reference to
  any declared appetite; (b) no absolute book-level volatility target — the planned book is ~$300k gross
  (`AUDUSD` alone targets `118,737` units, `$78k`) against `$18.1k` held, so gross is set by how many names
  pass the gate, not by how much risk the desk wants; (c) the frozen MACRO book, six cycles unchanged to
  the cent around a stranded `0.000029` ES; (d) the ADR-0086 chandelier exit still shows zero fires — with
  positions living minutes, a 3σ-over-the-holding-horizon stop may be structurally unreachable; (e)
  `turnover_cost_by_name` in the report is STILL erroring, and it is now the aggregate that would grade
  this very decision.

## 2026-07-27 — the aim is an average of PAST targets, so it can invert against the CURRENT one (ADR-0102)

- **Situation.** Healthy, not a de-risk cycle: PnL `−825.93 → +747.98` on the day, `on_track` true,
  breaker clear, firm gross ~3% of the declared `1,500,000` limit. Last cycle's ADR-0101 scored
  ⚠️ MIXED with risk-adj `0.01502 → 0.01622` and was not the culprit for anything.
- **Attribution honesty.** The window's `+83.46` is on positions ADR-0101 never touched — it earns the
  turnover reduction and none of the PnL. The report's `EXPOSURE RISING` flag had already reversed by
  the time I read `/api/risk` live (`46,558.92 → 38,852.99`), which is itself the finding below: gross
  swings `14k → 62k → 39k` run to run with nothing anchoring it.
- **The trigger, found by reading `/api/fusion/targets` NEXT TO `recent_orders`.** The orders were not
  chasing a wrong signal — they were chasing a wrong *intent*. JNJ: aim `+8.043404`, target
  `−219.420787`, held `+104`. The firm's largest position was long in the name its own strongest
  forecast said to be short, and the aim path was walking it further long. EURUSD: aim `−19268.293125`
  against a target of `−13006.790342`. AUDUSD and GBPUSD were about to *open* inverted from flat.
- **Rule 1: a recursion's fixed point is not its invariant — unroll it before you trust it.**
  `aim ← aim + a(T − aim)` unrolls to `a·Σₖ(1−a)ᵏ·Tₜ₋ₖ`: a convex combination of the targets the desk
  held in the PAST, which is not the hull of the target it holds NOW. Gârleanu & Pedersen's aim is an
  average of the current and expected FUTURE targets — every element a position the model wants. Ours
  averaged a realised past, so intent could exceed the target or oppose it. Nobody had checked, because
  the recursion looks obviously bounded.
- **Rule 2: when the signal is mean-reverting, every EWMA over it is a lagged inversion machine.** The
  only source passing the gate is reversion at the 900s rung; it crosses zero repeatedly inside `1/a`.
  Any control that smooths over that horizon spends much of its time on the wrong side. Check every
  remaining smoother in the desk against this.
- **Rule 3: the cheapest good change is one whose one-way property is a theorem, not a hope.**
  `|aim'| ≤ |aim|` and `sgn(aim') ∈ {0, sgn(T)}` both hold by construction and are asserted as tests, so
  the clamp cannot open, enlarge or side-flip a position. Contrast ADR-0093/0087/0088/0096, all reverted
  ❌ BAD because they were *expected* to shrink the book and instead levered it.
- **Rule 4: bound a quantity by one the system already computed, and you introduce no number.** The
  clamp's bound is this cycle's own target. No dial, no convention, no `PLACEHOLDER`.
- **Expected next.** Exposure down, PnL flat-to-up. **If this scores BAD**, doubt the premise that the
  target is a clean statement of intent at the cycle cadence — do NOT retry the clamp. Open levers, in
  order: (a) the overlay POSTURE question deferred by both ADR-0098 and ADR-0100 (`HEDGE −625.85`, 15%
  of firm gross, `equity-rebalance-floor-usd = 0` hedges from the first dollar against no declared
  appetite); (b) no absolute book-level volatility target — nothing anchors gross, which is why it
  swings 4×; (c) the frozen MACRO book, seven cycles unchanged around a stranded `0.000029` ES; (d) the
  ADR-0086 chandelier still shows `riskCuts: []` — and note a TRAILING stop is the wrong shape for a
  mean-reversion book (it cuts at maximum expected reversion); the honest version is a TIME stop at the
  measured horizon; (e) `turnover_cost_by_name` in the report is STILL erroring, third cycle running,
  and it is the aggregate that would grade exactly this decision.

## 2026-07-27 — every size control on this desk is RELATIVE; nothing states how much risk the book should carry (ADR-0104)

- **Situation.** Healthy, not a de-risk cycle: PnL `−825.93 → +824.48` on the day, `pnl_growth_pct 28.31`
  against a `1.0` target, `on_track` true, breaker clear, firm gross ~2% of the declared `1,500,000`
  limit. Attribution `ALPHA +1,083.73`, `MACRO +376.99` (frozen to the cent for a **ninth** cycle),
  `HEDGE −649.73`, fees `$437.66`.
- **Attribution honesty.** The window's `+9.68` is market on positions nothing of mine touched — no PnL
  credit claimed. The `EXPOSURE RISING` flag is **100% last cycle's change**: ADR-0103 scored ❌ BAD and
  was reverted (`gross 819.54 → 27,640.96` for `PnL +12.74`, inside the deadband). No trigger opened a
  loser this window; all seven live ALPHA positions are winners.
- **Rule 1: a lever named in three consecutive postmortems and acted on in none is the lever.** "No
  absolute book-level volatility target — nothing anchors gross, which is why it swings 4×" sat as open
  item (b) for three cycles. Each cycle preferred a smaller, better-understood control. When the SAME
  finding survives three postmortems, stop deferring it — the reason it keeps getting deferred is usually
  the reason it matters.
- **Rule 2: budget-neutral and capped-at-1 are not risk LIMITS — they are shape controls.** ADR-0083 is
  budget-neutral by construction (`Σᵢkᵢ = |C|`); ADR-0079 scales back to "the risk the per-name budget
  already implied". Both read like risk management and neither sets a level. The book's risk was
  `unit-notional × (names that happened to clear the gate) × (how loud their forecasts happened to be)`
  — an accident of the cross-section. Audit every control that *looks* like a limit for whether it can
  actually bind, not just whether it can shrink.
- **Rule 3: when the appetite number would be yours, anchor to the system's own distribution instead.**
  A "12% vol target" would have been the `$250k` hedge-cap mistake again. The median of the desk's OWN
  planned-σ series asserts only "no more risk than you typically carry" — no invented figure, and it
  self-calibrates to any feed (invariant 9). The general move: **replace a chosen constant with a
  quantile of the quantity's own measured history.**
- **Rule 4: sample the RAW input to a self-referential control, never its own output.** The σ series
  records the pre-brake σ. Recording the braked σ would drag the median down every time the brake bound
  and converge the book to zero — the failure mode is silent and terminal.
- **Expected next.** Planned gross stops making several-fold excursions; the top half of the planned-risk
  distribution is trimmed to the median, the quiet cycles untouched. So: exposure down or flat, never up,
  with PnL down proportionally *only if* the trimmed cycles earned as much per unit of risk as the average
  — which is exactly the volatility-targeting claim being tested.
- **Attribution warning for the NEXT postmortem.** The coming window contains **two** deployments: the
  scorer's ADR-0103 revert *and* this brake (the JVM that produced this window predates the revert). Gross
  will fall for both reasons. Do **not** credit the whole move to ADR-0104 — check `bookVolBrake` on
  `/api/fusion/targets` for whether the brake actually bound (multiplier < 1) and how many samples it had,
  before attributing anything to it.
- **If this scores BAD**, the thing to doubt is the median as the reference (too aggressive when the desk
  legitimately wants to be large), not the existence of a level anchor — do not go back to no anchor.
  Remaining levers, in order: (a) the overlay POSTURE question ADR-0098/0100 both deferred — `HEDGE`
  is `−649.73`, 60% of ALPHA's gross P&L, and `equity-rebalance-floor-usd = 0` hedges from the first
  dollar against no declared appetite; (b) the frozen MACRO book, nine cycles unchanged around a stranded
  `0.000029` ES; (c) the ADR-0086 chandelier, still `riskCuts: []` with all 23 σ sensors warm
  (`streamVolMeasuredNames: 23`) — so it is reachable and simply never fires, and a TIME stop at the
  measured horizon remains the honest shape for a mean-reversion book; (d) parametric VaR reports
  `coveredExposure 0.00` against `skippedExposure 24,792.33` — the firm's second risk sensor is blind for
  exactly the reason ADR-0089 fixed for fusion, and the mark-stream covariance that covers 23/23 names was
  never propagated to it; (e) `turnover_cost_by_name` in the report is STILL erroring, fourth cycle
  running.

## 2026-07-27 — the desk's own cost measurement can come out impossible, and one impossible reading sets the desk-wide hurdle (ADR-0106)

- **Situation.** On track, not a de-risk cycle: `pnl_growth_pct 15.33` against a `1.0` target,
  `on_track` true, breaker clear. Attribution `ALPHA +1,213.87`, `MACRO +376.996` (frozen to the cent
  for an **eleventh** cycle), `HEDGE −700.65`, fees `$459.59`.
- **Attribution honesty.** The window's `−1.25` is inside the deadband and is market on positions
  nothing of mine touched — no PnL credit claimed. The `EXPOSURE RISING` flag is **100%** last cycle's
  ADR-0105 revert (`gross 0.00 → 7,181.05`), not a change of mine.
- **Rule 1: verify a DANGER flag against the live endpoints before obeying it.** The header raised
  `BLEEDING; EXPOSURE RISING; DANGER`. Both legs were artifacts — a sub-deadband PnL move, and exposure
  measured from a `$0.00` prior reading (the book re-opening after the ADR-0104 brake flattened it, plus
  the revert). Live gross had *fallen* `7,176.74 → 2,170.03` with PnL *up* by the time I looked. A
  reflexive de-risk would have cut a healthy book. **A delta measured from zero is not a trend.**
- **Rule 2: when a family of levers has two BAD verdicts in five cycles, the next idea in that family is
  the same idea in a costume.** The hedge is the biggest prize on the desk (`realizedPnl −701.03` against
  `feesPaid 46.82` — the overlay's entire loss is realised round-trip churn, ~30 units of turnover per
  unit of exposure moved, consuming 55% of ALPHA's P&L and 39% of firm gross). Every fix I could justify
  mapped onto 0095 / 0098 / 0100 / 0105. I left it. The one genuinely untried angle is rebalance
  **cadence** (the overlay re-prices every ~60s against a book whose measured holding horizon is 900s)
  rather than target **shape** — that is the next hedge cycle, and it should be one-way (slow only
  risk-increasing deltas; reductions and unwinds keep trading in one cycle).
- **Rule 3: a pure scale change is worthless to this loop.** I nearly shipped "strip the diversification
  multiplier from measured-failing sources". It cuts exposure ~5% — and PnL by the same ~5%, because DM
  is pure leverage. **PnL-per-unit-exposure is unchanged.** Before shipping a size control, ask whether
  it changes the *ratio* or only the *scale*; only the first is progress. What moves the ratio is better
  signal per unit of risk, cheaper turnover, or removing exposure that earns nothing.
- **Rule 4: check a proposed control against the telemetry before believing your own story.** Two
  candidates died this way. Cross-sectional demeaning ("the reversion signal manufactures the beta the
  hedge then pays to remove") — the live cross-section is already balanced, `net/gross −0.064`, so it
  would have done nothing. A time stop at the measured horizon — the ladder reads `+8.43` bps at 900s
  and `+9.48` at 3600s, and forecasts are re-emitted every cycle, so a held position is a *renewed* call,
  not un-evidenced risk. Both were good stories; neither survived the numbers.
- **The finding I shipped.** `roundTripBpsByInstrument.NQ = −0.25445`. A round trip cannot pay the desk
  — both legs cross the touch or wait for it and the fee is charged twice. It is arrival-mark drift
  booked as execution, and under ADR-0084 the desk *posts* to enter, so entry drift is systematically
  favourable. Because ADR-0075 states the desk-wide verdict at the **cheapest** entry, that one number
  became the desk's cost and `netEdgeBps` came out **above the measured expectancy for every source at
  once** (`reversion` 8.683 vs a measured 8.429). It also collapsed that name's ADR-0101 buffer to the
  0.10 Carver floor, so the desk re-traded its least honestly-priced name the most often.
- **Rule 5: a MIN over a per-name series is only as honest as its worst entry — sanity-check the sign
  and the units of anything an extremum selects.** The desk-wide gate is monotone in cost and takes the
  minimum, which is correct; it just has no defence against an entry that cannot exist. Any control that
  picks the best/cheapest/loudest element of a measured series inherits that element's estimation errors
  whole.
- **Expected next.** Strictly one-way — every per-name hurdle rises or stays, so this can only remove a
  trade. Exposure cannot grow because of it; PnL effect is small and confined to that name's turnover.
  Expect ⚠️ MIXED or "no material change"; **if it scores BAD the verdict is not about this change**, as
  it cannot add exposure — look for the market or a concurrent revert.
- **Remaining levers, in order.** (a) The TCA shortfall is measured against the ARRIVAL mark, so the
  *whole* cost series is biased low, not just where it crosses zero — every cost-keyed control (gate
  hurdle, per-name veto, ADR-0101 `2C/mu` width) is too permissive by an unmeasured amount; decompose
  shortfall into spread and drift (deferred register). (b) Hedge rebalance cadence, per Rule 2.
  (c) `turnover_cost_by_name` in the report has been erroring **five** cycles running (`column "qty"
  does not exist`) — it is the aggregate that would grade exactly (a); now in the deferred register.
  (d) Parametric VaR reads `coveredExposure 0.00` against `skippedExposure 7,176.74` while the
  mark-stream covariance covers `23/23` names — the firm's second risk sensor is blind for the reason
  ADR-0089 already fixed for fusion. (e) The frozen MACRO book, eleven cycles unchanged.

## 2026-07-27 — the desk was dumping its whole position on every crossing of its own forecast

- **What the window did.** Report SITUATION: total PnL `$922.59`, window `+25.40`, three runs `+67.26`,
  `pnl_growth_pct 11.96` vs a `1.0` target, `on_track` true, exposure FALLING (`−9,680.61` on the
  window). No danger state. Last cycle's ADR-0106 scored ⚠️ MIXED and is one-way by construction, so it
  cannot have caused the exposure collapse — that was the book unwinding across a restart. Clean split:
  the `+25.40` is realised P&L on positions the change never touched.
- **The finding I shipped.** `/api/fusion/targets` plans 23 names into the hundreds of thousands of
  dollars and reports `deltaQty` **exactly zero on every equity name**, against a live gross of
  `$2,677.67` — the desk holds a rounding error of its own plan while filling `3,004` orders and paying
  `$403.17` of ALPHA fees against `$1,255.29` of ALPHA P&L. The tape shows why: AAPL bought
  `1/3/3/4/5/7/8` then sold `22` and `13`; JNJ bought `19/9/28/6` then sold `14/14/34`. ADR-0090 ruled
  that only a FLAT TARGET is an exit, but ADR-0094 moved order derivation into `PositionBuffer`, which
  keyed the full-speed branch on `aim.signum() == 0` — faithful until ADR-0102 began clamping an
  inverted intent to flat in ONE step. With a mean-reverting source that is the steady state, so every
  crossing was read as a cut and liquidated the position at MARKET, paying the full spread on a book
  ADR-0084 had built passively for free.
- **Rule 6: when a later ADR changes how a value can be REACHED, re-check every branch that keys on that
  value.** ADR-0102 did not touch `bufferedDelta`, and `bufferedDelta`'s test for "exit" stayed literally
  correct while becoming semantically wrong. A condition is only as good as the set of ways its input can
  arise. Key a branch on the *cause* (a control planned the name flat) rather than on a *symptom* the
  arithmetic happens to produce.
- **Expected next.** Turnover and fees down; **average gross exposure UP**, because positions now persist
  through crossings instead of round-tripping. Stated as the trade-off up front: if exposure rises and
  PnL does not, ❌ BAD is the correct verdict and the revert is right. A ⚠️ MIXED with PnL up and the
  risk-adjusted read improving is the success case.
- **Remaining levers, in order.** (a) Four sources measured significantly negative at EVERY rung
  (`trend` −6.67 bps t≈−5.9 on 45 cohorts, `momentum`, `social`) hold `1.00` of combined weight against
  `reversion`'s `2.19` — ~31% of the fused forecast comes from sources measured to lose, and on many
  names `trend` is the exact mirror of `reversion` (SAP: `+20` vs `−20`). Do NOT re-attempt ADR-0087's
  floor removal (BAD/reverted); find a different formulation. (b) ADR-0101 widths interact with the
  ADR-0102 clamp so that any name with `2C/mu ≥ |forecast|/TARGET_ABS` can never be opened from flat —
  GOOGL (10.05 bps measured slippage, width capped at 1.0) and SAP are structurally frozen; check
  whether that duplicates the edge gate's own per-name veto. (c) `turnover_cost_by_name` in the report
  has now errored **six** cycles running. (d) The frozen MACRO book, twelve cycles unchanged to the cent.

## 2026-07-27 — the gate was starved by a ROW cap, so the desk could never learn its way to a trade (ADR-0108)

- **What the window did.** Report SITUATION: total PnL `$5725.54`, three runs `+4834.17`, gross and net
  both `$0.00`, no flags. Last cycle's ADR-0107 scored ✅ GOOD, but its own prediction was "average gross
  exposure UP" and exposure went to zero instead; every book reads `unrealizedPnl 0.00000000`, so the
  whole `+4,798.49` is a book that **unwound and booked what it had** across a restart. **Claimed for the
  change: nothing.** A realised-only jump across an unwind cannot be split from the market on the numbers
  alone, and saying so is more useful than inventing a cause.
- **The finding I shipped.** `edgeGate.mayIncrease: false` with `deltaQty: 0` on all nine planned names
  against `$0.00` gross — the desk has been reduce-only its whole life on this feed. The cause is a unit
  mismatch two ADRs old: ADR-0077 moved the standard error onto **cohorts**, but the read stayed bounded
  at `sample-limit = 500` **rows**, a bound written in ADR-0055 phase 1 when an observation *was* a draw.
  So the evidence budget is spent at **cross-section width**: `reversion`@225s and `trend`@225s both hit
  the 500 rows at **58 cohorts** and can never accumulate a 59th, however long the desk runs, while
  `momentum`@225s at 234 rows gets **186**. Uncapping to cohorts: `reversion`@225s → 250 cohorts,
  t = 0.99 → **2.50**, p = 0.16237 → **0.00653** against α = 0.007583. The gate opens.
- **Rule 7: when an ADR changes the UNIT a statistic is computed in, audit every bound expressed in the
  old unit.** ADR-0077 changed the denominator from observations to cohorts and left a row cap in place
  upstream. The cap stayed literally correct ("500 rows") while becoming a cap on *statistical power set
  by breadth*. Same shape as Rule 6, one layer further out: not a branch keyed on a stale symptom, but a
  **budget denominated in a retired unit**.
- **Rule 8: a control that cannot be improved by gathering evidence is broken, whatever its verdict
  says.** The honest test of any gate is "what would make it open?" If the answer is "nothing the desk can
  do", it is not measuring — it is refusing. Ask that question of every hurdle on the desk.
- **Rule 9 (confirms the last cycle's Rule 4): check the story against the table BEFORE writing code.** I
  nearly shipped cross-sectional demeaning — "the sources' expectancy is mostly the market beta the hedge
  pays to remove", which is *true* (222 → 26 bps at 3600s). It moves the t-stat from 1.04 to **1.14**,
  because mean and standard error shrink together, and it zeroes any one-name cohort outright. A correct
  diagnosis is not automatically a lever. Both halves are in ADR-0108 so neither is re-attempted.
- **Expected next.** Gross exposure **rises from `$0.00`** — unavoidable for a book at zero, and stated up
  front as the trade-off. Permission is deliberately narrow: the ADR-0075 per-name test clears only `ES`
  (0.4214 bps) today, `AAPL` reads p = 0.011, and it widens only as √B accrues. The selected rung moves
  3600s → 225s, so the ADR-0080 holding rate becomes `a = 1 − e^(−30/225) = 0.1248` and the ADR-0097
  weights are re-estimated where **`trend` measures significantly negative (t = −2.47)** — the source that
  currently carries the entire book at weight 1.23 gets demoted to the minimum. If exposure rises and PnL
  does not, ❌ BAD is the right verdict and the revert is right.
- **Two live bugs found and NOT shipped (one change per run) — take these next.** (a) `ALPHA JPM SELL 23`
  REJECTED **45 consecutive times**, `no market data for JPM`, while `/api/marks` shows JPM live from
  alpaca at 171 ms: the order module's `LastPriceCache` is fed from `md.marks`, and `MarkPublisher` skips
  any mark the cache calls stale — so a name whose feed goes quiet can be neither entered **nor exited**,
  which is a risk control that fails in the dangerous direction. (b) `ALPHA MSFT BUY` CANCELLED every
  cycle by ADR-0084 re-plan: passive entries are posted at the arrival mark and retired ~30 s later, while
  exits cross at MARKET — a systematic entry/exit asymmetry that decays any book toward flat.
- **Remaining levers, in order.** (a) and (b) above. (c) The TCA shortfall is measured against the ARRIVAL
  mark so the whole cost series is biased low (deferred register). (d) `turnover_cost_by_name` in the
  report has now errored **seven** cycles running. (e) The frozen MACRO book, thirteen cycles unchanged.

## 2026-07-27 — one impossible print was setting the gate's standard error, and the book stayed at zero (ADR-0109)

- **What the window did.** Live `/api/risk` `.total` `$5725.58`, gross and net `$0.00`, every book
  `unrealizedPnl 0.00000000`. PnL moved `$0.04` in four hours. `run-status.json` reads `on_track` true
  at `pnl_growth_pct 542.34`, but that is one realised unwind flattering a frozen book — **read the
  growth flag against the exposure, not on its own.** Last cycle's ADR-0108 scored ⚠️ MIXED and did
  exactly what it claimed (`reversion`@225s 58 → 313 cohorts); it was not enough. **Claimed for the
  change: nothing.** At `$0.00` gross for the whole window there are no positions for the market to
  move either — nothing to attribute in any direction.
- **The finding I shipped.** ADR-0108 uncapped the sample, so the constraint moved into what the larger
  sample *contains*. `reversion`@225s over 313 cohorts: 4.55 bps, se 1.90, t = 2.18, p = 0.0152 against
  α = 0.00758 — shut. Drop **one** cohort: 2.78 bps, se **0.55**, t = **4.33**. That cohort's mean is
  +573 bps where every other sits inside ±35 bps, and its members are GOOG `174.77 → 323.58` (+8,515 bps)
  and NQ `19,773 → 28,677` (+4,503 bps) **over 225 seconds**. The rest of the burst's entry marks are the
  simulator's start levels (`AAPL 188.94`, `MSFT 429.14`, `ES 5438.30`, `GOOGL/TSLA/ORCL` ~`99.8`)
  against a live alpaca tape (`AAPL 337.12`, `ES 7454.75`): a **tape handover booked as a market return**.
  The fix reuses the desk's own bad-print threshold (`jethro.trading.mark-jump-bps`) as the bound on what
  counts as evidence — no new number — and it removes 2 observations of 5,101 (0.04%).
- **Rule 10: a statistic that gates money must have a BOUNDED influence function.** The ADR-0077 standard
  error is a plain sample sd, and it is the denominator of every gate on the desk. One observation could
  therefore set the desk's entire risk appetite, and no amount of honest measurement could outvote it —
  it enters the denominator as well as the numerator. Ask of every estimator that gates something: *how
  many observations does it take to change this answer?* If the answer is one, it is not a measurement.
- **Rule 11: when a control refuses, check whether its INPUTS are physically possible before touching its
  arithmetic.** An 85% equity move in four minutes is not a fact about alpha, and the desk already had a
  name for it — the corporate-action jump guard, whose threshold was sitting in config the whole time,
  keeping that same price out of P&L, orders, sizing and history while the expectancy read it anyway.
  Look for the definition the desk already owns before inventing a new one.
- **Rule 12 (sharpens Rule 9): test the candidate fix on the table, not just the diagnosis.** I nearly
  shipped Hampel's 3-MAD winsor — the textbook answer to a heavy-tailed panel. Run on the live cohort
  means it caps **7–20%** of cohorts, not the 0.27% it is calibrated for, and flips `reversion`@900s from
  t = −0.24 to **t = +7.95**. A correct-sounding standard method can be a worse change than the bug.
  I also killed a *diagnosis* the same way: horizon drift in `resolveDue` looked certain until the table
  showed a median realised window of 237.5 s against a nominal 225 s.
- **Sanity check that a fix is not buying significance:** exactly one of fifteen source × rung cells
  changes verdict, every measured-negative source measures *more* negative (`trend`@225s −2.19 → −4.68),
  and the surviving point estimate FALLS (4.55 → 2.63 bps). If a "cleanup" raises the point estimate or
  rescues several failing sources at once, it is tuning, not cleaning.
- **Expected next.** Gross rises from `$0.00` — unavoidable for a book at zero, stated as the trade-off.
  The selected rung moves 3600s → 225s (so the ADR-0080 rate becomes `a = 1 − e^(−30/225)`) and `trend`,
  which carries essentially the whole planned book at weight 1.26 while measuring t = −4.68 there, is
  demoted to the ADR-0097 minimum. If exposure rises and PnL does not, ❌ BAD and the revert are correct.
- **Remaining levers, in order.** (a) **The tape handover itself** — the mark source changed under a
  running `feed_mode=SIM` session with no invariant-8 epoch roll, and the σ sensors, stream covariance
  and daily-close series all still span both tapes (deferred register). Related and unexplained: the
  20% jump guard did **not** fire on an 85% move and `/api/marks/quarantined` is empty. (b) `ALPHA JPM
  SELL` REJECTED 45 consecutive times, `no market data for JPM`, while `/api/marks` showed JPM live —
  a name whose feed goes quiet can be neither entered nor exited, a control failing in the dangerous
  direction. (c) ADR-0084's entry/exit asymmetry: passive entries cancelled every re-plan while exits
  cross. (d) `turnover_cost_by_name` has now errored **eight** cycles running. (e) The frozen MACRO book,
  fourteen cycles unchanged to the cent.

## 2026-07-27 — the last two changes were never deployed; the loop was scoring a JVM that predated them (ADR-0110)

- **What the window did.** Live `/api/risk` `.total` `$5725.58`, gross and net `$0.00`, `/api/var`
  `"no positions"`, `/api/breaker` `halted: false`. PnL moved `$0.00`. `run-status.json` reads
  `on_track` true at `pnl_growth_pct 538.17` — still one realised unwind flattering a frozen book.
  **Claimed for last cycle's change: nothing**, and this time not for want of positions to attribute:
  the change had not executed at all.
- **The finding.** The running JVM (pid 912480) started `11:19:32`; ADR-0109 was committed at `12:18:59`.
  `logs/improve-2026-07-27.log` at both the 10:00 and 12:00 cycles: `Failed to restart jethro.service:
  Unit jethro.service not found.` → `deploy command FAILED — app NOT restarted`. The crontab carried the
  **systemd EXAMPLE from `ops/README.md`** on a box that runs the app from `scripts/run-local.sh`. So
  ADR-0108 and ADR-0109 were built, committed, pushed, baselined and **scored** against a binary that
  never contained them — two ⚠️ MIXED "no material change" verdicts that describe the old jar. And the
  `./gradlew :app:bootJar` half kept succeeding, rewriting `app-0.1.0-SNAPSHOT.jar` under the **live**
  JVM, which has thrown `ClassNotFoundException: org.springframework.util.PatternMatchUtils` ever since.
- **Rule 13: a verdict on code that was not running is not a measurement — it is manufactured evidence.**
  Worse than a wasted cycle, because it is *consistent*: the ledger row, the report and the heartbeat all
  agree, and the next agent correctly concludes "that idea did nothing" about an idea that never ran. The
  fix verifies the deploy against the **running process** (`/api/ops/jvm`: boot time = `now − uptimeSeconds`
  must be ≥ the moment the deploy started), not against the deploy command's exit status, and falls back
  to `scripts/svc.sh restart app`. Chosen because it is mechanism-agnostic and asks the only question the
  scorer's validity rests on.
- **Rule 14: check the CLOCKS before the statistics.** I spent the first minutes of this cycle re-deriving
  the edge gate's t-statistics from `signals_telemetry` — sensible-looking work on a table that could not
  have reflected the change I was grading. What actually broke the case was three timestamps: process
  start `11:19:32`, commit `12:18:59`, jar mtime `12:20`. Before diagnosing why a change "did nothing",
  confirm it *ran*: `ops_jvm.uptimeSeconds` against `git log --date=iso` is a ten-second check and it
  invalidates every other reading when it fails.
- **Rule 15: a restart must reproduce the CONFIGURATION, not just the binary.** The fallback is
  `svc.sh restart app` and not a bare relaunch because `run-local.sh` defaults `PROVIDER` to `yahoo` and
  only reaches `alpaca` through `local.env`. A "restart" that silently changes the mark source is an
  invariant-8 epoch event — precisely the tape handover ADR-0109 was cleaning up after. Also: it stops
  the app *before* rebuilding, so the jar is never rewritten under a live JVM.
- **Expected next.** The next scored window contains **three** deployments' worth of code (ADR-0108 and
  ADR-0109 finally reaching the JVM, plus this fix), so its vector will not attribute cleanly to any one.
  Gross should rise from `$0.00` — ADR-0109's stated trade-off, which it never got to demonstrate. Read
  the next verdict as "the last three cycles, now actually running", and do not credit or blame this
  change for the trading outcome.
- **Remaining levers, in order.** (a) Teach the scorer to refuse to score a commit it cannot prove was
  deployed — the deeper fix, kept out of this change because the scorer is the invariant-7 authority for
  the ledger's money numbers (deferred register). (b) The crontab still carries the bad
  `JETHRO_DEPLOY_CMD`; one owner edit removes the failure-then-fallback path. It was NOT edited here —
  `crontab -l` stopped returning content mid-cycle and a blind rewrite could have deleted the schedule.
  (c) The tape handover under a running `feed_mode=SIM` session, and the jump guard that did not fire on
  an 85% move. (d) `ALPHA JPM SELL` REJECTED 45× on `no market data for JPM` — a name that can be neither
  entered nor exited. (e) ADR-0084's entry/exit asymmetry. (f) `turnover_cost_by_name` errored a **ninth**
  cycle. (g) The frozen MACRO book, fifteen cycles unchanged.

## 2026-07-27 19:00Z — a merge can revert a fix while leaving its ADR standing

- **The finding.** ADR-0110's deploy verification is **not in `HEAD`**. Merge `014359b` had two parents:
  `d754109` (local, carrying the verification) and `ef16deb` (remote, `fix(ops): safe deploy — stop the
  app BEFORE rebuilding the jar`) — an independently-authored fix to the *same* step of
  `ops/improve-loop.sh`. The merge kept the remote side. ADR-0110's document, its README lines and its
  finding all survived; only the code died. The 15:00 log proves it: still
  `deploy: ./gradlew :app:bootJar -x test && sudo systemctl restart jethro` →
  `Failed to restart jethro.service` → `deploy command FAILED — app NOT restarted`, and the `bootJar`
  half still rewriting the jar under the live JVM. Restored, reconciled with `ef16deb` rather than
  reverting it: the default *and* the fallback are now `scripts/svc.sh deploy app` (stop-before-rebuild,
  strictly better than ADR-0110's original `restart app`), with the `/api/ops/jvm` boot-time check on top.
- **Rule 16: a surviving ADR is not evidence that its code survived.** Rule 14 said check the clocks
  before the statistics; this is its twin — check that the *mechanism* is still in the tree before
  trusting the *record* that says it was built. `git log --format='%h %p'` on the merges and
  `git diff <fix-sha> HEAD -- <the file>` is a ten-second check, and on this branch the loop and the
  maintainer share one branch and edit the same ops files, so same-region collisions are the norm, not
  an accident. When a fix and a doc land together, the doc is the thing most likely to outlive the fix.
- **Rule 17: a commit that touches no runtime code cannot have moved the vector — do not let it be
  scored.** `668a95704` is `docs/` + `ops/` + `reports/` only, yet it was charged with `+$10,408` of
  gross and auto-reverted as ❌ BAD. The gross move was ADR-0107's flattening reversing as ADR-0108/0109
  finally reached the JVM — which last cycle's finding had explicitly predicted. Two compounding harms:
  the ledger carries a manufactured verdict, and the loop tried to amputate its own plumbing. The revert
  then **conflicted** (loop-only commits touch `reports/last-analysis.md`, which every cycle rewrites),
  so the row says "reverted" while nothing was — the note is false even though the numbers are sound.
- **Rule 18: read the DANGER flag as a question, not a verdict.** It fired on `-$0.78` (0.014% of PnL)
  plus a gross move from `$0.00` — a book that had been flattened coming back to life. Bleeding-plus-
  adding-risk is the right thing to watch for, but "exposure rose from zero" is re-entry, not
  escalation, and de-risking into it would have undone the gate work of the last three cycles. Check
  whether the prior gross was `$0.00` before treating a rise as risk being added into a loss.
- **Untouched, and now the top lever:** net exposure `$10,407.01` **equals** gross — the book is
  100% one-way, seven EQUITY positions, while the HEDGE book holds `$0.00` gross on `-$3,071.55`
  realised and trades ES in `0.003`-contract clips. A hedge that rounds to nothing is not a hedge.
  That is next cycle's change, unless the deploy is still not turning the JVM over.

## 2026-07-27 20:00Z — a full target book placing zero orders, and the 14% conviction tax that kept it there

- **The finding.** Gross `$0.00` with **eleven live targets** and `deltaQty: 0` on every one — the desk
  was not idle, it was *blocked*. `/api/fusion/targets` names the two gates that compose into a halt:
  (a) ADR-0075 charges each name its own measured round trip against `reversion`'s 225 s edge
  (`avgReturnBps 2.0087`, `stdErrorBps 0.4271`), and of the equities only **AAPL** (`0.897` bps) clears —
  JPM `1.075`, JNJ `1.204`, MSFT `1.338`, GOOG `1.854`, GOOGL `20.106`, blend `1.327` do not; seven of
  eight `aims` read exactly `0.0`, which is only reachable through the reduce-only re-seed. (b) AAPL then
  sits just inside its own ADR-0101 band. Meanwhile `trend` (−1.5033 bps, 451 cohorts, **t = −3.89**) and
  `momentum` (−2.9177, **t = −3.02**) each held the ADR-0097 floor weight 0.25 against `reversion`'s
  2.899 — and with opposite-signed forecasts that cost 13.6% of the combined value on every name they
  called (GOOG: `13.319070` instead of `15.134`). Shipped ADR-0111: stand a **contradicted** source down
  to weight 0; keep the floor for a merely **unproven** one.
- **Rule 19: `deltaQty: 0` on a book with live targets is a BLOCK, not a decision — read the aims.** A
  flat book and a flat *target* book look identical in the SITUATION header and are opposite diagnoses.
  `aims` reading exactly `0.0` is the tell: that value is unreachable through the rate step (it would be
  `target·a`) and can only come from the ADR-0064/0075 reduce-only re-seed. One endpoint call separates
  "the desk has no view" from "the desk has a view it is forbidden to act on".
- **Rule 20: two controls that are each individually correct can compose into a halt, and neither will
  report it.** The gate published `mayIncrease: true` while the *per-name* test refused seven of eight
  names; the buffer published nothing at all. Nobody was wrong and the book was at zero. When exposure is
  `$0.00` against a non-empty target book, look for the *composition*, not the culprit.
- **Rule 21: "not demonstrated" is two findings.** UNPROVEN (the sample cannot tell) and CONTRADICTED
  (the sample says it loses) deserve different treatment; collapsing them hands live conviction to a
  measured loser. The test costs nothing to add — it is the same statistic with the sign reversed.
- **Predicted next, so it can be checked rather than re-derived.** Gross should rise from `$0.00`;
  ADR-0111 raises exposure by design, and if the reversion edge does not survive contact the loss is
  larger, not smaller. Do **not** credit or blame this change for a market move on positions it did not
  open — cross `recent_orders` against the scored diff first.
- **The binding constraint is NOT re-weighting — it is execution cost.** Seven of eight names remain shut
  because measured round trips (0.90–1.85 bps/name) sit close to what a measured edge with a sub-1 bp
  lower bound can pay. Next cycle's lever, unless the vector says otherwise: cost. Note
  `turnover_cost_by_name` — the one aggregate that would show cost per name — has now errored for a
  **tenth** consecutive cycle, and 553 orders died on ADR-0084 re-plan churn.
- **Rule 17 fired again, unchanged:** `6578cf49f` is `docs/`+`ops/`+`reports/` only and was still scored
  (⚠️ MIXED, −$72.74 / −$10,401.12). The numbers are the scorer's and sound; the *attribution* is void.

## 2026-07-27 21:00Z — the blend is a closed loop: a name that has never filled can never fill

- **The finding.** `mayIncrease: true` and every single `aim` reading exactly `0.0` — the desk-wide gate
  open and every name reduce-only. The arithmetic is mechanical and has nothing to do with any signal:
  `reversion` clears the ADR-0082 225 s rung at `avgReturnBps 1.913961` / `stdErrorBps 0.403198`, so the
  most a name may cost and still clear `t ≥ 2` is **1.107565 bps** — while the blend an *unmeasured*
  name is charged is **1.326575 bps**, *dearer than the hurdle*. Of 27 names with a live mark, 8 carry a
  measured round trip and only ES (0.430), AAPL (0.897) and JPM (1.075) sit under it. Shipped ADR-0112:
  a third cost rung, Roll (1984) effective spread from the name's own prints, floored at the cheapest
  round trip the desk has actually paid.
- **Rule 22: a cost fallback that fails the hurdle is a CLOSED LOOP, not a conservative default.** A name
  is measured only after it fills → an unmeasured name is charged the blend → the blend fails → it is
  held reduce-only → it never fills → it is never measured. The tradable universe then freezes to
  whichever names happened to have filled before the hurdle last tightened, and no edge anywhere can
  unfreeze it. The tell is arithmetic, not telemetry: compare the fallback cost against
  `avgReturnBps − tHurdle·stdErrorBps` every time either moves.
- **Rule 23: check that a fallback's INPUT exists on this feed before trusting the fallback.** ADR-0099
  exists to break exactly this loop and is **inert here** — all 27 marks carry `bid: null, ask: null`, so
  `quotedRoundTripByInstrument` returns empty on every cycle. A rung that never fires reads identically
  to a rung that fires and finds nothing. One `python3 -c` over `### marks` separated the two.
- **Rule 24: prefer a floor that is also a PROOF.** `max(cheapestMeasured, estimate)` was chosen not for
  caution but because `EdgeGate` takes the desk-wide verdict at the cheapest entry in the map — so
  flooring at exactly that minimum makes the desk-wide verdict, every `netEdgeBps` and the ADR-0101
  buffer's reference cost provably unchanged, and confines the change to per-name hurdles. When a control
  is one-way, find the invariant that makes it one-way and assert it as a test.
- **Attribution, honestly.** The −$0.03 this window is **neither market nor change** — it is dust on a
  book holding nothing (all ten position rows `quantity: 0`, zero unrealized). ADR-0111 *did* reach the
  JVM (boot 20:22:58Z vs commit 20:22:10Z; weights now read `trend: 0.0, momentum: 0.0`) and earns credit
  and blame for nothing. The −$83.16 over three runs is the 19:00–20:00 flattening crystallising, not code.
- **Predicted next, so it can be checked rather than re-derived.** Gross should rise from `$0.00` as names
  outside the 8-name measured set are priced from their own tape. ADR-0112 raises exposure **by design**;
  if the reversion edge does not survive the newly-admitted names the loss is larger, not smaller. Cross
  `recent_orders` against the scored diff before crediting or blaming it for any market move.
- **Still open, now three cycles running:** `turnover_cost_by_name` has errored for an **eleventh**
  consecutive cycle — the one aggregate that would show cost per name, on the cycle where cost was the
  lever. And the HEDGE book still holds `−$3,093.31` realised on `$0.00` gross, trading ES in 0.003–0.069
  contract clips: 35% of ALPHA's `+$8,370.51` handed to an overlay that was flat when the book was 100%
  one-way. That remains the next lever after this one is scored.

## 2026-07-27 22:00Z — the tape stopped two hours ago and every sensor kept "observing" it

- **The finding.** `instruments: 0`, `targets: []` — not eleven blocked targets like last cycle, **none at
  all**. `reversion` (weight 2.90, the only significant edge at `t = 3.55`) publishes nothing, and with
  ADR-0111 correctly holding `trend`/`momentum` at 0.0 the cross-section is empty. The WARN log names the
  cause on every equity: *"reversion sensor still cold for AAPL after seeding 1 of 241 stored prices"*.
  `/api/history` shows why — AAPL's series is 1,926 points at a 12 s cadence and then a single
  **59.7-minute hole** immediately before the newest point (JPM 56 min, ES 57.7), so the seed walk
  truncates after one sample. `/api/marks` explains the hole: the seven Alpaca equities carry provider
  clocks **122.8 minutes** old (the 20:00Z cash close) against **ingest ages of 180–558 ms**. Shipped
  ADR-0113: a sensor advances on PRINTS, not on cycles — `PrintClock` admits a mark only when its provider
  timestamp is strictly newer than the last one that sensor consumed.
- **Rule 25: `stale` is a WARM-LOAD MARKER, not a freshness measure — never read it as "this price is old".**
  `MarkCache.loadStale` sets it at boot and the first live tick clears it *permanently*. Both continuous
  sensors carried the comment "never advance the sensor's windows on a repeated stale price" guarded by
  exactly that flag, so the guard fired for seconds after each restart and was inert for the rest of the
  process life — including for a name whose tape stopped hours ago. Rule 23 again, one layer down: the
  question is not whether the guard exists, it is whether its *input* can ever be true on this feed.
- **Rule 26: a repeated last-value price is not a quiet observation, it is a FALSE one — and it corrupts in
  two directions.** It decays the scale estimator both sensors divide by toward zero, so the first real
  move at the reopen is divided by a near-zero denominator and reports an extreme conviction (the ADR-0066
  pin, reached from the other side, at the open, on the dominant-weight source); and it books a telemetry
  call every cycle off a price that never moved, each resolving at exactly zero, which shrinks the cohort
  standard error the ADR-0075 edge gate reads. Structural zeros do not make a statistic quieter, they make
  it falsely significant.
- **Rule 27: when two clocks disagree, name which one the code is asking about.** Ingest age (sub-second)
  and provider age (two hours) were both live on the same mark and answered opposite questions. The
  parameter-free test — "is this provider timestamp strictly newer than the last one I consumed?" — needs
  no threshold, so there is no number to choose and none to give provenance to, and it reads a live feed,
  a delayed feed, a replay and a sim clock identically (invariant 9). Prefer the formulation with no dial
  in it over the one that works with a well-chosen dial.
- **Attribution, honestly.** −$0.07 on a book holding nothing: neither market nor change. ADR-0112 *did*
  reach the JVM (the per-name cost map grew from 8 entries to 12, ZB/ZF/ES at the floor, NVDA/AMZN/SAP
  newly priced) and earns credit and blame for nothing, because nothing ever reached the cost gate. The
  cost ladder was the right diagnosis of last cycle's telemetry and the wrong layer for this one.
- **Predicted next, so it can be checked rather than re-derived.** Gross stays `$0.00` overnight — this
  change *narrows* the observed universe and **lowers** exposure by design; it should score MIXED and that
  is the correct outcome, not a failure. The thing to check at the next session open is whether the
  equities' first real prints produce ordinary-magnitude readings rather than capped ones.
- **Still open, unchanged:** `turnover_cost_by_name` has errored for a **twelfth** consecutive cycle. And
  the HEDGE book still holds `−$3,093.31` realised on `$0.00` gross against ALPHA's `+$8,370.51`, trading
  ES in 0.003–0.069 contract clips. That remains the next lever once the desk can form a view again.

## 2026-07-27 23:00Z — the seed still counted in cycles after ADR-0113 moved the sensors onto prints

- **The finding.** `instruments: 0` again, and the WARN log is unchanged: *"reversion sensor still cold for
  ES after seeding 1 of 241 stored prices"*. `/api/history` per name gives the median inter-print gap:
  **19.9 s** on the Treasury curve, **90 s** on NQ, **778 s** on GBPUSD, **1,199 s** on ES — against a 10 s
  reversion cadence. `SensorWarmup.seedPrices` derives its lookback (`interval × samples × 2` = 80 min) and
  its hole tolerance (`interval × 30` = 300 s) from the POLL cadence, so for ES every *ordinary* 20-minute
  print interval read as an outage and the walk broke at the first one. Shipped ADR-0114: both quantities
  are now counted in `max(poll interval, that name's median inter-print gap)`.
- **Rule 28: when an ADR changes the UNIT a quantity accumulates in, every quantity derived from the old
  unit is now wrong — go find them in the same breath.** ADR-0113 made a warm-up of 241 samples mean 241
  *prints*. Two derived numbers in the seed still meant 241 *cycles*, and neither is in the file ADR-0113
  touched. The bug was not in the change; it was in everything the change silently re-based. The check to
  run is not "does the new code work" but "what else was denominated in the thing I just redefined".
- **Rule 29: a fix and a rejected alternative can look identical from the outside — carry the distinction
  into a test, not just the prose.** ADR-0113 explicitly rejected "widen `SensorWarmup`'s gap tolerance so
  the seed bridges the halt", and this change makes gaps up to 10 h admissible on ES. It is not that
  alternative, because the tolerance is a multiple of the *name's own* interval: AAPL's 3-hour cash-close
  hole against its 12 s tape still truncates. That is `stillTruncatesAtACashCloseHaltOnAFastTape`, and it
  exists so a future cycle can tell the two apart without re-deriving the argument.
- **Rule 30: "no view" and "cannot form a view" produce the same telemetry and want opposite responses.**
  `instruments: 0` looked like ADR-0113 correctly standing the desk down over a closed tape — and for the
  seven equities it was. But ES, NQ and the FX pairs were *printing* the whole time, 7–19 minutes apart,
  and were silent for a plumbing reason. Before concluding the market gave the desk nothing, check whether
  the desk could have *heard* anything: the answer was in the per-name print cadence, not in the P&L.
- **Attribution, honestly.** +$11.81 on a book holding literally nothing — every position row flat, zero
  unrealized, last fill 19:54Z. Neither market nor change. ADR-0113 reached the JVM (boot 22:26Z against a
  22:25Z commit) and earns credit and blame for nothing.
- **Predicted next, so it can be checked rather than re-derived.** NQ and the rates curve should reach a
  full seed; ES/GBPUSD/AUDUSD seed from whatever the 12 h retention holds (~36 points for ES — better than
  1, still short of 241). Gross likely stays `$0.00` overnight since the ADR-0113 registry freshness window
  (600 s) still expires an ES view between its 20-minute prints, so a MIXED verdict is the expected and
  correct outcome. The thing to check is the **seed counts in the WARN log**, not the P&L.
- **Still open, unchanged:** `turnover_cost_by_name` has errored for a **thirteenth** consecutive cycle. And
  the HEDGE book still holds `−$3,093.31` realised against ALPHA's `+$8,382.25` — note its fees are only
  `$49.79`, so that drag is **directional, not churn**: the overlay was short ES into a rising tape doing
  its job. Judging whether it is worth its cost needs the desk forming views again first.

## 2026-07-28 13:30Z — the book didn't lose money, it was replaced; and the stop distance was decaying to zero

- **The finding.** `total PnL $0.00, gross $0.00` is not a drawdown and not staleness. The Alpaca feed was
  mis-tagged `SIM` and was corrected to `LIVE` overnight (`2c5b6d2`), so invariant 8 / ADR-0029 opened a new
  epoch and the SIM `$5,665.94` correctly does not carry across. Fresh flat live book; last fill
  `2026-07-27 19:54:38Z`; `orders_day: 0`. No trigger to post-mortem this window and no market effect on a
  book holding nothing — **neither** market nor change, and I said so rather than inventing an attribution.
- **What I shipped (ADR-0116).** `StreamVolatility`, the per-name σ that ADR-0086's risk cut measures its
  stop distance in, was fed once per 30 s cycle from a last-value mark cache, absorbing every republished
  price as `r = ln(p/p) = 0`. σ *is* the cut distance, so at span 4 a name warmed on ±1 % steps carries a 3σ
  trigger of 32.70 % over an hour — and twenty republished marks (ten minutes of quiet) take that to 0.198 %.
  Every name held across a close would be stopped out on the first genuine move of the next session, at full
  spread, then held reduce-only for a holding horizon. Gated on the ADR-0113 `PrintClock`.
- **Rule 31: a class that documents a rule it does not enforce is a bug, and the javadoc is the tell.**
  `StreamVolatility` already said a fabricated zero return "would bias the estimate toward *this name does
  not move*, which is the dangerous direction for a control that decides when to cut" — and then guarded only
  the NULL price, while the real source of fabricated zeros walked past. When a class states its own
  invariant, check the enumeration of cases it enforces it over before trusting it.
- **Rule 32: a decayed statistic and an absent one are opposite failures — find which one the consumer
  treats as safe.** `sigmaPerSample` is empty when variance is exactly 0, so a *long enough* freeze underflows
  to silence and the cut correctly stands down. The danger band is the partial decay in between — minutes to
  ~11 h — where σ is small, positive and believed. A weekend was safe by accident; a lunch lull, a halt and a
  thin name were not. Do not reason about the extreme case and assume the middle is milder.
- **Rule 33: `$0.00` means read the equity curve's `feed_mode` column before calling it a loss.** The whole
  triage turned on `firm_equity_curve` carrying a `LIVE` row at `0.00` beside SIM rows at `5,665.94`. A
  headline that resets to zero is an epoch boundary until proven otherwise.
- **Still open, unchanged.** `turnover_cost_by_name` has errored for a **fourteenth** consecutive cycle.
  `/api/market/regime` reads `volRatio 4.6e21` / `ELEVATED` on a calm CHOP tape — the SAME cycle-clock defect
  in `VolatilityRegime`, made permanent by ADR-0051's deliberate freeze against upward baseline moves; it is
  dormant only because fusion is the sole order origin, and it is now a register row (not a third fix in one
  cycle). The desk holds a view on 1 name of 35 while the LIVE sensors warm — that self-heals with prints and
  is the thing to re-check next cycle, along with whether σ survives the 20:00Z close.
- **Predicted next, so it can be checked rather than re-derived.** A still-flat book scoring MIXED. The
  check is the reopen, not the P&L.

## 2026-07-28 14:00Z — the desk reopened, traded twice, and shut itself; the seed that could never finish

- **The window.** First LIVE positions of the epoch: `ALPHA AAPL SELL 1` at 13:40:44Z and `HEDGE ES BUY
  0.001136` ten seconds behind it. AAPL is the **winner** (`+$1.28` unrealized), the ES hedge leg is
  `-$1.44`; both names fell almost the same percentage, so a `hedge_beta 1.25` overlay (refdata, ADR-0040)
  necessarily lost a shade more than the short made. **Market, not change** — nothing I shipped opened,
  closed or resized either leg, and one hour of two co-moving names says nothing about the hedge ratio. PnL
  `-$0.21` (up `+$0.42`), gross `$759.72` and falling, breaker clear. No danger state.
- **Why it then stopped, and why that is correct.** `mayIncrease: false`. The edge gate went ACTIVE the
  moment the first fill gave it a measured cost — before that it was open only for want of one
  (`roundTripCostBps == null` ⇒ "gate inactive"). Now `resolved` is 0–5 against `minSample 30` and trend's
  expectancy is negative, so it refuses to pay. It self-heals: observations accrue from published
  forecasts, not from fills. I did not touch it.
- **Rule 34: the desk's one free trading window is the one before its first fill.** A fresh epoch's gate is
  open because nothing has been measured, not because something was proven. Whatever the desk puts on in
  that window is what it holds until it earns 30 resolved observations. Read `orders_day` against
  `edgeGate.sources[].resolved` before concluding the desk "decided" anything.
- **What I shipped (ADR-0117).** `StreamVolatility` and `StreamCovariance` count **returns**; the ADR-0071
  seed replays `warmupSamples()` **prices**; N prices are N−1 returns. The seed therefore landed exactly one
  return short — every time, every name, any depth of history, since `seedPrices` caps the walk at the count
  it is handed. Both now expose `warmupPrices()` / `warmupSnapshots()` = `warmupSamples() + 1`.
- **Rule 35: a log line reading "n of n, and still not ready" is an off-by-one until proven otherwise.**
  `still cold for NQ after seeding 120 of 120 stored prices` and `still cold after seeding 120 synchronised
  snapshots` were the bug printing its own diagnosis. Every *other* cold name was history-bound (62, 71, 99
  of 120) and looked identical in the log — the two that got everything they asked for were the tell. When
  scanning warm-up warnings, sort by whether the supply met the demand, not by how many are cold.
- **Rule 36: a one-sample error is only harmless while samples are cheap.** This was invisible while a
  cycle produced a sample every 30 s. ADR-0113/0116 re-denominated both estimators in PRINTS, and the same
  one sample became ~90 s on NQ, ~13 min on GBPUSD, ~20 min on ES — against a 30-minute process life. When
  a change re-bases a unit, re-audit the ±1s that were rounding errors in the old unit.
- **Still open, unchanged.** `turnover_cost_by_name` has errored for a **fifteenth** consecutive cycle
  (`column "qty" does not exist`) — the cost/turnover lens the loop contract names as its order post-mortem
  source has been dark since 2026-07-26; it is a report-SQL fix, not a money change, and it is now the
  strongest candidate for a cycle where nothing better presents itself. `/api/market/regime` reads
  `volRatio 0.65 / CALM` this run, so the `4.6e21` reading has cleared on its own — the `VolatilityRegime`
  cycle-clock defect behind it is still register-only and still dormant.
- **Predicted next, so it can be checked rather than re-derived.** MIXED again on a reduce-only book. The
  check is the **WARN log at the next boot**: the σ and covariance seeds should log *warmed* rather than
  cold, and `streamVolMeasuredNames` should rise off 1 toward `covarianceCoveredNames: 6`. If they do not,
  the remaining cold names are genuinely history-bound and ADR-0117 is done its part.

## 2026-07-28 14:30Z — the post-mortem lens was reading yesterday's epoch

- **The window.** Zero orders. PnL `-$0.88` (down `-$2.07`), gross `$759.76` (up `+$1.25`) — both moves are
  mark drift on two legs that have not changed size since 13:40:54Z. AAPL short `+$0.92`, ES hedge leg
  `-$1.76`; the names co-moved and a beta-1.25 overlay necessarily lost a shade more than the short made.
  **100% market, 0% change.** The header's DANGER flag (bleeding + exposure rising) was a **false positive**:
  the desk added no risk, so cutting here would only crystallise a loss and pay the spread.
- **Rule 37: "exposure rising" with zero orders is a mark, not a decision.** Check `orders_day.total`
  before you believe an exposure delta. Only a delta with an order behind it is a risk decision you can
  attack; the rest is the tape moving under a position you already hold.
- **ADR-0117 verified as predicted.** Every risk-cut σ WARN now demands `121` and is met with less
  (`109/121`, `86/121`, `104/121`, `73/121`) — remaining cold names are genuinely history-bound, and the
  `n of n, and still cold` lines are gone, covariance included. Rule 35 held.
- **What I shipped.** The cost/turnover post-mortem lens in `scripts/system-report.py`, dark for sixteen
  cycles. Three faults, one lens: `sum(abs(qty))` on a table whose column is `quantity` (the error row);
  share count published under a header saying *turnover cost* (now `quantity × price × contract_multiplier`,
  the multiplier joined from `instrument` refdata, plus `fee_bps`); and `recent_orders` **hardcoded to
  `feed_mode='SIM'`**. All three are now scoped to the mode of the most recent row.
- **Rule 38: a hardcoded `feed_mode` in a diagnostic is a time bomb that goes off at the epoch boundary.**
  It fails silently and in the worst direction — the lens kept returning 60 confident rows of the *previous*
  sim epoch while the two orders that built the live book were invisible in it. When a report filters on
  mode, derive the mode from the data (`order by created_at desc limit 1`), never spell it out. Same rule
  for any aggregate: `fills_by_day` was pooling 2,770 sim fills with 2 live ones on one date row —
  invariant 8 applies to the *report*, not only to the platform.
- **Why not a money change.** The desk is reduce-only and correctly so: on LIVE data trend@225s is `118`
  resolved at `-1.11` avg bps and reversion@225s is `58` at `-0.60` — negative expectancy *before* cost.
  Forcing the edge gate open is the one move here that reliably loses money.
- **Predicted next, so it can be checked rather than re-derived.** MIXED (report-only change; the scorer
  reads live endpoints and cannot see it). The check is that `turnover_cost_by_name` and `recent_orders` in
  `logs/report.md` show **LIVE** rows — at which point per-name cost becomes a loop input for the first time.

## 2026-07-28 15:00Z — the desk held a short its own model wanted long, and could not close it

- **The window.** Zero orders again. PnL `$0.87` (up `+$2.04`), gross `$759.71` (down `-$0.34`) — mark
  drift on the same two legs, unchanged since 13:40:54Z. **100% market, 0% change.** Rule 37 applied
  cleanly: `orders_day.total: 2` means no exposure delta here is a decision.
- **Last cycle's prediction verified.** `turnover_cost_by_name` and `recent_orders` now carry LIVE rows.
  Per-name cost is a loop input for the first time, exactly as forecast.
- **What I found.** `/api/fusion/targets`: AAPL `targetQty +6.031064`, `currentQty -1.0`, `deltaQty 0`.
  The desk was SHORT a name its own combined forecast wanted LONG, forbidden by the shut edge gate to
  rebuild it, and frozen out of closing it — then hedging that unwanted short with ES, so it paid gross
  exposure on BOTH legs for a position no control wanted.
- **Rule 39: `deltaQty: 0` is ambiguous, and one of its meanings is a trap.** It reads identically whether
  a position is inside its buffer by design or stuck in it forever. When intent (`aim`) and holding are on
  opposite sides and the delta is zero, the desk is not winding down — it is frozen. Check the *aim*
  against the *holding*, not just the delta, before believing a book is being managed.
- **Rule 40: a no-trade band must be scaled by a position the desk is PERMITTED to hold.** ADR-0094 sizes
  the band from the target's implied average position; under a reduce-only gate that target is
  unreachable, so the band was a no-trade region derived from a position the gate forbids. Any wrong-side
  holding smaller than it never traded. Whenever a control's threshold is scaled by a quantity another
  control has vetoed, expect exactly this class of freeze.
- **What I shipped (ADR-0118).** A flat aim in a gate-shut name is an EXIT, worked in full. Conjunctive on
  purpose: reduce-only alone must not liquidate a book merely on hold, and a flat aim alone is ADR-0090's
  churn case — which needs the ability to REBUILD, and a shut gate removes it. A test pins that an OPEN
  gate is byte-identical. Band 13.813752 vs gap 1.000000 asserted as exact decimals.
- **Honest cost, recorded so it is not forgotten.** The exit crystallises the short's unrealised PnL, and
  on a wrong-side position that is most likely a loss. Accepted: carrying $760 of gross across two legs to
  earn mark noise is worse risk-adjusted PnL than being flat.
- **Predicted next, so it can be checked rather than re-derived.** The desk buys back 1 AAPL, goes flat,
  and the ES hedge unwinds behind it — gross exposure falls on both legs toward zero, PnL moves only by
  the round trip. Likely scored ⚠️ MIXED with "risk-adj n/a (zero gross)". The check is
  `recent_orders`: a LIVE `ALPHA / AAPL / BUY 1` followed by a `HEDGE / ES / SELL`. If AAPL is still short
  1 with `deltaQty: 0` next cycle, the branch did not fire and the diagnosis is wrong.

## 2026-07-28 15:30Z — the desk's only position was the one name its sensors disagreed about

- **The window.** Zero orders again; `orders_day.total: 2`, both from 13:40:54Z. PnL `$-0.05`
  (up `+$1.32`), gross `$763.08` (up `+$1.12`) — mark drift on two unchanged legs. **100% market, 0%
  change.** Not bleeding, but underwater and off the growth target.
- **Last cycle's prediction FAILED, and the failure was informative.** I predicted ADR-0118 would buy
  back 1 AAPL and go flat. It did not fire — and correctly so: AAPL's aim had flipped from `+6.031064`
  LONG to `−14.915227` SHORT in one thirty-minute cycle, so the aim was no longer wrong-side. The branch
  is sound; its *input* was unstable.
- **Rule 41: when an aim flips sign between cycles, do not fix the control that reads the aim — go look
  at what the aim is made of.** Two cycles were spent on the machinery downstream of a number whose sign
  was noise.
- **What I found.** AAPL's combined forecast is the residual of two sensors fighting: trend `+11.222357`
  against reversion `−10.408273`, netting `−1.522972`. Every other name in the cross-section had its
  sources pointing the same way. The desk sized `−14.915227` shares off that residual and hedged it with
  ES — gross exposure on both legs for a view that did not exist.
- **Rule 42: a mean is not a conviction.** Two sources agreeing on +1.5 and two sources fighting to a net
  +1.5 give the same mean and, until now, the same position — with nothing like the same confidence about
  the sign. Averaging shrinks the posterior mean under conflict but nothing was widening the posterior
  variance, so sizing off the mean alone over-sizes worst exactly where the desk knows least. Whenever
  a control consumes a combination of estimates, ask what it does when they *disagree*, not just what it
  does on average.
- **Rule 43: a diversification multiplier must not be applied to a residual.** ADR-0076's DM restores
  scale removed by averaging *correlated* forecasts. Under disagreement the averaging revealed a
  contradiction, not a rescaling, and multiplying the survivor back up is leverage the data contradicts.
  Note the trap in the tempting fix: `DM = 1/√(h + (1−h)ρ)` is *increasing* as ρ falls, so feeding it a
  measured anti-correlation makes it LARGER. Breadth and confidence are different terms.
- **Rule 44: derivable is not seen.** `agreement` was computable from the `contributions` already
  published on `/api/fusion/targets` for every one of these cycles, and neither I nor the owner computed
  it — which is why the AAPL diagnosis was wrong twice. A number that decides position size gets its own
  field.
- **What I shipped (ADR-0119).** `combined ×= |Σwᵢfᵢ| / Σwᵢ|fᵢ|` — the ADR-0113/0100 efficiency ratio
  read across sources instead of over time. No dial, no threshold. One-way by the triangle inequality and
  exactly `1` when the sources share a sign, so agreeing names are byte-identical. Composes with
  ADR-0118: as sources converge on cancellation the aim goes flat and that exit branch finally becomes
  reachable.
- **Honest cost, recorded.** Trend and reversion are structurally opposed at different horizons, so a
  genuine trend fighting a genuine reversion is sized down even when one was right. Correct while LIVE
  expectancy is negative; revisit if the edge gate opens and the book is systematically under-sized.
- **Predicted next, so it can be checked rather than re-derived.** AAPL's target falls to about an eighth
  (`agreement 0.12321282549722257` on this cycle's readings) — still same-side and still above the 1 share
  held, so **no order and likely ⚠️ MIXED again**. The check is `/api/fusion/targets`: an `agreement` field
  near `1.0` on AMZN/NVDA/JNJ and far below it on AAPL, with `targetQty` an order of magnitude smaller
  than `−14.915227`. If AAPL reads `agreement: 1.0`, the sensors stopped disagreeing and this diagnosis
  has expired.

## 2026-07-28 16:00Z — the desk went flat and profitable; the gate that keeps it flat was miscounting its own evidence

- **The window.** ADR-0119 fired and composed with ADR-0118 exactly as designed: AAPL's agreement
  collapsed its target BELOW the wrong-side holding, which made the flat-aim-under-a-shut-gate exit
  reachable for the first time. `ALPHA / AAPL / BUY 1` at 15:50:37, `HEDGE / ES / SELL` at 15:50:41.
  Book flat: gross `$763.54` → `$0.00`, PnL `-$0.51` → `+$0.61`, all realized. **~100% change, 0% market**
  — a realized round trip on the two legs the change closed, seconds apart, not mark drift.
- **Rule 45: a prediction that fails in the RIGHT direction still falsifies the model, and you must say
  which.** I predicted "target falls to an eighth, stays above the holding, no order". The target fell
  FURTHER than that and tripped the exit. The mechanism was right; my estimate of its magnitude was not.
- **Rule 46: check the hypothesis against the data BEFORE building on it.** I was one edit away from
  shipping cross-sectional demeaning of cohort returns — on the thesis that the gate's standard error is
  dominated by the market factor the desk hedges away. One read-only query killed it: **cohorts are almost
  all singletons**, so there is no cross-section inside a cohort to demean. Ten minutes of SQL beat a
  well-argued but wrong ADR.
- **What that query actually found (the real defect, ADR-0120).** ADR-0077 makes one PASS over the
  cross-section the estimator's unit, but identifies it by a 60-second CLOCK GAP — on the assumption,
  written into the javadoc and the dial's own comment, that a source emits its cross-section "in one
  ~200ms burst". This desk's sensors publish per name as each mark updates, so a pass takes MINUTES:
  live `trend`@3600s, 13:34:36 NQ → 14:02:32 JPM is ONE 28-minute pass, and the gap rule cut it and the
  next into eleven cohorts. Every source and horizon: **≈2.2× more cohorts than passes.**
- **Rule 47: a heuristic that stands in for a concept will eventually measure something else.** The gap
  rule was measuring how fast the scheduler walks the universe, not how often the market was drawn. Tune
  a sensor to publish faster and the gate's apparent evidence multiplies with zero new observations. When
  a proxy gates money, periodically re-derive it from the thing it is proxying for.
- **Rule 48: check which DIRECTION a measurement error runs before calling it harmless.** This one ran
  anti-conservative — more cohorts narrows the standard error AND inflates the Student-t degrees of
  freedom ADR-0081 was installed to get right. It was the exact √(1+(n−1)ρ̄) understatement ADR-0077
  exists to prevent, readmitted through the grouping instead of the averaging.
- **The fix is dial-free.** A name appears at most once per cohort ⇒ cohort index = running maximum of
  each name's occurrence count. `cohort-window-seconds` RETIRED, not retuned. Retroactive (re-scores
  stored history, no migration). Late joiners and lone repeaters fall out with no special case.
- **Honest cost, recorded.** It makes the gate HARDER: `trend`@3600s goes 16 cohorts @ `-2.41` bps → 7 @
  `-8.40` bps. The desk looks worse because it now counts each draw once. Correct, and free to do while
  the book is flat — which is exactly when to fix a measurement, not when it is gating a live position.
- **Predicted next, so it can be checked rather than re-derived.** The gate stays SHUT and the book stays
  flat, so expect **no orders and likely ⚠️ MIXED with "risk-adj n/a (zero gross)"**. The check is
  `signals_telemetry` in the next report: `cohorts` should roughly HALVE at every source/horizon (trend
  @3600s ≈7 not 15, @900s ≈25 not 59, @225s ≈68 not 131) while `resolved` is unchanged. If `cohorts` is
  unchanged, the SQL did not take effect; if `resolved` moved too, something other than grouping changed.
- **The natural successor, deliberately NOT done here.** Now that a cohort really holds ~8 names from one
  interval, the demeaning question becomes askable: is a source's expectancy skill, or the market factor
  times its net directional tilt? The desk hedges net equity toward flat (ADR-0019), so beta is not P&L
  it keeps. Do NOT attempt this until the next report confirms cohorts actually merged.
- **Also seen, not fixed (one change per run).** `jethro.fusion.edge-gate.min-sample=30` is expressed in
  OBSERVATIONS while ADR-0108 deliberately moved the estimator's sample bound to COHORTS. Both units
  block today's readings, so it changes nothing now — but it is a unit mismatch in the gate's own
  admission test and worth a look once evidence accumulates.

## 2026-07-28T16:30Z — held under evaluation; ADR-0120 confirmed; the min-sample unit mismatch is now load-bearing

- **No change: the evidence window is open.** `reports/.pending-baseline.json` exists for `9be1633`
  (ADR-0120) at `16:17:48Z`, one heartbeat accrued against `MIN_CYCLES=6`, no ledger row. Diagnosed,
  verified, stopped. Book flat: gross `$0.00`, net `$0.00`, VaR95 `$0.00`, breaker clear, PnL
  `$0.61209817` unchanged on the run. Zero orders — nothing attributable to market OR to code.
- **Rule 49: with a flat book, PnL is frozen at realized — a `+0.00` window is not stability, it is
  absence.** No positions ⇒ no marks to drift ⇒ the number cannot move until the gate reopens. Do not
  read a flat delta on a flat book as evidence about anything.
- **Rule 50: state the prediction with a number so the next cycle can CHECK it, not re-derive it.**
  Last cycle's check fired perfectly. Predicted `trend` cohorts ≈7 / ≈25 / ≈68 at 3600s / 900s / 225s;
  live reads **7 / 27 / 71**. The decisive one: pre-ship SQL said `trend`@3600s → 7 cohorts at `-8.40`
  bps, and the gate now publishes `cohorts: 7`, `avgReturnBps: -8.40379205357143`. A cohort-weighted
  mean lands on a pre-computed value only if the grouping changed and nothing else did. ADR-0120 is
  confirmed in production, and the gate is honestly harder.
- **Rule 51: a bound you dismissed as inert can be ARMED by your own next change — re-check the
  dismissals.** I logged `min-sample=30` last cycle as a units bug that "changes nothing now". ADR-0120
  coarsened the cohorts and made it load-bearing. `momentum`@3600s: `resolved: 5`, `cohorts: 2`,
  `stdErrorBps: 0.944` against `stdReturnBps: 95.96` — two cohort means that happened to land close
  together, not precision. `tStat: 18.15` against `tHurdle: 2.0`, `pValue: 0.0175` (exactly df=1).
  **The significance test PASSES.** The sole thing keeping the desk out is `minSample: 30` vs
  `resolved: 5` — a bound in observations standing in for a bound in cohorts.
- **Why that is dangerous, precisely.** `resolved` grows ~3–4× faster than `cohorts` here (trend@225s:
  332 vs 71). So `resolved` crosses 30 while `cohorts` is still single digits — the exact regime where
  a degenerate 2–3-cohort standard error clears a t-hurdle on noise and the gate opens on nothing.
  It is ADR-0108's cohort-denominated sample bound defeated by the one test that never got converted.
- **Queued next (NOT shipped — one change per run, and the window is open):** denominate the edge
  gate's admission test in **cohorts**, the unit its standard error and Student-t degrees of freedom
  already use. Ship only once `9be1633` has a ledger row.
- **Deliberately still deferred:** cross-sectional demeaning of cohort returns. Its precondition —
  cohorts genuinely merging — is now confirmed, so it is askable at last, but the units bug sits on the
  admission test that gates exposure and outranks it.
- **Predicted next, so it can be checked rather than re-derived.** Gate stays shut, book stays flat,
  zero orders, PnL pinned at `$0.61209817` barring a fill. Expect the scorer to keep printing `still
  accumulating evidence (n/6)` for several cycles and **no ledger row until ~6 heartbeats past
  16:17:48Z**. If a row appears sooner, or PnL moves off `$0.61209817` with no order in
  `recent_orders`, something outside the loop touched the book.

## 2026-07-28T17:00Z — held (2/6); the flagged "gate is one draw from opening" reading self-destructed, which proves the units bug rather than retiring it

- **No change: the evidence window is open.** `reports/.pending-baseline.json` still exists for
  `9be1633` (ADR-0120) at `16:17:48Z`; two heartbeats accrued (`16:18:09Z`, `16:33:45Z`) against
  `MIN_CYCLES=6`; newest attribution snapshot is still `160006Z-b2a569f32.json`. Diagnosed, verified,
  stopped. Book flat: gross `$0.00`, net `$0.00`, VaR95 `$0.00`, breaker clear, PnL `$0.61209817`
  unchanged. Zero orders — nothing attributable to market OR to code.
- **Every prediction from last cycle landed.** Gate shut (`mayIncrease: false`), book flat, zero
  orders, PnL pinned to the cent, scorer at `2/6`, no ledger row, cohorts growing as observations
  resolve (`trend` 7 / 27 / 71 → **8 / 28 / 75**). Nothing outside the loop touched the book.
- **Rule 52: a t-statistic built on 2 cohorts is not a finding in either direction — do not escalate
  on one, and do not stand down when one evaporates.** Last cycle I reported `momentum`@3600s at
  `resolved: 5`, `cohorts: 2`, `tStat: 18.151`, `pValue: 0.0175` — significance PASSING, held out only
  by `minSample: 30`. One observation later the same row reads `resolved: 6`, `cohorts: 2`,
  `avgReturnBps: 6.386`, `stdErrorBps: 12.154`, `tStat: 0.487`, `pValue: 0.356`. **t fell 18.15 → 0.49
  on a single draw.** My urgency framing was wrong; the diagnosis is now *demonstrated* instead of
  argued. A two-cohort standard error is where two means happened to land, and it crosses the hurdle
  freely in both directions. It landed harmlessly this time; nothing in the gate makes that general.
- **Rule 53: check whether a latent unit bug is about to become live, and date it.** `resolved`/`cohorts`
  runs ~3.4× on every row (`trend`@3600s 27/8, `reversion`@3600s 20/6, `trend`@225s 386/75), so
  `resolved` crosses `minSample: 30` while `cohorts` is still ~9. `trend`@3600s is at `resolved: 27`
  after ~4h of LIVE session — admission on single-digit cohorts is a cycle or two away, not hypothetical.
- **Queued next (still NOT shipped — one change per run):** denominate the edge gate's admission test
  in **cohorts**, the unit its standard error and Student-t df already use (ADR-0108's bound, defeated
  by the one test never converted). Ship only once `9be1633` has a ledger row.
- **Edge mission re-checked, still honestly negative.** All four sources fail the confidence demand:
  `reversion` `p=0.243`, `momentum` `p=0.356`, `social` `p=0.468`, `trend` `p=0.829`, against a
  `0.628` bps round trip. None is close. The binding constraint is sample — 27 resolved 3600s
  observations in this LIVE epoch, and invariant 8 forbids borrowing SIM history. A fifth signal into a
  measurement that cannot separate four from zero would grow the INCONCLUSIVE wall, not escape it.
- **Predicted next, so it can be checked.** Gate shut, book flat, zero orders, PnL `$0.61209817`,
  scorer `3/6`. `trend`@3600s `resolved` crosses 30 with `cohorts` 8–10 and is admitted to the t-test;
  its `tStat` is `-1.02` so it still fails and the gate stays shut for the right reason. **If it is
  admitted and PASSES on single-digit cohorts, the queued fix is urgent, not merely correct.**

## 2026-07-28T17:30Z — held (3/6); the 2-cohort momentum row completed its walk 18.15 → 0.49 → −0.45, and `trend`@3600s is now ONE observation from the bad admission

- **No change: the evidence window is open.** `score` prints `9be1633c2 still accumulating evidence
  (3/6 cycles)`; `reports/.pending-baseline.json` still exists for `9be1633` (ADR-0120) at `16:17:48Z`;
  newest attribution snapshot is still `160006Z-b2a569f32.json`. Diagnosed, verified, stopped. Book flat:
  gross `$0.00`, net `$0.00`, breaker clear, marks live (`markAgeMillis: 768`), PnL `$0.61209817`
  unchanged. Zero orders — nothing attributable to market OR to code, in either direction.
- **Rule 52 is now proven, not argued.** The same `momentum`@3600s row has read, on three consecutive
  single observations: `cohorts: 2` `tStat: 18.151` `p=0.0175` → `cohorts: 2` `tStat: 0.487` `p=0.356` →
  `cohorts: 3` `tStat: -0.447` `p=0.651`. It travelled from far above `tHurdle: 2.0` to plainly negative
  in three draws. A 2–3-cohort standard error is not precision; it is where a couple of means landed,
  and it crosses the hurdle freely in both directions. **Never escalate on, and never stand down from, a
  t built on single-digit cohorts.**
- **Rule 54: locate a unit bug at its line before the sample reaches it.** `EdgeGate.clears`
  (`app/src/main/java/io/jethro/app/fusion/EdgeGate.java:171`) rejects on `resolved < minSample` and on
  `cohorts < 2`, then the next line computes `Significance.studentTUpperTail(t, cohorts - 1.0)`. Standard
  error and df are in **cohorts**; the admission bound alone is in **observations**. `resolved`/`cohorts`
  runs ~3.6× on every row (`trend`@3600s 29/8, `reversion`@3600s 26/7, `trend`@225s 434/79), so `resolved`
  clears `minSample: 30` with `cohorts` still single-digit.
- **Prediction miss worth recording:** I said `trend`@3600s would cross `resolved: 30` this cycle. It reads
  `resolved: 29`, `cohorts: 8` — one short. Everything else landed to the cent. The 3600s horizon resolves
  roughly one observation per cycle, so admission is genuinely next cycle, not "a cycle or two".
- **Queued next (still NOT shipped — one change per run):** denominate the edge gate's admission test in
  **cohorts**, the unit its standard error and Student-t df already use (ADR-0108's bound, defeated by the
  one test never converted). Ship only once `9be1633` has a ledger row.
- **Edge mission re-checked, still honestly negative.** All four sources fail at the demanded confidence
  against a `0.6278` bps round trip: `reversion` `p=0.327`, `social` `p=0.580`, `momentum` `p=0.651`,
  `trend` `p=0.818`. The two with the most sample (`trend` 29, `reversion` 26) are the two with negative
  net edge. Binding constraint is sample, and invariant 8 forbids borrowing SIM history. A fifth signal
  would grow the INCONCLUSIVE wall, not escape it.
- **Predicted next, so it can be checked.** Gate shut, book flat, zero orders, PnL `$0.61209817`, scorer
  `4/6`, no ledger row, regime `CHOP`/`CALM`. `trend`@3600s crosses `resolved: 30` at `cohorts` 8–9 and is
  admitted to the t-test; at `tStat -0.97` it fails and the gate holds for the right reason. **If any
  source is admitted and PASSES on single-digit cohorts before `9be1633` scores, the queued fix is what
  stands between the desk and exposure taken on two data points.**

## 2026-07-28T18:00Z — held (4/6); the predicted bad admission ARRIVED — `trend`@3600s judged on 35 observations that are 9 sweeps

- **No change: the evidence window is open.** `score` prints `9be1633c2 still accumulating evidence
  (4/6 cycles)`; `reports/.pending-baseline.json` still exists for `9be1633` (ADR-0120) at `16:17:48Z`.
  Diagnosed, verified, stopped. Book flat: gross `$0.00`, net `$0.00`, both position rows at `quantity: 0`,
  VaR `0.00` (`note: "no positions"`), breaker clear, feed live (`lastUpdateAgeMillis: 848`), PnL
  `$0.61209817` unchanged for a third consecutive run. `orders_day` `total: 7`, newest `15:50:41Z` — zero
  orders this window, so the PnL move is `+0.00` from market AND `+0.00` from code. Attribution is exact,
  not estimated, this cycle.
- **Prediction landed.** I said `trend`@3600s crosses `resolved: 30` at `cohorts` 8–9, is admitted, and
  fails near `tStat -0.97`. It reads `resolved: 35`, `cohorts: 9`, `tStat: -0.9491041554975994`,
  `pValue: 0.8148231827460571`, `passes: false`. Gate shut for the right reason.
- **Rule 54 is now observed, not predicted.** A source was admitted to the significance test on 35
  observations that are only 9 independent sweeps, then judged on 8 df. It failed ONLY because
  `avgReturnBps: -6.333250868827161` is negative — the sample size was never the thing that stopped it.
  `EdgeGate.clears` (`app/src/main/java/io/jethro/app/fusion/EdgeGate.java:171`) rejects on
  `resolved < minSample` (observations) and then hands `cohorts - 1.0` to `studentTUpperTail`. ADR-0108
  moved the evidence *budget* to cohorts and explicitly left `min-sample` in rows; that sentence is the bug.
- **Rule 55: a gate that only holds because the sign came out wrong is not holding.** Do not read "gate
  shut, no exposure" as evidence the gate is sound. Check WHICH clause rejected. If the rejecting clause is
  the measured sign rather than the sample bound, the sample bound is untested and the next positive draw
  is the one that spends money.
- **Queued next (still NOT shipped — one change per run):** denominate the admission bound in cohorts.
  Honest consequence to state when shipping: no 3600s row has `cohorts` ≥ 30 (best is `trend` at 9), so the
  3600s rung shuts entirely until more sweeps accumulate; 225s (`trend` 81, `reversion` 68) is unaffected.
- **Edge mission re-checked, still honestly negative.** Against `roundTripCostBps: 0.6278285714285714`:
  `reversion` `p=0.3096`, `social` `p=0.4992`, `momentum` `p=0.6507`, `trend` `p=0.8148`. The two
  best-sampled rows are the two with the worst net edge. `strategy_diag` reports `signals: 0`,
  `executed: 0`, and 10 of 18 measured names carry `no positive OOS edge`.
- **Predicted next, so it can be checked.** Gate shut, book flat, zero orders, PnL `$0.61209817`, scorer
  `5/6`, still no ledger row, regime `CHOP`/`CALM`. `trend`@3600s stays admitted with `cohorts` 9–10 and
  keeps failing on its negative mean; `reversion`@3600s (`resolved` 27, `cohorts` 7) is the row to watch —
  it is the only positive-mean source with real sample, and it reaches `resolved: 30` on roughly
  single-digit cohorts. **If `reversion` is admitted and PASSES on ~8 cohorts, the queued fix stops being
  correctness housekeeping and becomes the thing standing between the desk and sized exposure.**

## 2026-07-28T18:30Z — held (5/6); the queued fix failed its own test — **retracted**, and Rule 55 corrected

- **No change: the evidence window is open.** `score` prints `9be1633c2 still accumulating evidence
  (5/6 cycles)`; `reports/.pending-baseline.json` still exists for `9be1633` (ADR-0120) at `16:17:48Z`.
  Book flat: gross `$0.00`, net `$0.00`, HEDGE `+0.95512117` / ALPHA `-0.34302300`, breaker clear, feed live
  (`lastUpdateAgeMillis: 62`), PnL `$0.61209817` unchanged for a fourth consecutive run. Newest order is
  `159.9` minutes old — zero orders this window, so the move is `+0.00` from market AND `+0.00` from code.
- **Prediction landed exactly.** I named `reversion`@3600s as the row to watch and said it would cross
  `resolved: 30` on single-digit cohorts with a positive mean. It reads `resolved: 34`, `cohorts: 8`,
  `avgReturnBps: 7.374500649147727`, `tStat: 0.6763382074979686`, `pValue: 0.26026966244441807`,
  `passes: false` — admitted on observations, positive-signed, and stopped by the **significance** clause.
- **Rule 56 (supersedes the alarm in Rule 55): before "fixing" a sample bound, compute what the reference
  distribution already demands at that df.** Re-implementing the gate's Student-t tail reproduced its
  published p-values (`0.26027` vs `0.26026966`; `0.793545` vs `0.79354495`), so the required `tStat` at
  α = `0.00758337731605974` (`tHurdle: 2.0`, `hypotheses: 3`) is: df 1 → `41.97`, 2 → `8.03`, 3 → `5.03`,
  7 → `3.195`, 32 → `2.566`, 85 → `2.479`. The t-distribution already punishes small cohorts savagely — it
  IS the textbook correction for a standard error estimated from few draws. `reversion`@3600s would need
  ~`33` bps against the `7.37` it measures. The `resolved < minSample` unit mismatch is **cosmetic, not a
  safety hole**.
- **Queued change RETRACTED — do not ship it, and do not re-propose it.** A cohort-denominated
  `min-sample: 30` would shut **9 of 12** source×horizon rows (all four at 3600s, best `cohorts: 9`; three
  of four at 900s, only `trend` at `33` surviving; two of four at 225s) to buy protection the reference
  distribution already provides. Rule 55 was right that "which clause rejected" matters; it was wrong to
  infer the bound was dangerous. **A two-cycle-old alarm still has to pass a quantitative check before it
  becomes a commit.**
- **Edge mission — one real structure, honestly discounted.** `reversion` is the only source positive at
  every horizon: `225s +0.533252798747244`, `900s +1.2789941648057297`, `3600s +7.374500649147727`, rising
  with horizon while `roundTripCostBps: 0.6278285714285714` is fixed — net of cost negative at 225s,
  positive at 900s and 3600s. That is cost amortisation over a longer hold. **But the three rows measure the
  same signal over overlapping windows — not three independent confirmations** — and none is significant.
  Every other source is negative-mean at its best-sampled horizon.
- **Predicted next, so it can be checked.** `9be1633` reaches 6/6 and **scores** — expect the first ledger
  row in six cycles, most likely `⚠️ INCONCLUSIVE` on a flat book with zero orders. Gate stays shut, PnL
  stays `$0.61209817` absent orders. `reversion`@3600s stays admitted, `cohorts` 8–10, still failing on
  significance. **The lever I intend to take up once scored is the horizon ladder** — whether the desk
  should prefer the hold at which `reversion`'s expectancy clears its cost — not another admission-bound edit.

## 2026-07-28T19:00Z — the pending change SCORED, so I shipped a new SIGNAL instead of another mechanic: cross-sectional residual reversion (ADR-0121)

- **Window attribution is exact, not estimated.** `9be1633c2` (ADR-0120) scored `⚠️ INCONCLUSIVE`
  (`+0.000000 risk-adj/cycle over 7 cycles, t=+0.00`), `reports/.pending-baseline.json` is gone, so the
  evidence window is closed and a new change is allowed. Book flat: gross `$0.00`, net `$0.00`, VaR
  `0.00` (`note: "no positions"`), breaker clear, feed live (`lastUpdateAgeMillis: 10`), PnL
  `$0.61209817` for a fifth consecutive run. `orders_day.total: 7`, newest hours old — **zero orders**,
  so the move is `+0.00` from market AND `+0.00` from code.
- **Rule 57: the horizon-ladder lever is DEAD — do not take it up.** I said last cycle I would pursue
  whether the desk should prefer the rung where `reversion`'s expectancy clears its cost. Before
  building it I computed all three rungs' t-stats from the published cohort dispersions
  (`stdCohortMeanBps / √cohorts`) rather than assuming: `reversion`@900s ≈ `0.27` on 29 cohorts,
  @225s ≈ `0.25` on 77, against the gate's own `0.6344345168075892` at 3600s. **No rung is close**, and
  the best t anywhere on the desk is `0.6564771217276463` (`social`@3600s, 5 cohorts). Switching the
  gate's measurement rung would not open it. Second cycle running that a queued lever failed a
  quantitative pre-check before becoming a commit — that check is now the habit, not the exception.
- **Rule 58: when every measurement is null, the only honest lever is a NEW MEASUREMENT.** Five
  consecutive cycles of `INCONCLUSIVE` came from tuning the combiner, the gate and the sensor mechanics
  while the underlying expectancies stayed indistinguishable from zero. Re-weighting sources with no
  edge cannot create edge. Spend the change on a predictor, not on the plumbing that ranks predictors.
- **The structure I acted on, and why it is not a fishing trip.** `reversion` is the ONLY source
  positive-signed at every rung (`0.6500729504188325` bps @225s/77 cohorts, `1.2532288851764135`
  @900s/29, `6.940456315814394` @3600s/8); `trend` is negative on both its best-sampled rungs
  (`-0.21794567180606306` @225s/87, `-8.299129091035356` @3600s/10). The reversal literature's reading
  of exactly that shape is that the documented effect is **idiosyncratic** (Lehmann 1990, Lo–MacKinlay
  1990, Khandani–Lo 2007): fading a name that is down because the whole cross-section is down is a bet
  on the market factor — ~zero expectancy over an hour plus full turnover cost — and ADR-0019 hedges
  that factor toward flat anyway, so it is exposure the desk does not keep, measured as if it were alpha.
- **Shipped:** `xsreversion`, a fifth forecast source that fades `(r − peer median)/(1.4826·MAD)` where
  `r = ln(P_last/P_first)/√span`, peers = asset class from the instrument master, admission = the name
  printed in **both halves** of the window (dial-free). One sweep = one ADR-0120 cohort. It cannot add
  exposure while the gate is reduce-only; the cost of being wrong is measurement time, not money.
- **Predicted next, so it can be checked.** Scorer at `1/6`; expect no ledger row for six cycles and PnL
  to stay `$0.61209817` absent orders. `xsreversion` should appear in `signals_telemetry` within a cycle
  or two with `cohorts` in the low single digits at 225s and nothing yet at 3600s; the EQUITY group
  should be the only one clearing `min-peers: 4` on this universe (18 measured / 8 tradable), so FX and
  futures rows may stay absent — **if `xsreversion` never appears at all, the peer groups are too thin
  and the diagnosis is `min-peers`, not the signal.** Gate stays shut. **If it measures null once it has
  real cohorts, that is evidence this universe has no short-horizon reversal to capture, and the honest
  next move is a different feed — not a sixth source.**
- **Queued follow-up (NOT this change):** the gate's Bonferroni divides α by RUNGS only
  (`hypotheses: 3`), not by sources. A fifth source makes the un-corrected source multiplicity worse, so
  a passing `xsreversion` reading deserves more scepticism than the gate expresses.

## 2026-07-28T19:30Z — held (1/6); ADR-0121 landed on every predicted clause, and single-source names turn out to be max-conviction with no stop

- **No change — evidence window open.** `score`: `68ff73eb2 still accumulating evidence (1/6 cycles)`;
  `reports/.pending-baseline.json` present for `68ff73eb2`, stamped `2026-07-28T19:17:20Z`. Book flat
  (gross `$0.00`, net `$0.00`, VaR `note: "no positions"`), breaker clear, feed live
  (`lastUpdateAgeMillis: 10`). PnL `$0.61209817` for a sixth consecutive run. `orders_day.total: 7`,
  newest ~3.5 h old — **zero orders**, so the move is `+0.00` from market AND `+0.00` from code.
- **Last cycle's prediction landed on every clause.** `xsreversion` is live and publishing:
  `resolved: 17`, `cohorts: 2`, `open: 9` @225s; `cohorts: 0`, `open: 10` @900s and @3600s. All eight of
  its `fusion_targets` contributions are equities; `NQ` (the only non-equity routed name) carries none.
  So it is correctly EQUITY-scoped and the **`min-peers` failure branch is closed** — from here the
  diagnosis is the signal itself, not the plumbing. Weight sits mid-pack at `1.0113609266093764`.
- **Rule 59: do not read a 2-cohort row's sign, not even your own new source's.** `xsreversion`@225s
  reads `avgReturnBps -2.857963236111111`, `hitRate 0.25` — on one degree of freedom, where the gate
  demands `t ≈ 41.97`. This desk already watched a 2-cohort row walk from far above the hurdle to
  negative. The temptation to grade your own change early is exactly what the 6-cycle window exists to
  resist; the falsification standard is "null once it has REAL cohorts", and that has not happened yet.
- **Rule 60: coverage is not conviction — the combiner rewards breadth but never discounts thinness.**
  `ForecastCombiner.combine` (`ForecastCombiner.java:104`) forms `average × dm × agreement`; at one
  source the average IS that source's raw reading (no cross-source shrinkage), `dm = 1.0`,
  `agreement = 1.0`. So a name held up by ONE source with two cohorts of measurement is scaled exactly
  like GOOG, where four sources agree. `xsreversion` is currently the sole source on **TSLA** and
  **NFLX**, and TSLA carries the largest `|combinedForecast|` (`-19.482833721224054`, near the clamp)
  and the largest notional target in the book.
- **The uncomfortable overlap, and why it is the next lever.** Those two names are single-source
  *because* their own-history sensors are cold (`trend` seeded `66 of 193` for TSLA, `34 of 193` for
  NFLX) — and the same WARN log says `risk-cut σ sensor still cold` for both: "this name cannot be
  stopped out until its mark history has accumulated". **The thinnest-conviction, largest-target names
  are the ones with no working stop.** Harmless today only because `mayIncrease: false` pins every
  `deltaQty` to 0. This is a risk-control asymmetry, not an edge question — legitimate to act on under
  the edge-first priority — but it gets a quantitative pre-check before it becomes a commit, not before.
- **Predicted next, so it can be checked.** Scorer at `2/6`; no ledger row for five more cycles, PnL flat
  at `$0.61209817` absent orders. `xsreversion`@900s should resolve its first cohorts within a cycle or
  two, 3600s later. If it measures null once it has real cohorts, the honest next move is a different
  feed — not a sixth source.

## 2026-07-29T13:30Z — the dormancy was never a signal problem: the owner's directive never reached the JVM (ADR-0123)

- **Scorer cleared** (`68ff73eb2` → ⚠️ INCONCLUSIVE, 38 cycles, `$0.61209817` → `$0.61209817`, gross
  `0` → `0`), no `.pending-baseline.json`, so a change was permitted. Book `DORMANT`: gross `$0.00` =
  0.0% of the $1,500,000 firm cap, VaR `no positions`, breaker clear. **Zero orders** in the window
  (7 LIVE orders ever, newest `2026-07-28 15:50:41Z`) ⇒ the move is `+$0.00` from market AND `+$0.00`
  from code. That INCONCLUSIVE verdict is about a book that placed no trades — it is not evidence
  about ADR-0121.
- **Rule 61: before diagnosing a signal, prove the JVM is running the code you are reading.** The repo
  said `jethro.fusion.edge-gate.enabled=false` (ADR-0122, owner-directed, committed 22:24Z 2026-07-28).
  The live app said `edgeGate.mayIncrease:false` **with a populated `sources` list** — which only
  happens when `gateSupplier != null`, i.e. the gate ENABLED. `ps` gave the process start as
  `Tue Jul 28 15:35:00 2026`, seven hours before the commit. Ten minutes into the open session every
  `deltaQty` was exactly `0` against non-zero targets. Two cycles of reading fusion telemetry would
  have been wasted chasing a combiner that was never the constraint.
- **The trigger, and why it was permanent not merely slow.** `ops/improve-loop.sh`'s ADR-0115
  market-closed branch fetches, fast-forwards to origin — so the change lands in the tree — then
  `exit 0`s before any deploy, having taken its `BEFORE` sha AFTER the merge. By the next open cycle
  the commit is already an ancestor of HEAD, so `git diff BEFORE AFTER` can never see it and
  `CODE_CHANGED` is empty forever. **A change pushed after the close was undeployable in principle.**
  Same bug §2 of the open path fixed for itself on 2026-07-28, left standing in the closed path.
- **Shipped:** ADR-0123 — closed branch takes `BEFORE` before the fast-forward and calls the same
  deploy; the deploy block is now one shared `deploy_if_code_changed` used by both paths. Tests green.
- **Rule 62: name the conflation BEFORE the window opens, not after the verdict.** This commit triggers
  a deploy that also brings ADR-0122 live for the first time. The next scored row therefore contains
  TWO deployments and exploration mode will dominate it. **Credit/blame belongs to ADR-0122, not to
  this plumbing fix** — do not read that row as evidence about either alone.
- **Predicted next, so it can be checked.** After the restart the running app should report
  `edgeGate` absent-or-inactive on `/api/fusion/targets` (`gateSupplier=null`), and `deltaQty` should
  go non-zero on the names clearing `min-forecast-to-route=5.0` (MSFT/AAPL/NVDA at the 13:30Z reading).
  Gross should leave `$0.00` within a cycle or two. **If it does NOT** — if deltas stay 0 with the gate
  demonstrably off — the next suspects are, in order: whole-share truncation in
  `TargetPlanner.tradableQuantity` against a per-cycle delta of `a·gap` at `a = 1 − e^(−30/3600) =
  0.0083`, and the ADR-0094 buffer measured against an aim that `aims.clear()` resets to flat every
  time the book goes stale overnight. Both are arithmetic, both are checkable before they are commits.
- **Deferred row 76 has FIRED** (`docs/deferred-register.md`): a ledger verdict was found to describe
  undeployed code. ADR-0123 makes stranding rarer; teaching `scripts/score-change.py` to refuse to
  score a commit it cannot prove was deployed is what makes the verdicts honest, and is now the
  higher-priority half.

## 2026-07-29T14:00Z — the desk woke up; the loss sits in the one source measuring negative edge (no change — pending at 1/6)

- **No change permitted.** `scripts/score-change.py score` → `d9f8969cc still accumulating evidence
  (1/6 cycles)`, `.pending-baseline.json` present. Analysis + memory only, per contract.
- **Last cycle's prediction landed in full.** `ps` → JVM start `13:48:18Z` (fresh, minutes after
  `fea8dac`); `/api/fusion/targets` now returns **`edgeGate: null`** with `routing:true`, 11 instruments,
  non-zero deltas. First LIVE order in 22 hours at `13:54:00Z`; 13 fills today vs 7 in the book's entire
  prior history. Gross `$0.00` → **`$10,772.39`** (0.7% of the $1.5M cap), PnL `$0.61209817` → **`-$23.21`**.
  **The dormancy was a delivery bug (ADR-0123), not a signal problem** — the diagnosis is now verified,
  not merely argued. Per Rule 62 this window is ADR-0122's behaviour, not the plumbing fix's.
- **Not danger.** 0.7% of the exposure cap, `-$23` against a `maxFirmDrawdown` of `$50,000`, breaker
  clear, `riskCuts: []`. A book coming off zero into 0.7% of cap is the goal, not a reason to de-risk.
- **Rule 63: when a fade is the *sole* source on a name, a falling price makes it buy more.**
  **JPM +14sh, gross `$4,893`, PnL `-$18.05` — ~78% of the whole firm loss in one name**, drip-accumulated
  1 share/30s from 13:54. Its target is `sources: 1` — `xsreversion` alone, `combinedForecast 15.02`,
  `trend` not contributing at all. As JPM falls below its peer median the residual grows, so the forecast
  grows, so `targetQty` grows (89.68 vs `currentQty` 12). Self-reinforcing accumulator, no trend filter,
  no per-name stop (`riskCutStoppedNames: 0`). NQ (`+$2,301` gross, `-$5.28`) is the same shape. The two
  *shorts* — AAPL `-$0.16`, JNJ `-$0.47` — are at entry cost, i.e. fine. The damage is specifically in
  the names being accumulated into.
- **Rule 64: 12 minutes of live PnL is not evidence about a signal — the multi-day telemetry is.** The
  book was flat when the window opened, so 100% of the move is on code-opened positions with zero
  untouched inventory; that makes it cleanly attributable and still statistically worthless. The durable
  read, t computed by script from the gate's own cohort dispersions (`avg/(sdCohort/√cohorts)`):
  **`reversion` +0.39 / +1.76 / +1.39** at 225/900/3600s — the only source positive at every horizon and
  the only one clearing the 1.5 hurdle anywhere (900s: +3.17bps, 291 resolved, 88 cohorts). `trend`
  −0.09/−0.02/−0.88, `momentum` −0.04/+0.31/−1.45, `social` −0.43/−0.49/+0.21, and **`xsreversion`
  −0.25/−0.74/−8.32 — negative at every horizon**, yet weighted 0.52 and sole driver of JPM/NVDA/NFLX.
  The live loss is a *consistent illustration* of what the telemetry already said, not the proof.
- **This answers the standing "work on EDGE" question:** yes, one signal here predicts returns —
  `reversion`. That is no longer an open question, and re-weighting is no longer combiner-tuning-without-
  edge; there is now a measured edge to shape.
- **Predicted next, so it can be checked:** demote/gate `xsreversion` on its own measured expectancy and
  let `reversion` carry the size. **Check first** whether JPM's loss persisted or mean-reverted across the
  full window — that distinguishes "the fade was early" from "the fade was wrong," and only the second
  justifies the demotion.

## 2026-07-29T14:30Z — the agreement scaler is degenerate at n=1; the loss rotated from JPM to NQ (no change — pending at 2/6)

- **No change permitted.** `scripts/score-change.py score` → `d9f8969cc still accumulating evidence
  (2/6 cycles)`, `.pending-baseline.json` present. Analysis + memory only, per contract.
- **Last cycle's question is answered, and the answer is neither option I offered.** I asked whether JPM's
  loss *persisted* or *mean-reverted*. It did neither: **the model reversed its own sign in 18 minutes and
  realized the loss.** +14 sh long → `SELL 14 FILLED` 14:11:58 → SELL 2, SELL 1 → now **−3 sh, realized
  −$11.53**, unrealized only −$1.57. `xsreversion` was *wrong*, not early. It flipped because `reversion`
  arrived as a source on JPM (−11.54 @ w2.10 vs `xsreversion` −3.79) and overrode it — the system
  self-corrected, but only after paying the loss plus two spread crossings.
- **Rule 65: `agreement = |Σwᵢfᵢ| / Σwᵢ|fᵢ|` is identically 1.0 at one source — so ADR-0119's scaler gives
  FULL conviction to the names with NO corroboration.** Confirmed in `ForecastCombiner.java:39,124-128`.
  Live proof, and it is the next JPM queued larger: **GOOGL `sources:1`, agreement 1.0, target −147.25 sh**
  (already ratcheting: SELL 1→2→…→8, ROUTED 14:29:34) and **TSLA `sources:1`, agreement 1.0, target
  +329.96 sh** — both driven solely by `xsreversion`, the source measuring negative edge at every horizon.
  n=1 is absence of evidence and must score near-minimum conviction. **This is the next change**, with an
  ADR — it is a degenerate formula, not a parameter tweak, so it is not combiner-tuning-without-edge.
- **Rule 66: a data gap silently converts an exit into an add.** The loss rotated off JPM onto **NQ:
  +0.007661, gross $4,239, −$24.55 = 81% of the firm loss.** Between 14:10:28–14:13:29 the desk tried to
  exit NQ **eight times** (`SELL 0.004134`, the whole position) and every one returned `REJECTED — no
  market data for NQ`. When the mark returned the target had flipped to BUY and it **accumulated**
  (`BUY 0.003527 FILLED` 14:24:02). The guardrail was right to refuse; what is missing is above the floor
  — a refused *exit* intent must be retained and retried, not re-derived from a fresh forecast. ES marks
  at `ageMillis` ≈ 1.05M, so futures feed gaps are routine, not a one-off.
- **Telemetry (t by script, `avg/(sdCohort/√cohorts)`) at 225/900/3600s:** `reversion` **+0.57/+1.31/+1.36**
  (939/294/86 resolved) — still the only source positive at every horizon. `xsreversion` **−0.09/−1.43/−1.32**
  — negative at every horizon, yet sole driver of the book's two biggest targets. `trend` −0.33/+0.34/−1.10,
  `momentum` −0.04/+0.31/−1.45, `social` −0.89/−1.55/+0.21.
- **Watch, don't act yet:** AAPL is the only winner (short −4 sh, **+$9.25**) and its target is now
  **+87.54** — the combiner is about to flip a winner, the mirror of the JPM whipsaw. If corroboration-aware
  agreement does not settle the sign churn, holding-period discipline (targets re-planned every 30s against
  signals measured over 225–3600s) is the cycle after.

## 2026-07-29T15:00Z — the loss is 100% in round-trips; the open book is up (no change — pending at 3/6)

- **No change permitted.** `scripts/score-change.py score` → `d9f8969cc still accumulating evidence
  (3/6 cycles)`, `.pending-baseline.json` present. Analysis + memory only, per contract.
- **Rule 67 — split the book by open vs closed before blaming anything else. Every dollar of the loss is
  in round-trips.** Script over `/api/risk` `.positions`: names now FLAT (NQ, JPM, GOOGL, ES) total
  **−$68.12**; names still OPEN (MSFT, JNJ, AAPL, NVDA, AMZN) total **+$7.21**; firm **−$60.91** — they
  reconcile exactly. The desk's *views* make money; its *churn* loses it. This also settles change-vs-market
  with no guessing: a realized round-trip loss is 100% trading logic, there is no untouched inventory to
  blame the market for. **Cost rate:** LIVE turnover **$64,453 on a $18,238 gross book = 3.53× the book**
  in ~70 min; firm realized = **−9.80 bps of turnover** vs fees **0.88 bps** — the loss is ~11× commission,
  so it is the *direction* of the round trips, not execution cost. Per name: NQ −42.19bps, GOOGL −14.86bps,
  JPM −10.46bps; while MSFT/NVDA/AMZN — built but never round-tripped — are each exactly −1.00bps, i.e.
  pure fee. That contrast is the proof.
- **Rule 68 — a newly-promoted name is structurally n=1, structurally full-conviction, and structurally
  un-stoppable. All three at once.** GOOGL took the book's largest turnover ($13,991) while the log shows
  `trend` cold (2 of 193), `reversion` cold (2 of 241) **and** `risk-cut σ` cold (2 of 121 — *"this name
  cannot be stopped out until its mark history has accumulated"*). Only `xsreversion` was warm, because a
  cross-sectional fade needs one snapshot rather than history — and it is the source measuring negative
  expectancy at every horizon. Add rule 65 (`agreement ≡ 1.0` at n=1) and the least-informed name in the
  book gets maximum size with the stop switched off. **TSLA (10 of 241) and ORCL (0–3) are queued in the
  same state.** The fix must couple corroboration *and* a warm risk sensor: no stop ⇒ no size.
- **Last cycle's prediction landed exactly.** I flagged that the combiner was about to flip the book's only
  winner: AAPL short −4 sh at **+$9.25**, target +87.54. It flipped to **long +10** — realized +$8.38
  banked, unrealized −$9.19, total **−$0.81**. A winner converted to a scratch. Third instance of the same
  sign-reversal whipsaw after JPM and GOOGL.
- **NQ's rule-66 failure completed and it is the worst single line.** Eight `REJECTED — no market data`
  exits at 14:10, then a flip to BUY and an *add*, then `SELL 0.007661 FILLED` at 14:51:50 — the exit the
  desk wanted was executed 40 minutes and one accumulation later, for **−$35.82**.
- **Telemetry (t by script, LIVE) at 225/900/3600s:** `reversion` **+0.38/+1.26/+0.73** (984/305/88
  resolved) — still the only source positive at every horizon. `xsreversion` **−0.35/−0.41/−1.36** —
  negative at every horizon. `trend` −0.14/+0.08/−0.52, `momentum` −0.04/+0.31/−1.45, `social` −0.50/−1.75/−0.79.
- **Falsifiable check before the next change ships:** META is `sources:1`, agreement 1.0, target +62.79,
  with **13 consecutive posted-and-cancelled BUYs** ratcheting 1→8 and not one fill. If it round-trips at a
  double-digit-bps loss, that is the fourth instance and rule 68 is confirmed. If it fills and holds
  profitably, the mechanism is wrong and holding-period discipline against the 30s re-plan is the target
  instead.

## 2026-07-29T15:30Z — the loss is a FEED split: 82% of it is in the 15-min-delayed cohort (no change — pending at 4/6)

- **No change permitted.** `scripts/score-change.py score` → `d9f8969cc still accumulating evidence
  (4/6 cycles)`, `.pending-baseline.json` present. Analysis + memory only, per contract.
- **Rule 69 — split the book by MARKET-DATA FEED before blaming the signal. It is the sharpest cut yet.**
  Script over `/api/risk` `.positions`, cohorts from `instrument_symbology`: names with real-time `alpaca`
  symbology (AAPL, AMZN, GOOG, JNJ, JPM, MSFT, NVDA) realized **+$11.13**, total **−$12.60**, **+1.81 bps**
  of $61,481 turnover. Names priced only by the delayed `yahoo` poll (GOOGL, NQ, TSLA, ES) realized
  **−$56.67**, total **−$56.44**, **−23.04 bps** of $24,592 turnover. They reconcile exactly to firm
  **−$69.04**. **29% of turnover, 82% of the loss** — and it is almost all *realized*, so it is trading
  logic, not market drift on untouched inventory.
- **Rule 70 — the limit price IS the last mark, with no age check, so a delayed feed posts orders at a
  price the market left 15 minutes ago.** `FusionExecutor.passiveLimitPrice` (`FusionExecutor.java:180-190`)
  = `LastPriceCache` mid (`LastPriceCache.java:21-37`, a last-value map, no TTL). Decisive statistic —
  distinct limit prices vs reposts over 3h: **GOOGL 19 orders → 1 price, ORCL 16 → 1, NFLX 5 → 1, META 29
  over 52.9 min → 2**, fill rates 5/0/0/0%; versus **NVDA 11 → 11, JPM 18 → 18, MSFT 22 → 21, AAPL 13 → 11**,
  fill rates 100/83/77/77%. Same market, same 30s cadence — that is a feed property, not a market property.
  `distinct_px == reposts` ⇒ 56–100% fill; frozen price ⇒ 0–9%. A stale limit only trades when the market
  comes **back** to it — i.e. when the move went against the view — so **fills are adversely selected by
  construction**. This is why `MarkPublisher` republishing at 1 Hz makes `/api/marks` `ageMillis` read
  fresh (~700ms) for META while its price steps once per ~15 min: staleness is invisible in the endpoint.
- **Rule 71 — this supersedes rule 68's explanation.** The newly-promoted names are not primarily losing
  because they are `sources:1` and un-stoppable; they are losing because
  `UniversePromotionService.java:67-73` **deliberately writes yahoo-only symbology**, so every
  discovery-promoted name (GOOGL, META, ORCL, NFLX, TSLA) lands in the delayed cohort **by design**.
  Corroboration was the symptom; feed latency is the cause. Do not spend the next change on agreement.
- **Rule 72 — the edge gate is being fed frozen non-observations, so "no source has edge" is partly a
  data artifact.** `signal_observations` LIVE, 6h: **29.7%** of resolved rows on delayed names have
  `exit_mark = entry_mark` **exactly** (70 of 236) vs **3.7%** on real-time names (40 of 1080). A third of
  the evidence on those names is a frozen price booked as a flat return — it drags expectancy toward zero
  and inflates the gate's degrees of freedom. You cannot measure a 225–3600s expectancy on a price that
  steps every 15 min. TCA is blind too: `arrival_price` is written from the same stale mark, so measured
  slippage on those names is ~0 **by construction** while the true adverse selection goes unmeasured.
- **Last cycle's falsifiable check on META resolved — and it FALSIFIED the predicted mechanism.** META did
  not round-trip at a double-digit-bps loss; it **never filled at all** (29 orders, 0 fills, 100% cancelled,
  all at 588.51 while the mark is now 591.18 — a bid 45 bps below the market). ORCL likewise: 16 offers at
  120.325 against a 117.69 mark, 224 bps above the market, 0 fills. Both views were *right* and captured
  nothing. That non-fill is the evidence that produced rules 69–72.
- **Next change (queued, needs an ADR — architecturally significant):** gate tradability on **price age** —
  a name whose mark is older than the horizon the desk plans on goes reduce-only, and/or a discovery-promoted
  name must earn real-time symbology before it becomes tradable; and discard zero-move resolutions on stale
  feeds from the edge evidence. **Falsifiable check:** if the delayed cohort is made reduce-only, firm
  realized bps should move toward the real-time cohort's +1.81 bps. If it does not, the feed thesis is wrong
  and the target reverts to holding-period discipline against the 30s re-plan.

## 2026-07-29 16:00Z — the execution scheme is one-sided by design, and fees are NOT the churn cost

- **Rule 73 — reconstruct round-trips FIFO from `fills`⋈`orders` before blaming "churn". It reframes the
  loss.** Over the LIVE epoch: **61 of 65** round-trips are **LIMIT-entry → MARKET-exit**, aggregating
  **−11.6 bps** on **$39,363** of round-tripped notional (**−$45.53** pre-fee), median holding period
  **27.4 min**. That is `FusionExecutor.route` doing exactly what ADR-0084 says — *"an entry POSTS, an exit
  CROSSES"*: risk-increasing rests as a DAY LIMIT at the mid, risk-reducing goes MARKET. So the desk pays
  the crossing cost on **100%** of exits and captures spread on **0%** of entries. Changing it needs a
  superseding ADR, not a dial.
- **Rule 74 — this KILLS the "round-trip *cost* is the loss" framing (which had been must-fix #1).**
  Explicit fees are **$10.83** of the **$51.61** realized loss — **21%**, or **0.89 bps** on **$122,221** of
  turnover. The other **79%** is adverse price movement between entry and exit. A limit resting at the mid
  only fills when the market comes *to* it, i.e. when the move went against the view: the fills are
  adversely selected by construction, and that dwarfs commission. Never diagnose "we trade too much and pay
  too much cost" from turnover alone — split fee from price movement first.
- **Rule 75 — the feed split survived re-test: the delayed cohort is still >100% of realized loss.** DELAYED
  (ES, GOOGL, NQ, TSLA) is **27.6%** of turnover but **67.5%** of total loss (was 29% / 82%) — **2.4×** its
  share. Its realized loss is **three round-trips**: NQ **−84.3 bps** (2 RTs) + GOOGL **−27.7 bps** (1 RT) =
  **−$55.04**, versus the firm's entire realized loss of **−$51.61**. The rest of the book is net positive
  on realized. Rule 72's TCA-blindness corollary re-confirmed hard: NQ's measured `avgSlippageBps` is
  **0.033** against a realized −84.3 bps, because `arrival_price` is stamped from the same stale mark.
- **Rule 76 — the agreement scaler is INVERTED, not just degenerate (rule from must-fix #3, sharpened).**
  `/api/fusion/targets`: single-source names carry `agreement` **1.000** and `|combinedForecast|` **20.00**
  (the cap) / **18.95** / **18.55**; three-source names carry **0.993 → 10.33**, **0.981 → 6.62**,
  **0.832 → 5.80**. Uncorroborated views size **2–3× LARGER** than corroborated ones, because `agreement`
  returns 1.0 when there is nothing to disagree with. And every single-source name is driven by
  `xsreversion` alone — which are the discovery-promoted, hence yahoo-delayed, names (rule 71). **The two
  defects compound: maximum conviction is handed to exactly the names priced on a 15-minute delay.**
  Downstream symptom: TSLA target **192** shares vs current **1**, repeatedly cancelled/replanned and
  rejected with `no market data for TSLA`.
- **Rule 77 — resist the holding-period story; n is too small.** Bucketing the 65 round-trips by holding
  period is NOT monotone — 20–30 min is **+7.7 bps** while 30–60 min is **−32.1 bps**. That is noise, not a
  regime. Log it; do not spend a change on it until the round-trip count is materially above 65.
- **Attribution this window (honest split):** no code change has been made for three cycles — the pending
  one is at 5/6 — so **none** of the −$6.42 run-over-run move is attributable to a new change of mine. It is
  the standing exploration-mode configuration trading against the market. Do not credit or blame a change
  for it.

## 2026-07-29 16:30Z — the feed thesis was wrong about the mechanism and right about the names

- **Rule 78 — re-test a thesis against the metric that IS the mechanism, not the one that correlates with
  it.** Two cycles were spent building the case that GOOGL/NQ/TSLA/ES lose money because they are priced
  off a 15-minute Yahoo poll. The case rested on order-book *behaviour* (reposts collapsing onto one
  price) and a startup log line. The direct measurement — `/api/marks` `ageMillis` and `/api/risk`
  `markAgeMillis` — was never read. Read live this cycle it says GOOGL **1.0s**, NQ **0.9s**, TSLA
  **0.7s**, META **0.5s**, all `source=alpaca`, and `markAgeMillis` **50** on every open position. Only
  GBPUSD and ES are stale (**1383s**) and both hold **zero** exposure. The thesis is falsified. **Before
  building a fix, read the metric the mechanism is literally made of.**
- **Rule 79 — the cohort was right, the reason was wrong: those names are SINGLE-SOURCE, not delayed.**
  META and TSLA are called by `xsreversion` alone. `UniversePromotionService` promotes names with no
  history, so the discovery-promoted set is simultaneously the yahoo-symbology set *and* the
  one-sensor-has-warmed set — two explanations perfectly confounded in the same names. The loss share is
  still real (**26.7%** of turnover, **58%** of gross losses); the cause is breadth, not latency.
- **Rule 80 — a ratio that is 1 "by construction" is not a safety property, it is a blind spot.**
  ADR-0119 sized conviction by `|Σwᵢfᵢ|/Σwᵢ|fᵢ|` and recorded "every single-source name is byte-identical"
  as *safe*. Live it inverted the book: META `sources=1, agreement=1.000, |forecast| 15.41` — the largest
  conviction on the desk, **1.8×** the best-corroborated name (NVDA, 3 sources, 0.812 → 8.44) — against a
  DM of only 1.000 vs 1.155 pushing the other way. Whenever a scalar has a degenerate case, check which
  END of its range the degenerate case lands on. This one landed on maximum size.
- **Rule 81 — "unestimable" is not "zero".** The repair (ADR-0124) sizes on the sources' dispersion
  `s² = Σŵᵢ(fᵢ−μ̂)²/(1 − Σŵᵢ²)`; the denominator is the residual degrees of freedom and is **zero** at one
  effective source. The old code implicitly read that as zero dispersion (full confidence); the honest
  reading is no measurement, hence scalar 0. Same trap to watch for anywhere a variance, a standard error
  or a correlation is computed from a single observation.
- **Rule 82 — a mechanical ❌ BAD can be the right thing to keep.** `d9f8969cc` scored BAD on
  "gross grew 0 → $26,879 with no return" — but that commit IS the wake-up from dormant, and it carries
  the deploy fix without which no later change reaches the JVM. Its auto-revert conflicted and did not
  land; left un-reverted deliberately. **Read what a BAD change actually did before honouring the revert
  — and note that the scorer records `"revert": true` even when the revert failed.**
- **Attribution this window (honest split):** no change of mine has been live for three cycles, so
  **none** of the +$13.76 PnL or the −$11,145 gross is attributable to one. It is the standing
  exploration configuration trading against the market.

## 2026-07-29 17:00Z — no-change (ADR-0124 at 1/6): the fix landed exactly as specified

- **Rule 83 — a VERIFY-BY written as a *sign or ordering* test, not a PnL test, is what let this be graded
  in one cycle.** ADR-0124's VERIFY-BY named the exact rows: *every `sources=1` name reads `agreement
  0.000` / `combinedForecast 0.0`, and the largest conviction belongs to a corroborated name*. Live:
  **META `sources=1`, `agreement 0.000`, `fc 0.000`, `tgt 0.000`** (was `1.000` / `15.41` / **−78.3**
  shares against a holding of 0); top of book is **MSFT `sources=2`, `agreement 0.958`, `fc −10.381`**.
  ✅ VERIFIED with no appeal to money, which is still the scorer's at 1/6. Write mechanism VERIFY-BYs.
- **Rule 84 — check deployment by process start time, not by `git log`.** The JVM started **12:45:40
  local**, 45 s after commit `3e7817e` (12:44:55). That one line is what separates "graded the change"
  from "graded code the app never ran" — and the loop has been burned by exactly that before.
- **Rule 85 — a restart re-cuts the tradable set, so any post-restart "the churn stopped" claim is
  confounded.** The second half of the VERIFY-BY (cancellation runs stop) passed — zero `fusion re-plan`
  cancels post-restart — but `selector` went to `measured 19, tradable 9` in the same minute and ORCL, the
  name that had been ramping, left the routed set entirely. Recorded as confounded, not as evidence.
  Warm-up and the fix change the same observable; only the *first* check discriminates.
- **Rule 86 — the ORCL ramp is the TSLA pathology with a different ticker, and it indicts ADR-0084, not
  fusion.** Pre-restart: 15 consecutive `fusion re-plan` cancels, SELL size ramping **5 → 12 → 18 → … →
  94**, **zero** fills. A DAY LIMIT resting at the mid, re-planned every 30 s, simply never transacts —
  the entry side of one-sided execution failing *silently*, so the desk holds nothing while believing it
  is working an order. Promoted to must-fix **#1**.
- **Rule 87 — resist re-adopting the fee framing rule 74 already killed, even when it looks compelling.**
  `/api/attribution` reads ALPHA `totalPnl` **−$18.66** against `feesPaid` **$21.10** — the strategy book
  is positive before commission, which is a very tempting "cost is the whole loss" story. But rule 74
  measured fees at only **21%** of the firm realized loss. Fee and adverse selection are both *execution*
  costs; split them before blaming either, and note the firm figure is dominated by **NQ −$35.82** and
  **GOOGL −$40.71**, which are frozen (byte-identical run-over-run, zero exposure, not trading).
- **Attribution this window (honest split):** ADR-0124 touches only META and TSLA, both at `quantity 0`
  with no new PnL. Everything that moved — JPM **−$24.88** over 41 fills ending flat, JNJ **+$42.64** over
  44 fills ending flat, AMZN **−$14.79**, GOOG **−$8.28** — is a multi-source name the change did not
  silence. So **none** of the −$31.33 is attributable to it. The gross fall $28,332.63 → $9,651.27 is
  claimed for **neither** side: ADR-0124 shrinks targets book-wide *and* the restart re-cut the universe,
  in the same minute, and these numbers cannot separate them.

## 2026-07-29 17:30Z — no-change (ADR-0124 at 2/6): the fix holds, but the churn it was blamed for is back

- **Rule 88 — a "the symptom stopped" check taken right after a restart must be RE-TESTED a cycle later,
  not just flagged as confounded.** Rule 85 correctly refused to credit ADR-0124 for the post-restart
  silence. This cycle re-tested it and the silence was indeed warm-up: **3 of 17** post-restart orders are
  `fusion re-plan` cancels, with **AAPL** running the ORCL ramp in miniature — SELL **1 FILLED → 1 FILLED
  → 1 CANCELLED → 2 CANCELLED → 3 ROUTED**, re-planned 30 s apart at a growing size. Flagging a confound
  is only half the job; the other half is scheduling the re-test.
- **Rule 89 — that re-test is what NARROWED the diagnosis, and narrowing is worth a cycle.** The
  cancellation churn is now proven **independent of the agreement scaler**: it recurs on a three-source
  name with the scaler working correctly. It belongs wholly to must-fix #1 (one-sided execution,
  ADR-0084), and the AAPL ramp is the *second* named instance of a mid-resting limit that never
  transacts. A held cycle that removes a wrong attribution is not a wasted cycle.
- **Rule 90 — before blaming the buffer for holding tiny positions, check whether anything has EDGE.**
  `/api/fusion/targets` shows NVDA aiming **+148.27** against **−3.0** held with `deltaQty 0.0`, JPM
  **−69.89** against **0** with `deltaQty 0.0` — which reads as a refusal to trade. It is not:
  `PositionBuffer` is under a reduce-only edge gate, and cohort-clustered t on `/api/signals/telemetry`
  (LIVE 3600 s) says why — **reversion +8.783 bps on 29 cohorts, t = +1.22**, the only positive
  expectancy in the book and short of the 1.5 hurdle; trend **−8.634**, xsreversion **−11.524**, social
  **−4.370**, momentum **−13.257**. The gate is correct. The desk holds little because it has measured
  little, and reversion needs **more cohorts, not more tuning**.
- **Rule 91 — a gross rise on a hedged book is two legs, not more risk.** Gross **+$7,742.82** on the
  window looked like risk-taking; `/api/risk` says EQUITY gross **$9,288.86** at net **−$9,288.86** (nine
  shorts) against one ES leg of **$9,125.84** long, firm net **−$163.02**, `/api/hedging`
  `status: ON-TARGET`. Read net alongside gross before calling a gross rise a risk event.
- **Attribution this window (honest split):** every name that traded — JNJ (44 fills, $58,850), JPM (41,
  $28,661), AAPL (37, $27,964), GOOG (30, $27,041), MSFT (36, $22,487) — is **three-source**, i.e. a name
  ADR-0124 does not touch; the three names it silences (TSLA, META, GOOGL) sat at `targetQty 0` and did
  not trade. So **none** of the **+$1.74** is attributable to the change; it is the standing exploration
  configuration against the market. The gross rise is attributable to the **hedge**, not to the change.

## 2026-07-29 18:00Z — no-change (ADR-0124 at 3/6): the re-measurement killed must-fix #1

- **Rule 92 — apply the small-sample rule to the HEADLINE number, not just to the sub-buckets.** Must-fix
  #1 carried an explicit "do not chase the holding-period buckets, n=65 and not monotone" caveat for three
  cycles — while the **−11.6 bps** headline that ranked the item #1 rested on *the same 65 round-trips*.
  Re-measured this cycle by FIFO reconstruction over all **276** LIVE fills: LIMIT-in → MARKET-out is
  **n=186 at +1.68 bps**, and clustered by instrument the ALPHA book is **+1.220 mean bps, t = +0.13** —
  statistically zero. The item was closed rather than acted on. Had the loop not re-measured, the next
  change would have been an ADR superseding ADR-0084 to fix a cost that does not exist.
- **Rule 93 — a re-measurement is only trustworthy if it RECONCILES to a live endpoint; check that first.**
  The reconstruction initially understated futures by their contract multiplier (NQ 20×, ES 50×), which
  showed up as MACRO **−$1.95** against `/api/risk` `MACRO.realizedPnl` **−35.82347655**. With the
  multiplier applied it returns **−$35.82** — an exact match, which is what licenses quoting the equity
  cohort figures at all. Reconcile the derived number to a number the app publishes, or don't use it.
- **Rule 94 — "the desk executes for free and earns nothing" is the proof that the problem is EDGE.** 189
  equity round-trips, **$106,291.89** of closed notional, **+$20.30** net. That is the standing priority
  stated as a measurement rather than an assertion: cost is not the constraint, so no amount of execution
  or combiner work can help. Reversion remains the only source positive at **every** horizon, with
  expectancy scaling in horizon as a real signal does — **+0.144 bps (266 cohorts, t +0.40) at 225 s,
  +2.300 (101, t +1.38) at 900 s, +9.066 (29, t +1.26) at 3600 s** — and needs **cohorts, not tuning**.
- **Rule 95 — decompose the firm loss by BOOK before diagnosing anything.** The firm's realized loss is
  **entirely** two NQ round-trips (MACRO **−$35.82**, −84.02 bps, now closed and flat) against ALPHA
  equities at **+$20.30**. The obvious hypothesis — futures sized without their contract multiplier — was
  **falsified** by reading `TargetPlanner` (`unitValue = price × contractMultiplier`). n=2: log, don't chase.
- **Attribution this window (honest split):** the three names ADR-0124 silences (TSLA, META, GOOGL) all sat
  at `targetQty 0.00` and did not trade; every name that traded is multi-source and untouched by it. So
  **none** of the **−$31.16** is attributable to the change. It is **−$31.54** of unrealized mark on 9 open
  equity shorts — market. The gross rise is the hedge leg (EQUITY net **−$8,734.41** against ES
  **+$9,114.16**, firm net **$379.75**), not added risk (rule 91).

## 2026-07-29 18:30Z — no-change (ADR-0124 at 4/6): the desk's weight-discipline rules have never fired

- **Rule 96 — a rule guarded by `if (!anyAdmitted) return` is OFF, not conservative, once the gate that
  sets `anyAdmitted` is disabled.** `TelemetryWeights.compute` classifies sources ADMITTED / UNPROVEN
  (ADR-0097 → held at `weights.min`) / CONTRADICTED (ADR-0111 → stood down to 0), and both demotions sit
  behind that guard. ADR-0122 disabled the edge gate on this paper book, so nothing ever clears admission,
  `anyAdmitted` is permanently **false**, and **neither rule has ever fired here**. Proven live rather
  than inferred: `/api/fusion/targets` `weights` = `reversion 2.2992, social 1.1056, momentum 0.6265,
  xsreversion 0.5275, trend 0.4412` (Σ 5.0000) — **no source at the `weights.min=0.25` floor, none at 0**.
  When you disable a gate, audit every rule downstream that reads its verdict.
- **Rule 97 — check WHICH SIDE of the vote the weight actually sits on before calling the combiner tuned.**
  Cohort-clustered t on `/api/signals/telemetry`: reversion **+0.488 / +1.611 / +1.239** at 225 / 900 /
  3600 s is the only source positive at every rung, yet it carries **45.98%** of Σweights while the four
  sources non-positive at the selected rung carry **54.02%**. The Φ(t) statistic down-weights losers but
  never removes them, and removal is what the two inert rules were for.
- **Rule 98 — expectancy that SCALES with horizon is the signature that separates a signal from noise.**
  Reversion reads **+0.174 → +2.708 → +8.614 bps** across the three rungs, roughly in proportion to the
  period, and its best-rung t has risen **+1.406 (102 cohorts) → +1.611 (103)** in one cycle. Noise does
  not scale that way. This is the first cycle where the standing priority's precondition — *a measured
  edge exists to be shaped* — is actually met, which is what licenses touching the combiner at all.
- **Falsified this cycle, do not re-chase:** *"the edge gate measures the wrong horizon"* — `HorizonLadder`
  (ADR-0082) already evaluates 3600 / 900 / 225 (`jethro.signals.horizon-rungs=3`) and selects by best
  p-value; the lever exists and is exercised. And *"Bonferroni across 3 nested rungs is over-conservative"*
  is true in the literature but changes nothing about what sizes this book while ADR-0122 holds the gate off.
- **Attribution this window (honest split):** the four names ADR-0124 silences (TSLA, META, GOOGL, and
  newly ORCL) all sat at `targetQty 0` and did not trade, so **none** of the **−$4.47** is the change's.
  It is **−$71.91** of unrealized mark on four equity shorts against **−$10.42** realized — market. The
  **−$1,634.51** gross fall is the hedge tracking down (EQUITY net **−$6,409.26** vs one ES leg at
  **+$6,507.25**, firm net **$98.00**, `status: ON-TARGET`), not a de-risking (rule 91).

## 2026-07-29 19:00Z — no-change (ADR-0124 at 5/6): the desk cannot get out of its own positions

- **Rule 99 — a no-trade band scaled by the TARGET, not by the HOLDING, freezes every small position the
  desk actually has.** `PositionBuffer.band()` = `|target| × Forecast.TARGET_ABS ÷ |forecast| ×
  buffer-fraction`, with `TARGET_ABS = 10.0` and `buffer-fraction = 0.5`. This book's live
  `|combinedForecast|` runs **0.045–8.338**, so the `10 ÷ |fc|` ratio inflates the band 1.2×–222×. Every
  held name computed from `/api/fusion/targets` in exact decimal is inside its own band — AMZN band
  **87.89** vs gap **13.00**, AAPL **118.99** vs **9.00**, MSFT **45.06** vs **8.00**, GOOG **47.49** vs
  **5.97**, NVDA **62.90** vs **2.00** — so `bufferedDelta` returns **0.0000** every cycle, indefinitely.
  GOOG is the clean proof it is not a stalled entry: target and holding are the **same side** (short 2.03
  wanted, short 8.00 held) and the desk still cannot cut the difference.
- **Rule 100 — this is the SECOND rule found gated behind the disabled edge gate; treat that as a class,
  not a coincidence.** ADR-0118 diagnosed this exact trap and wrote the escape (`isTrappedExit` → close
  the holding in full), but the call sits inside `if (gate != null && !gate.mayIncrease(...))` and
  `jethro.fusion.edge-gate.enabled=false` (ADR-0122). So the fix exists in source and is unreachable in
  the configuration the desk runs — exactly like rule 96's `anyAdmitted`. **When a gate is disabled, audit
  every rule whose body reads that gate's verdict; the ones that matter most are the ones that get out.**
- **Rule 101 — the frozen names ARE the loss.** `/api/risk` positions: AMZN **−$42.33**, MSFT **−$26.49**,
  GOOG **−$22.81** unrealized against a firm unrealized of **−$83.90**. The desk is holding its losers
  because the band will not let it out, not because a model chose to.
- **Rule 102 — a commit is not a deployment, and an out-of-band commit can poison a pending measurement.**
  `ecf079c` (ADR-0125, V48, ~12 new equities) was committed **18:54:55Z** by a non-loop session after the
  **18:46:05Z** boot; Flyway tops out at **version 47** and the boot log still reads `Alpaca real-time WS
  for 7 equities`, so it is NOT running. `.pending-baseline.json` still names **3e7817e4f (ADR-0124)**,
  which scores next cycle — if the app restarts first, V48's universe change lands inside ADR-0124's
  window and the scorer credits/blames the wrong commit. Discount that row in words; never hand-edit the
  ledger or the baseline to compensate.
- **Attribution this window (honest split):** the three names ADR-0124 silences (TSLA, META, GOOGL) do not
  appear in `recent_orders` at all, so **none** of the **−$26.18** is the change's. The window's trading is
  three build-then-dump round trips in a `CHOP` regime that roughly offset — JNJ **+$15.98**, NVDA
  **+$37.40**, JPM **−$35.20** realized — and the rest is **−$83.90** of unrealized mark on the five frozen
  shorts: market, on positions the desk is structurally unable to close. The **+$154.97** gross rise is the
  ES hedge tracking (`ON-TARGET`, `trackingRate 0.992506`), not added risk (rule 91).

## 2026-07-29 19:30Z — ADR-0126: the desk was sizing its biggest bets in names it could not stop out

- **Rule 103 — a WARN nothing consumes is not a control, it is a confession.** `FusionLifecycle` has
  logged `risk-cut σ sensor still cold for {} — this name cannot be stopped out` since ADR-0086 shipped,
  and no code ever read it. ADR-0125's widening turned that into the desk's dominant risk: **KO
  −101.879300** and **WMT −74.222500**, the two largest absolute targets in the whole book, were both in
  names that had never armed a stop (3 of 121 prices seeded), against a largest *protected* target of
  NVDA 37.621300. **When a log line states a risk control cannot protect something, something in the
  code must branch on that same condition — grep for the predicate behind every WARN you write.**
- **Rule 104 — verify a register item's CONSTANT before acting on it, not just its symptom.** Last
  cycle's must-fix #1 computed the no-trade band with `jethro.fusion.buffer-fraction=0.5`; `PositionBuffer`
  is wired from `jethro.fusion.position-buffer.fraction=0.10` (`FusionConfig:211`). Five times too wide,
  and the whole "every position is frozen" diagnosis was an artefact of it — live `deltaQty` is
  **1.495053 GOOG / 2.704668 AAPL / 0.506787 JPM**, nothing frozen. Two same-named dials in one
  properties file is enough to invalidate a cycle's work; **read the wiring, not the property name.**
- **Rule 105 — the dead-branch class now has three members; assume it, do not discover it.** ADR-0075's
  reduce-only clamp, ADR-0118's trapped-exit escape and (nearly) this control all want to live inside
  `if (gate != null && !gate.mayIncrease(...))`, which is unreachable while ADR-0122 holds
  `edge-gate.enabled=false`. ADR-0126 is therefore a **conjunction that returns a verdict when
  `gate == null`**. Bonus: for a σ-cold name, ADR-0118's escape becomes reachable with the gate off.
  **Any new rule that vetoes risk must be written to work with every optional gate absent.**
- **Rule 106 — a clamp applied before `PositionBuffer` is a no-op.** That step re-derives every delta
  from the aim and discards whatever was computed upstream, which is why ADR-0064's clamp had to be
  re-applied inside it. Put the veto where the delta is finally decided.
- **Attribution this window (honest split):** **none** of the **+$68.30** is a loop change's — no loop
  commit was deployed into this window; the ~19:09Z JVM restart brought ADR-0125's V48 universe live
  (`instruments 49`) and that is what moved the book. Realised: two closed-out losers (**GOOGL −$40.71**
  at 6.0 bps slippage, **MACRO/NQ −$35.82**) against **NVDA +$42.15 / AAPL +$24.41 / GOOG +$21.30 /
  JNJ +$15.98** and **HEDGE +$10.50**. ALPHA is **+$8.56 net of $27.22 fees**; the firm is negative
  only because MACRO gave back **−$35.82**. Market and mix, not code.
- **Edge check (still no):** reversion is the only source positive at all three horizons (**+7.6173 bps
  @3600s t≈1.1**, +2.1683 @900s t≈1.3, +0.1366 @225s) and clears neither the 1.5 hurdle nor the ~3 bps
  round trip. **xsreversion is negative at every horizon (−3.5725 / −0.8669) on weight 0.860** and
  **social negative at every horizon on weight 1.030** — queued as must-fix #2, not taken, because
  re-weighting is combiner work and ranks below a hole that lets the desk open unclosable risk.

## 2026-07-30 11:30Z — the platform had been dead for 4.5 hours and the heartbeat called it "market-closed"

- **Rule 107 — a new enum value is a two-sided change: the language enum AND the schema constraint.**
  ADR-0129 added `INDEX` to `AssetClass.java` and shipped `V49__world_indices.sql` inserting twelve index
  rows, but never widened `instrument_asset_class_check` (last set by `V7__rates_and_swaps.sql:9` for
  `SWAP`). Result: `SQLSTATE 23514` on the first insert → Flyway aborts → `PersistenceConfig.flyway` fails
  → Spring context aborts → **the JVM never boots**. A migration that only ever ran in a test that doesn't
  exercise the constraint is not tested. **When adding a value to an enum that is persisted, grep for a
  CHECK on that column in the same change.**
- **Rule 108 — a boot-blocking migration outranks the no-new-change-while-pending rule.** That rule
  protects measurement evidence; a dead app produces none. ADR-0126 sat at zero accumulated cycles with
  `.pending-baseline.json` still holding `5e35752dd`, and would have sat there forever. Restoring service
  is not a strategy change — it touches no signal, dial or risk model — so it ships, the pending baseline
  is left alone, and the scorer's own refusal to record a baseline against an unreachable app keeps it
  honest. **Availability is prerequisite to the objective, not competing with it.**
- **Rule 109 — edit an unapplied migration in place; never "fix it forward" with a later version.** Flyway
  runs in version order, so a `V50` widening is unreachable while `V49` fails first. Check
  `flyway_schema_history` before deciding: it topped out at **V48**, `count(*) where asset_class='INDEX'`
  was **0**, and Postgres had rolled the failed transaction back with no history row to repair — so V49 was
  never published and editing it breaks no checksum. Verify by piping the migration through psql inside
  `BEGIN … ROLLBACK` (got `INSERT 0 12 / 0 12 / 0 24`) — green tests do not exercise Flyway against the
  real constraint.
- **Rule 110 — a frozen number reported as `available: true` is worse than an error.** Nine cycles
  (07:00Z–11:00Z) logged `action: market-closed`, `available: true`, and the identical
  `total_pnl 126.87240898 / gross 0E-8` — the last thing the app said before dying, indistinguishable from
  a quiet closed session. `logs/report.md` *did* show `Connection refused` on every endpoint. **Trust the
  endpoint errors over the status summary; when PnL is byte-identical across cycles, check for a pulse
  before concluding the book is flat.** Queued as must-fix #3.
- **Attribution this window (honest split):** **neither market nor change.** No loop commit deployed into
  this window and no position was live to be moved; the unchanged PnL is a stale read, not a held position.
  No credit and no blame taken for it.

## 2026-07-30 13:30Z — the book was flat all session because the warm-start seed only ever fired once

- **Rule 111 — a warm-start seed that runs only at boot is scheduled at the worst possible moment.**
  ADR-0071 replays stored prices into a sensor on FIRST SIGHT of a name. First sight is boot, and a boot
  is very often preceded by exactly the discontinuity that emptied the store's tail — an outage, a
  weekend, a pre-market start. `SensorWarmup` then correctly refuses to walk the hole and returns almost
  nothing (`0 of 193`, `1 of 241`, `0 of 121` live today), and the sensor is abandoned there for the whole
  process. Counted across the log since the 07:47 ET boot: **39 `trend sensor still cold`, 0 warmed**;
  same 39/0 for reversion; 9/1 for the σ sensor. **A recovery mechanism that gets one attempt, taken at
  the moment of maximum failure probability, is not a recovery mechanism.** Fixed by ADR-0131 — a cold
  sensor re-seeds on its own warm-up cadence and retires once warm.
- **Rule 112 — when the whole book is flat, read `sources` on `/api/fusion/targets` before anything else.**
  Every name showed `sources: 1`, `agreement: 0.0`, `combinedForecast: -0.0` and `targetQty: 0` while the
  raw per-source forecasts underneath were large (MSFT −20.0, AMZN −12.5, AAPL +6.4). That is ADR-0124
  behaving exactly as designed — one effective source has unestimable dispersion, so it sizes at nothing.
  The forecasts were never the problem; the *count* was. `forecastScalars` is the fastest confirmation: a
  source absent from that map has published nothing at all since boot.
- **Rule 113 — look for the name that worked, it isolates the cause.** NQ was the one instrument whose σ
  warmed and whose reversion seed returned `232 of 241`. NQ prints overnight; every equity does not. That
  one contrast ruled out the sensors, the config and the feed, and pinned the cause on the discontinuity
  in the stored series — in one line of log, without instrumenting anything.
- **Rule 114 — resetting an estimator is safe exactly when it is cold, and only then.** The re-seed drops
  per-name state whole (`forget`) before replaying, because the store already contains every print the
  sensor consumed and replaying on top double-counts. That reset is confined to cold names on purpose: a
  cold forecast publishes no view and an unmeasured σ arms no ADR-0086 stop, so nothing live can move
  underneath a live position. **A warm sensor is never re-seeded.**
- **Attribution this window (honest split):** **neither market nor change.** No loop commit deployed into
  this window and no position was open, so the unchanged PnL is the absence of activity, not a held
  position moving. No credit and no blame taken for it.

## 2026-07-30 14:00Z — ADR-0131 held at 1/6 cycles; the forecast sensors woke, the σ sensor did not

- **Rule 115 — a metric that moves in the right direction is not proof your mechanism moved it.** After
  ADR-0131 deployed, `/api/fusion/targets` went from every name at `sources: 1` to **15 of 20 at
  `sources: 2`**, agreement up to **0.870**, 15 non-zero `targetQty`, and a `trend` scalar present where
  the source had published nothing. Tempting to call it verified. But every `sensor warmed` line is
  stamped within 90 seconds of the 09:49:40 boot and every still-cold name logged exactly **one** seed
  line — the retry cadence (193 sightings / 121 plans) had not elapsed. The sensors warmed because this
  restart followed two hours of uptime rather than a 4h45m outage, so the store's tail was full. **Check
  the timestamps against the cadence before crediting a retry that has not run yet.**
- **Rule 116 — when targets are non-zero and exposure is still zero, read `deltaQty`, then read what
  gates it.** All 20 targets showed `deltaQty: 0` against `targetQty` up to **+378.11**.
  `PositionBuffer.mayIncrease` needs the ADR-0064 edge gate **and** ADR-0126's `stopArmed`, and
  `stopArmed` is `sigmaPerSample(id).isPresent()`. With **1 `risk-cut σ sensor warmed`** (NQ) against
  **19 `still cold`**, every name with a real view was vetoed from opening. Two independent locks on the
  same door: fixing the forecast sensors could never open the book on its own.
- **Rule 117 — a pending change that is itself the remedy for the live blocker earns its evaluation
  window.** The σ seeds are short but close (MSFT **106 of 121**, AAPL **79 of 121**, AMZN **75 of 121**),
  which is precisely what ADR-0131's retry exists to clear. Shipping a second change on top would both
  destroy the scorer's evidence and pre-empt the fix already in flight. Held with no change; next run's
  test is `risk-cut σ sensor warmed` > 1 and any non-zero `deltaQty`. If the retries fire and σ stays
  cold, the defect is the **seed span**, not the cadence.
- **Attribution this window (honest split):** **neither market nor change.** `orders_day.total` is 0, no
  position was open, and PnL sat at $126.87 for the third consecutive run. ADR-0131 takes no credit for
  the warmed sensors (a clean boot did that) and no blame for the flat book (ADR-0126's σ veto is doing
  that, by design).

## 2026-07-30 14:30Z — the book reopened, and ADR-0131's retry is unreachable code on 3 of its 4 call sites

- **Rule 118 — a retry cadence measured in the sensor's own units can be longer than the process lives.**
  `SensorReseed` counts *sightings*, one per scheduled tick of the owning lifecycle, at a cadence equal to
  `warmupSamples()`. Multiplied by each configured interval that is **16.1 min** (trend, 193×5s),
  **40.2 min** (reversion and xs-reversion, 241×10s) and **60.5 min** (σ, 121×30s) — against a JVM whose
  `uptimeSeconds` was **1410 (23.5 min)** and whose previous boot was **~17 min** earlier, because the
  loop redeploys every cycle. The counters are in-heap, so they die with the process. Three of four call
  sites can *never* reach their retry. Trend was the only source that recovered, and its cadence is the
  only one shorter than the process lifetime — the arithmetic predicted exactly which one would work.
  **The warm-up length is the right seed span and the wrong retry period.** When you derive a period from
  a sensor constant "to avoid inventing a dial", check the product against the process's actual lifetime.
- **Rule 119 — when a log line fires on every attempt, its ABSENCE is proof, not weak evidence.**
  `warmWhileCold` logs INFO on warm and WARN on still-cold, unconditionally. So "every boot-cold name has
  exactly one `still cold` line" is not ambiguity about verbosity — it is positive proof that no second
  attempt ran. Establish the logging contract *first*; then a missing line is a measurement.
- **Rule 120 — both VERIFY-BY numbers can go green while the mechanism stays dead.** σ-warmed went 1→2
  and `deltaQty` went non-zero (NVDA **−4.127545**, MSFT **−0.040934**) — the exact two tests written last
  cycle. Both were false positives: the second σ line was NQ's *first* seed as a late-arriving instrument
  (all four of its sensor lines stamped 10:19), MSFT's σ came from the boot seed at 10:07:11, and NVDA's
  armed off the live tick stream. **Write VERIFY-BY tests that only the mechanism can pass** — the next
  one is a *second* `still cold` line for a name that already logged one, which nothing else can produce.
- **Rule 121 — when one lock opens, re-read which lock is now binding; the ranking moves.** With σ no
  longer universal, `strategy_diag.edgeGated` holds **13 of 20** names out on `no positive OOS edge`
  (GOOGL momentum **−58.18505489**, PFE **−137.76114263**, PG **−82.99627912**) against large real targets
  (WMT **+386.53347**, BAC **+535.398702**) all sitting at `deltaQty: 0`. That is ADR-0064 working as
  designed, not a defect — the answer stays a new signal with measured edge, never a looser gate.
- **Attribution this window (honest split):** **desk activity, cause not separable.** Total PnL
  **$147.57045400** (+**$20.70**), gross **$3288.885** from $0.00, on the only two names traded — MSFT
  **+6** (totalPnl **$77.06615500**) and NVDA **−3** (**$19.29237792**), both opened this window. ADR-0131
  takes **no credit**: MSFT's σ came from the boot seed and NVDA's from the live stream, neither being the
  path ADR-0131 added, and that path never ran. How much of the +$20.70 is market drift on two intraday
  positions versus selection cannot be separated from these numbers, so no cause is claimed.

## 2026-07-30 15:02Z — the re-seed retry fired, disproved my own root cause, and is moving sensors backwards

- **Rule 122 — a retry that re-derives from a sliding window is not a retry; it is a coin flip.**
  ADR-0131's `warmWhileCold` calls `forecaster.forget(...)` and replays whatever the re-read returns.
  The seed anchors on the *current* mark's provider timestamp and walks newest-first, so the window
  **slides** with the anchor instead of accumulating. Wave 1 → wave 2 seeded counts went **down** for six
  tradable names — **HD 164→140**, **JPM 188→161**, **MCD 171→162**, **JNJ 186→182**, **PFE 178→175**,
  **XOM 170→169** — and up for four (**UNH 153→183**, **PG 176→183**, **CAT 150→159**, **CVX 157→162**).
  For the six, the retry threw away ~16 min of consumed live prints *and* replayed less history than the
  boot seed had. **Waiting moves the window; it does not fill it.** Any retry that discards accumulated
  state must first prove the replacement is at least as large — make re-seeding monotone or don't retry.
- **Rule 123 — check the discriminating test's OTHER branch; last cycle's root cause was half wrong.**
  I concluded the retry cadence outlives the process on 3 of 4 call sites. For trend that is **false**:
  16.1 min (193 × 5 s) fits inside this process (`uptimeSeconds` **1506**) and it fired — **52** still-cold
  lines across **27 distinct names**, XOM at **10:36:25.022** then **10:52:34.778**, **16 min 9 s** apart,
  matching the prediction to the second. It held only for reversion (**22** lines / **22** distinct, zero
  duplicates) and xs-reversion (**0** lines). A cadence table that is arithmetically right can still be
  the wrong *diagnosis* — confirm which call sites the arithmetic actually indicts.
- **Rule 124 — "seeded N of N and still cold" means the count is not the predicate.** Twelve rate/swap
  names (USD.TSY.\*, USD.SOFR.\*, USD_IRS_\*) replayed **193 of 193** in *both* waves and `warm()` is
  still false. Their stored series does not move, so the scale estimator has nothing to absorb. Re-seeding
  a name whose seed is already complete is unfixable-by-retry — such names must be declared **unwarmable**,
  not retried forever. When a "not enough data" remedy is applied to a full dataset, the diagnosis is wrong.
- **Rule 125 — a pre-registered discriminator is worth more than a confident diagnosis.** Last cycle wrote
  *"if the retries fire and σ stays cold, the defect is the seed span, not the cadence."* That one sentence
  converted this cycle from re-arguing a hypothesis into reading off an answer, and it redirected the fix
  away from "shorten the cadence" — which would only have regressed Class B faster. Write the branch you
  do *not* expect.
- **Attribution this window (honest split):** **desk activity, cause not separable, and not ADR-0131's.**
  Total PnL **$167.76973099** (later read **$178.02603045**), gross **$24383.04512500** from $3288.885,
  net **$6210.52512500** — **1.5%** of the firm cap, no flags, `on_track=true` at **13.06%** vs the **1.0%**
  target. Eight ALPHA names plus HEDGE traded. The enabling event was the **boot seed** warming **7** σ
  sensors at **10:36:40** (NVDA, MSFT, KO, AAPL, GOOG, AMZN, NQ) against one in the prior process — the
  ADR-0071 path, untouched by ADR-0131 — which lifted the ADR-0126 σ veto. ADR-0131's retry ran only after
  that and every name it touched stayed cold, so it takes **no credit**. Market drift versus selection on
  freshly-opened intraday positions cannot be separated from these numbers; no cause is claimed.
  `/api/attribution` reads `firmTotal` **$167.04598599** = ALPHA **$57.15161336** + HEDGE **$145.71784918**
  + MACRO **−$35.82347655** — the hedge book carries most of the firm total, and MACRO is the one loser.

## 2026-07-30 15:30Z — the pre-registered test failed on a fresh JVM, and the one success on the tape names the fix

- **Rule 126 — a mechanism that helps some names and hurts others is not "partly working"; it is
  non-monotone, and that is a single fixable defect.** ADR-0131's retry reproduced on a *different*
  process (booted 11:06:51, `uptimeSeconds` **1392**): 8 names advanced (CAT **136→180**, HD 150→156,
  PFE 159→166, TSLA 3→8, GOOGL 0→1, EURUSD 1→27, META 1→3, NFLX 1→3), **4 regressed** — XOM **179→151**,
  CVX **165→146**, JNJ **160→149**, JPM **150→148** — and 12 stood still. The cause is one line:
  `warmWhileCold` calls `forecaster.forget(...)` **before** it knows what the replay yields, while
  `SensorWarmup` reads `step × samples × LOOKBACK_MULTIPLE` back from the *current* mark's provider
  timestamp and re-derives `step` from that same read. Both ends of the window and the stride move
  between waves. **Never destroy accumulated state before the replacement is in hand and measured.**
- **Rule 127 — the one success is worth more than the four failures, because it tells you what NOT to
  revert.** PG logged `still cold ... 190 of 193` at 11:07:04 and `trend sensor warmed PG from 193 stored
  prices (needs 193) — warm` at 11:23:14. Nothing but the ADR-0131 retry emits that line. So the answer
  is not "revert ADR-0131" — it is a strict-improvement guard: compute `seedPrices` first (it is already
  pure), replay only when strictly larger than this name's best. PG's 190→193 passes; the four
  regressions are refused; and the **12** rate names sitting at `193 of 193` are already at their maximum,
  so they retire from retrying with **no separate unwarmable rule**. One condition, both classes.
- **Rule 128 — when PnL falls, split realized from unrealized before you look for a culprit.** Total PnL
  **$128.22843661** is down **−$57.35** on the run, but realized *rose* to **$177.46496415** (from
  **$159.63438842**) while unrealized swung **+$18.39164203 → −$49.23652754**. Nothing was lost in a closed
  trade. Two names carry it — NVDA **−$27.85750000** and UNH **−$24.72000000** — together more than the
  whole firm unrealized figure, on positions opened this window. A book that opens 19 positions will mark
  against you on some of them; that is not a defect to chase.
- **Attribution this window (honest split): market on fresh positions, and again NOT ADR-0131's doing.**
  Gross **$40994.69587500** / net **$49.80412500** — 19 equities net long **$13298.56500000** against one
  ES short **−$13248.76087500**, i.e. gross with essentially no directional net, **2.7%** of the firm cap,
  no flags. The enabler was once more the **boot seed**: **19** σ sensors warmed at 11:07:29 against 7 last
  cycle and 1 before that — the ADR-0071 path ADR-0131 never touches — which lifted the ADR-0126 veto.
  ADR-0131's only attributable effects are one warmed sensor and four regressed warm-up counters, none of
  which is a position. Market drift versus selection on 30 minutes of new marks cannot be separated here;
  no cause is claimed. `/api/attribution` reads `firmTotal` **$120.16705892** = ALPHA **$20.35473620** +
  HEDGE **$135.63579927** + MACRO **−$35.82347655**, `hedgeMasking` **true**.

## 2026-07-30 16:00Z — third reproduction on a third JVM, and the second success proves the fix rather than the defect

- **Rule 129 — when a non-monotone mechanism succeeds twice, check whether both successes share a
  precondition; if they do, that precondition IS the fix.** ADR-0131's retry warmed **PFE 192→193** this
  cycle (11:36:35 `still cold ... 192 of 193` → 11:52:45 `trend sensor warmed PFE from 193 stored prices
  (needs 193) — warm`) after warming **PG 190→193** last cycle. Both are *strict improvements* in seed
  count. Meanwhile the six regressions — **HD 171→133, PG 181→143, CAT 174→139, UNH 156→141,
  MCD 159→145, GOOG 191→180** — are all cases where the replay returned *less* than the sensor held.
  The strict-improvement guard is not a compromise between the wins and the losses; it is the exact
  boundary between them. Third JVM (PID 3523413, boot 11:36:18, `uptimeSeconds` 1425), third distinct set
  of victims, one defect. **Class A reconfirmed a third time:** the same 12 rate/swap names seeded
  `193 of 193` in both waves and stayed cold, so they retire under the same one condition.
- **Rule 130 — an unrealized swing on freshly-opened positions is a mark, not a loss; wait one cycle
  before calling it anything.** Last cycle's unrealized **−$49.23652754** (NVDA −$27.85750000, UNH
  −$24.72000000) now reads **−$6.20316872**, with realized up to **$180.21540531** and total PnL
  **$174.01223659** (**+$25.70** on the run). I declined to chase it as a defect and it reverted on its
  own. This is Rule 128 paying off — record the split, don't act on it.
- **Rule 131 — gross falling while PnL rises is the objective moving, and it deserves the same
  attribution discipline as a loss.** Gross **$32990.96481250** is down **−$18,049.05** on the run
  (2.1% of the firm cap, **$1,467,797** headroom, no flags) while PnL rose. The driver is visible in
  `recent_orders`: ALPHA SELLs across PG/KO/BAC/GOOG/AMZN/XOM/NEE/AAPL/NVDA plus a run of small HEDGE
  **ES BUYs** covering the short leg — fusion re-planning, **not** ADR-0131, whose only attributable
  effects on this tape are one warmed sensor and six regressed counters, none of which is a position.
  **Carry forward:** net moved **+$49.80412500 → −$8,634.83518750**, so the market-neutral book of last
  cycle is now net short. At 0.9% of the net cap that is not a danger — but note it now rather than
  rediscover it later. `/api/attribution`: `firmTotal` **$174.01223659** = ALPHA **$59.63435276** +
  HEDGE **$150.20136038** + MACRO **−$35.82347655**, `hedgeMasking` flipped **true → false**.

## 2026-07-30 16:30Z — the window closed BAD, the auto-revert silently failed, and the memory files were the reason

- **Rule 132 — a failed auto-revert is the highest-priority defect on the board, above any queued fix.**
  The ledger row for `efccc6502` reads ❌ BAD with the note `⚠️ REVERT FAILED (git conflict): the BAD
  commit is STILL LIVE and needs a manual revert`. The scorer attempts a revert **once**; nothing retries
  it. So a BAD change can keep running indefinitely while the loop moves on to new ideas — exactly the
  failure mode Step 0 exists to prevent, arriving through the automation rather than through diagnosis.
  **Read the ledger note, not just the verdict**, every cycle.
- **Rule 133 — never `git revert` a loop commit whole; revert its code paths and leave the memory files.**
  The conflict was mechanical and predictable: of the 13 files in `efccc6502`, exactly three had moved
  since — `docs/loop-findings.md`, `reports/last-analysis.md`, `reports/must-fix.md` — each touched by all
  five hold-cycle doc commits. Every loop change carries those three, so **every** loop revert will
  conflict on them, and a revert that "succeeded" would have erased five cycles of findings. Correct
  procedure: `git checkout <bad>^ -- <code paths>`, `git rm` the added sources, confirm with
  `git diff --cached <bad>^` returning empty over the code trees and a `grep` for the removed class
  returning none, and mark the ADR **Status: Reverted** rather than deleting the decision record.
- **Rule 134 — an ADR consequence bullet that pre-emptively excuses a defect is where to look when the
  change fails.** ADR-0131 accepted its own non-monotonicity in advance: *"the worst case discards state
  that was earning nothing and would have kept earning nothing."* The second clause does not follow from
  the first — a sensor at 191 of 193 prints publishes nothing **now** but is two prints from publishing.
  Three JVMs, three different victim sets, one defect, all predicted by that one sentence. When writing an
  ADR, treat "accepted deliberately and not guarded against" as a claim requiring evidence, not a waiver.
- **Rule 135 — a BAD verdict can be driven entirely by the exposure leg, and it still stands.** The note
  reads `risk-adj return/cycle -0.000016 over 7 cycles, t=-0.03 (hurdle 1.5); gross 0→33,743 [grew]`. The
  t-statistic is indistinguishable from zero; the verdict came from gross rising off a **dormant** book,
  which is the outcome the loop had been trying to produce. Record the tension honestly — then revert
  anyway. The scorer owns the verdict; arguing the book out of its own measurement is how a loop with no
  human in it goes wrong.
- **Rule 136 — five cycles in sensor plumbing ended at BAD; the standing priority was right.** Live now:
  `firmTotal` **$125.43676941** = ALPHA **$11.04806156** + HEDGE **$150.21218440** + MACRO
  **−$35.82347655**, `hedgeMasking` **true** — the hedge carries the whole firm total while
  `strategyAlpha` reads **−$24.77541499** net of ALPHA's **$51.332169** fees against `totalFees`
  **$54.339315** on a **$125.44** total. `strategy_diag` still reads `measured` **29**, `tradable` **16**,
  **13** names `no positive OOS edge`. **Next actionable item is a new signal with measured edge — and
  ALPHA's fee-to-PnL ratio — not more sensor mechanics.**

## 2026-07-30 17:00Z — the hand revert verified on every leg; the next leak is notional churn, not edge

- **Rule 137 — a revert is verified by the ABSENCE of a log line, which is the cheapest VERIFY-BY there
  is; pre-register it.** The ADR-0131 WARN text appears **zero** times this run against a JVM that logged
  it last run, and every `trend sensor warmed … from … stored prices` line sits between **12:36:32** and
  **12:36:57** against a boot at **12:36:29** — inside the boot window, so ADR-0071 seeding still fires
  and the reverted retry does not. Two greps closed a five-cycle defect with no judgement call in the
  loop. When a change adds or removes a log line, make that line the VERIFY-BY.
- **Rule 138 — closing item #1 does NOT free you to act when the pending baseline exists.** The scorer
  printed `still accumulating evidence (1/6 cycles)` and `reports/.pending-baseline.json` was present, so
  the correct move was to rank the next item and stop. A verified fix and a scored fix are different
  things; only the second lifts the hold. Rank the next target in `must-fix.md` so the freed slot isn't
  re-derived next cycle.
- **Rule 139 — fees are bps of NOTIONAL, so fill count is a decoy; measure turnover_usd against gross
  exposure.** `turnover_cost_by_name` reads JNJ **$75,222.83**, JPM **$67,623.49**, GOOG **$64,535.97**,
  AAPL **$61,986.59**, MSFT **$56,713.32** at **1.00** bps, on a book whose entire `grossExposure` is
  **$33,028.89** — five single names each churned more notional in one window than the firm has at risk.
  MSFT's 93 fills and NVDA's 109 look like the problem and aren't; the ADR-0084 re-plan re-issuing
  targets every ~30s is. **A cost leak beats an edge hunt: it converts to risk-adjusted PnL without
  needing a signal to work first.**
- **Rule 140 — ALPHA's book line turned positive while `strategyAlpha` stayed negative; read both.**
  `/api/attribution` reads ALPHA `totalPnl` **$23.36357064** (from **$11.04806156**) but `strategyAlpha`
  **−$12.45990591**, with `feesPaid` **$56.444977** — the book made money gross and gave more than all of
  it back in fees. `firmTotal` **$145.76317190** is still carried by `hedgePnl` **$158.22307781** with
  `hedgeMasking` **true**. The alpha book does not need a better forecast before it needs a cheaper one.
- **Rule 141 — do not credit a revert for a good window.** PnL rose **$20.51** and gross fell
  **$3,279.94**, but the reverted mechanism opened and closed nothing — it only discarded sensor state.
  One cycle cannot separate market drift from sensors staying warm, so claim neither. That is what the
  6-cycle window is for; say "not separable" rather than inventing a cause.

## 2026-07-30 17:30Z — the revert re-verified on a second JVM; the order tape renamed the churn defect

- **Rule 142 — "exactly one log line per name" is a stronger revert proof than "all lines inside the boot
  window"; prefer the count over the timestamp.** Last cycle inferred no-second-wave from timestamps. This
  cycle proved it directly: keyed by lifecycle+name, every cold name logs its `still cold for … after
  seeding N of 193 stored prices` line exactly **once** (`TrendForecastLifecycle|XOM` 1, `|PG` 1, `|CAT` 1,
  `|MCD` 1, `|UNH` 1, `|HD` 1). A count of one is unfalsifiable by clock skew or a slow boot; a timestamp
  window is not. When the reverted mechanism emitted a per-attempt line, **count it, don't time it**.
- **Rule 143 — check the late log lines individually before calling a boot-window verification clean.**
  **48** of **66** `sensor warmed` lines fell in the boot window at **13:04:33**–**13:04:59**; **9** did
  not, which on a naive read looks like the retry surviving the revert. Each was a late-arriving instrument
  taking its *first* seed (NQ 13:08:34–13:09:00, TSLA 13:07:09, META 13:21:32, GOOGL 13:28:04). A prior
  cycle already logged this exact false positive with NQ (Rule from the 10:19 block) — the memory paid off.
- **Rule 144 — the ALPHA churn defect is the re-plan's *absolute targets*, not its cadence.** `recent_orders`
  shows PG running BUY 10 FILLED → 4 FILLED → 3 CANCELLED → 4 CANCELLED → 13 FILLED → 2 ROUTED in ~2
  minutes, and NEE 1 → 2 → 2 → 13 → 13, on a ~30s ADR-0084 re-plan. The cancels work; the problem is that
  superseded slices **fill first**, so the book pays 1.00 bps on the full notional of every partial
  re-approach to a target it was already walking toward. **Fixing the cadence would not fix this — netting
  the new target against the slice already working would.** Don't tune the timer.
- **Rule 145 — a worsening fee-to-PnL ratio is the signal, not the fee level.** ALPHA reads `totalPnl`
  **$9.88820735** against `feesPaid` **$60.552698**, from **$23.36357064** against **$56.444977** last run:
  fees rose slightly while the book's result fell by more than half. `firmTotal` **$130.12772736** is still
  carried by `hedgePnl` **$156.06299656** with `hedgeMasking` **true** and `strategyAlpha`
  **−$25.93526920**. Track the ratio run-over-run; the absolute fee number hides the deterioration.
- **Rule 146 — reversion at the LONG horizon is the only positively-signed place left; look there for
  item #2.** `/api/signals/telemetry` at 3600s reads `reversion` `avgReturnBps` **+7.215148481777953** on
  **213** resolved and `xsreversion` **+2.7840649126009924** on **189** (`hitRate` **0.5592105263157895**),
  against `trend` **−4.104976474445127** and `momentum` **−3.1019291527777773**. The edge gate still says
  `no positive OOS edge` on **13** names and significance is its to compute — but a positive sign with a
  large resolved count is where an OOS test is worth spending a cycle, and the short horizons are not it.
- **Rule 147 — gross rising while PnL falls is not danger; read the headroom before reacting.** Gross rose
  **+$19,147.75** to **$46,057.91** while PnL fell **−$12.17**, which pattern-matches to "bleeding into
  rising risk". It isn't: that gross is **3.1%** of the cap with **$1,453,942** of headroom and **no** flag
  set. Danger requires proximity to the cap or the breaker. De-risking here would have been the error.

## 2026-07-30 18:00Z — the revert holds on a third JVM; ALPHA's churn traced to an inverted band fallback

- **Rule 148 — a designed defence that is CONFIGURED ON can still be inert; check its fallback branch before
  concluding it doesn't work.** `jethro.fusion.position-buffer.enabled=true` and ADR-0101's cost-aware
  widening `max(fraction, min(1, 2C/mu))` are both live, yet no name is ever widened: the documented
  fallback is *"Unmeasured … or non-positive cost or edge ⇒ the convention, unchanged"*, and
  `strategy_diag.edgeGated` reads **13** names with **`no positive OOS edge`** on every one. mu is never
  measured, so every name silently takes the **narrow** `fraction=0.10` branch. "Enabled" is not "binding".
- **Rule 149 — an unmeasured or non-positive expectancy implies an UNBOUNDED no-trade band, not a default
  one.** `2C/mu` diverges as mu → 0 and is meaningless below it: if the desk has no measured edge on a name,
  rebalancing that name is *never* worth its cost. Falling back to the narrow convention is the
  churn-permissive direction and is backwards. When a formula's denominator is unmeasured, ask which way the
  economics point before picking the fallback — don't default to the tighter one.
- **Rule 150 — measure churn as gross SHARES TRADED vs SHARES HELD, not as turnover in dollars.** Turnover
  alone can't distinguish one-way convergence from round-tripping. `turnover_cost_by_name.qty` against
  `fusion_targets.currentQty` settles it: JNJ traded **299** to hold **13**, AAPL **197** to hold **12**,
  NVDA **263** over **119** fills. That is round-tripping, and it renamed the defect away from "the re-plan
  doesn't net against the working slice" (Rule 144) — the slices net fine; the *aim* flips.
- **Rule 151 — the aim is redrawn ~120× per e-folding time, and that ratio is the churn.** ADR-0080 derives
  `adjustment-rate` = 1 − exp(−30/3600) = **0.0082987…** (a 3600s time constant) while the fusion loop
  re-plans every **30s** on a book whose dominant source is `reversion` at weight **1.5417176854024859**
  against `trend` **0.3723889694091483**. AAPL sits at `currentQty` **−12.0** against `targetQty`
  **−153.110674** — it never arrives, and pays a round trip each time the mean-reverting aim flips. Compare
  the re-plan cadence to the convergence time constant before blaming the executor.
- **Rule 152 — check for a JVM restart inside the window before attributing anything.** This window contains
  a boot at **13:34:34** local, and PnL **+$16.33** / gross **−$18,702.44** looks like a de-risking result.
  It isn't attributable: a revert that opens and closes nothing cannot have produced it, and a restart
  rebuilds the book from warm state. Say "not separable" (Rule 141) rather than crediting the revert.
- **Rule 153 — MACRO is losing directionally, and the churn fix cannot touch it.** `/api/attribution` reads
  book MACRO `totalPnl` **−$35.82347655**, all realized, on `feesPaid` of just **$0.169833** — the largest
  single negative line inside `strategyAlpha` **−$27.20516312**, with essentially no turnover. A cost fix
  aimed at ALPHA will leave it intact; it needs its own cycle and its own trigger-level post-mortem.

## 2026-07-30 18:30Z — the revert holds on a fourth JVM; ALPHA's cost ratio is now a four-run trend

- **Rule 154 — a monotone four-point sequence upgrades a diagnosis to a trend; re-rank on it.** ALPHA's
  `/api/attribution` pair moved the wrong way on both legs four runs running — `totalPnl`/`feesPaid`
  **$23.36357064**/**$56.444977** → **$9.88820735**/**$60.552698** → **$8.61831343**/**$66.073455** →
  **$7.64428496**/**$73.682486**. PnL down and fees up on every step is not noise. A defect that is merely
  *present* can wait behind a bigger one; a defect that is *compounding* cannot. Track the pair, not the fee.
- **Rule 155 — the convergence gap and the step size sit in the SAME `fusion_targets` row; read them
  together before blaming the executor.** KO `currentQty` **−69.0**, `targetQty` **−412.71**, `deltaQty`
  **−1.915`; JNJ **−14.0** / **−146.26** / **−2.745**; NVDA **10.0** / **124.21** / **2.795**. The step is
  ADR-0080's 3600s e-folding rate; the aim is redrawn every 30s under `reversion` weight
  **1.6024876487480502** vs `trend` **0.42024570127205746**. A desk that never arrives pays a round trip
  per flip — that one row is the whole churn story, no cross-referencing needed.
- **Rule 156 — net exposure can swing sign hard while gross barely moves; read both, and read the headroom
  before calling it.** Gross moved **-1385.11** to **$36625.58** while net went to **$-13291.73** — a
  reversion-dominated aim (KO **−412.71**, NEE **−351.01**, JNJ **−146.26**) pulling the book short. At
  **1.3%** of the $1,000,000 net cap with no flag set, that is a read, not a danger (Rule 147 again).
- **Rule 157 — a frozen loss ranks below a compounding one.** MACRO `totalPnl` **−$35.82347655** on
  `feesPaid` **$0.169833** is bit-identical to last run: the book is not trading, so the loss is a closed
  directional position that is not growing. It stays item #2 behind ALPHA's churn for exactly that reason —
  "largest single negative line" is not the same as "most costly going forward".
- **Rule 158 — prove "no name warmed twice" by count, not by inspecting the late lines.** Keying warm lines
  on lifecycle+name, every per-name entry is count **1**; the only count>1 is `fusion covariance warmed 19
  of …`, the ADR-0089 rolling matrix rebuild. That is unfalsifiable by clock skew and far cheaper than
  reading each post-boot line — which is what the previous three cycles did.

## 2026-07-30 19:00Z — the revert holds on a fifth JVM; I re-tested my own four-run diagnosis and it was wrong

- **Rule 159 — key a "proved by count" check on the FULL discriminator, or the count proves nothing.**
  Rule 158 said prove no-double-warm by count. A pattern keyed only on the word *before* `sensor` reports
  **14** false positives at count 2 (AAPL, AMZN, BAC, GOOG, JNJ, KO, MSFT, NEE, NVDA, PFE, PG, UNH, WMT,
  XOM) because it collapses `CrossSectionalReversionLifecycle : cross-sectional reversion sensor warmed X`
  with `ReversionForecastLifecycle : reversion sensor warmed X`. Keyed on the full lifecycle class, count>1
  is empty. A counting proof is only as strong as its key — print the key before trusting the count.
- **Rule 160 — four monotone points are NOT a trend on this book; Rule 154 is retracted.** Rule 154
  upgraded ALPHA's cost ratio to a four-run trend and re-ranked on it. It broke on the very next read:
  `totalPnl` went **$7.64428496** → **$15.27790493** → **$17.08569992** while only `feesPaid` stayed
  monotone (**$73.682486** → **$78.463912** → **$79.469996**). Track the two legs separately and require
  the *ratio* to move, not one leg — a rising-fee/rising-PnL book is not the same defect as rising-fee/
  falling-PnL, and conflating them is how a blip gets promoted to a trend.
- **Rule 161 — read the counter that answers the question before asserting a mechanism is inert.** I called
  the ADR-0094 no-trade band inert for four consecutive runs from indirect evidence. `/api/fusion/targets`
  exposes `insideBuffer` — incremented in `PositionBuffer.apply` exactly when a planned delta is zero — and
  it reads **13** of **20**. The band was suppressing most of the book the whole time. When a component has
  its own telemetry, absence of proof is not proof of absence.
- **Rule 162 — "fall back to the convention when unmeasured" is the invariant, not a bug; do not widen it.**
  I had queued a fix to widen `PositionBuffer.widthFor`'s fallback because unmeasured μ makes `2C/μ`
  unbounded. But **0.10 is Carver's cited convention for precisely the desk that has not measured its
  edge**, and the method's contract says that with no measurement there is no claim to make. Widening it
  would author a number that gates money — invariant 7 / ADR-0016. Before "fixing" a conservative fallback,
  check whether the fallback IS the provenance.
- **Rule 163 — sample the target book across consecutive re-plans before blaming the executor or the
  buffer.** Across `atMillis` **1785438150584** / **1785438180812** / **1785438210932** (30s apart) the
  targets re-randomise rather than drift: AAPL **-10.75** → **+129.53** → **+67.19**, HD **-1.48** →
  **+42.78** → **+1.39**, NVDA **-0.08** → **-50.91**, KO **-324.08** → **-45.38**, GOOG flips sign twice.
  The buffer's band is `|target|·TARGET_ABS/|forecast|`, so it swings *with* the target it filters. Under
  `reversion` **1.5810674747889952** + `xsreversion` **0.9850309963910409** vs `trend`
  **0.39443196542948245**, and `edgeGated` reading `no positive OOS edge` on every listed name, the churn is
  a SIGNAL-stability problem upstream of every execution dial. Three cheap endpoint reads beat four cycles
  of inference — take them first.

## 2026-07-30 19:30Z — the buffer was steering wrong-side positions to a destination that is also wrong-side (ADR-0132)

- **Rule 164 — recompute the component's published arithmetic before theorising about it.** Four cycles of
  inference produced three different wrong diagnoses of the same symptom (band inert → ADR-0101 fallback
  inverted → targets re-randomise). `/api/fusion/targets` publishes `aims` alongside `targetQty`,
  `currentQty` and `combinedForecast` — enough to recompute the whole ADR-0094/0101/0102 chain. Done once,
  it reproduced the published `deltaQty` **exactly on all 13 planned names** (GOOG
  `6.043466 × 0.008298707 = 0.050153`, AMZN `22.379233 × 0.008298707 = 0.185720`) and the defect was
  visible in the intermediate value nobody had printed: the DESTINATION, `held + delta`. An exact
  reproduction of a component's output is a stronger instrument than any number of samples of its input.
- **Rule 165 — bounding the INTENT does not bound where the desk STOPS.** ADR-0102 clamps the aim into
  [flat, target]. But ADR-0094 trades to the near EDGE of the no-trade region, a whole band below the aim,
  and the band is scaled by the average position at the TARGET — so early on ADR-0080's hour-long aim path
  the band exceeds the aim and the region straddles flat. GOOG's destination computed to **-2.956534**
  against a target of **+32.814821**. When a policy clamps an intermediate quantity, check every quantity
  DOWNSTREAM of it that inherits none of the clamp.
- **Rule 166 — a fix scoped to a branch is worth nothing if the shipped config never enters that branch.**
  ADR-0118 diagnosed this identical AAPL plan (short 1 vs target +6.031064) and fixed it — inside
  `!mayIncrease`. With `jethro.fusion.edge-gate.enabled=false` (ADR-0122) and the ADR-0126 σ sensors warm,
  that branch never runs, so the remedy has been dead code and its own test pinned the live-configuration
  case at `deltaQty` 0 as correct. When writing or reading a fix, state which config reaches it — and when
  a test asserts "0" for a state an ADR calls a defect, that test is pinning the bug.
- **Rule 167 — "same six names, unchanged, across N re-plans" is the signal; the targets moving is not.**
  Rule 163 read sign flips in `targetQty` and concluded the book re-randomises. Re-sampled, the targets
  drift (MSFT **-63.86 → -51.60 → -57.99**). What was actually invariant across every sample was
  `currentQty`: GOOG **-9.00**, AAPL **-13.00**, NVDA **-16.00**, KO **+45.00** in all three. Look for what
  does NOT move between samples — a frozen position is a defect; a moving target is a forecast.

## 2026-07-31 13:30Z — the no-trade band was wider than the whole position it policed (ADR-0133)

- **Rule 168 — when two quantities are compared, check that they SCALE the same way with the thing they
  both depend on.** The ADR-0094 band is `width × |target| × TARGET_ABS / |forecast|`, and the target is
  linear in the forecast, so the two `|forecast|` factors cancel: the band is the position at a
  full-strength view **regardless of the current view**. The gap it is tested against lives inside
  ADR-0102's interval `[flat, target]`, whose width **does** shrink with conviction. Neither ADR is wrong
  alone; the ratio `band/|target| = width × TARGET_ABS / |forecast|` is what breaks, and past
  `|forecast| < width × TARGET_ABS` the band swallows the whole interval and the delta is zero **forever**.
  Live: 6 of 20 names permanently vetoed, `insideBuffer` 19/18/20/20 across four re-plans, the desk holding
  $14,215 of its own $303,271 target book while flagged DORMANT. Write the ratio out; don't eyeball the
  two expressions.
- **Rule 169 — a "DORMANT" header snapshotted at the open is the overnight freeze, not a verdict.** The
  report was generated at `atMillis` 1785504593354, seven seconds BEFORE the 13:30Z open, after three
  `market-closed` heartbeats. Gross $0.00 and PnL +0.00 over three runs were both artefacts of the
  timestamp. Three live endpoint reads after the open showed the book moving. Check the snapshot time
  against the session before reading anything into a flat header — and re-read the endpoint yourself.
- **Rule 170 — the fix that matches the cited rule is not automatically the fix to ship.** The class cites
  Carver, whose buffer is centred on the TARGET; I restructured `bufferedDelta` to match and it broke two
  paid-for lessons at once — ADR-0107's rated view-change unwind (it dumped a full 104-share holding where
  the rule demands a rated step) and ADR-0090's unrated same-side de-risking. Reverted, and shipped a
  one-`min` cap on the band's scale instead: same defect closed, every other semantic byte-identical. When
  a restructure lights up tests that encode past incidents, the tests are usually right — narrow the fix
  to the proven defect rather than re-deriving the component from first principles.
- **Rule 171 — cross-check a component's expected values with an INDEPENDENT implementation of its
  documented formula, not by running the component.** Six tests needed new band arithmetic. I re-derived
  all of them in a separate exact-decimal script written from the ADR's formula; it reproduced the Java
  output digit for digit (3.281482, 1.812000, 0.603106, 103.757400, −27.803973). That makes the test a
  real check rather than a transcription of whatever the code now happens to emit.

## 2026-07-31 14:00Z — the book is liquidated at every boot, and the σ-cold veto blocks only the rebuild (no change; ADR-0133 at 1/6)

- **Rule 172 — before spending a change on a gate, confirm the live book actually REACHES it.** Two cycles
  running I fixed the position buffer — ADR-0132's destination clamp, ADR-0133's band cap — and both were
  graded UNVERIFIABLE, for the same structural reason: `mayIncrease` is false for every name (σ-cold), so
  the reduce-only branch re-seeds `aim` to `held + delta` = 0 *before* either fix is consulted. With aim 0
  and `currentQty` 0 the gap is zero, so the band is never evaluated. Live: aims **all exactly 0.0**,
  `insideBuffer` 19/19, 19/19, 18/18, 19/19 across four re-plans. Walk the gate ORDER from the delta
  backwards and find the first one that zeroes the input; fix that one, not the prettiest one.
- **Rule 173 — `insideBuffer` at the full name count does NOT mean the buffer vetoed anything.** It counts
  `delta.signum() == 0`, which is equally true when the gap is zero because nothing is held and nothing is
  intended. Read `aims` alongside it: a *vetoed* trade has a non-zero aim, a *vacuous* one has aim 0.0. I
  read this field as a veto last cycle and it was an empty gap.
- **Rule 174 — an unarmed risk sensor must not be allowed to LIQUIDATE, only to block opening.** ADR-0126's
  veto is asymmetric in the damaging direction. At boot the σ sensor seeded 38–99 of the **121** prices it
  needs (`vol-span=120`) and logged `still cold` for **18** names; every name went reduce-only, the desk
  worked its wrong-side holdings out **in full** (five FILLED orders 19 seconds after start: JPM SELL 4,
  AAPL SELL 9, AMZN BUY 17, GOOG BUY 7, MSFT BUY 7) and gross went **$17,942.68 → $0.00**. Then it could not
  rebuild: `streamVolMeasuredNames` **0, 0, 0** for ten minutes, ticking to **1** at ~10.5 minutes — and
  exactly one aim unfroze with it. The loop reboots about every 30 minutes and the slowest name (MCD, 38 of
  121) needs ~83 more samples, so the book is destroyed every cycle and only partly rebuilt.
- **Rule 175 — a graded-BAD fix rules out the REMEDY, not the DEFECT.** ADR-0131 attacked this same root
  cause by re-seeding the cold sensor on its warm-up cadence and scored ❌ BAD; it was reverted. That does
  not make the boot liquidation acceptable — it means the next attempt must pick a different lever (here:
  suppress the boot flatten, rather than accelerate the warm-up). Don't let a reverted remedy quietly
  retire the problem it failed to solve.

## 2026-07-31 14:30Z — the σ seed IS converging, just slower than the reboot cadence; the whole firm book is one name (no change; ADR-0133 at 2/6)

- **Rule 176 — a "permanent floor" claim needs two boots to support it; check the previous boot log before
  calling a warm-up stuck.** Last cycle I read the σ seed's 38–99 of **121** as a structural ceiling. It is
  not: every name improved against the previous process — MCD **38 → 57**, KO **43 → 64**, WMT **43 → 64**,
  BAC **41 → 62**, NEE **41 → 62**, GOOG **46 → 67**, NVDA **49 → 71**, MSFT **61 → 83**, AMZN **80 → 101**,
  AAPL **99 → 103**. The durable mark store accumulates; it just gains ~20 prices per ~35 minutes of open
  market while the loop reboots every ~30. The defect is a *rate* mismatch, not a floor — and the fix that
  follows from a rate is different from the one that follows from a floor.
- **Rule 177 — "the sensor will warm up" is not a plan when the seed runs once per process.**
  `FusionLifecycle.seedVolatility` guards on `volSeeded.add(instrument)`, so after the single boot seed the
  only further warming inside a process is live prints at the 30s re-plan cadence. Live:
  `streamVolMeasuredNames` crawled **0 → 1 → 4** over ~22 minutes against `volBudgetNames` **19**, and the
  process is torn down at ~30. The desk lives its whole life holding only its fastest-printing names.
- **Rule 178 — read the frozen names' TARGETS, not just the count, to price what a veto costs.** **18 of 21**
  aims were exactly **0.0** while the targets behind them were PFE **2848.93**, NEE **-1250.11**, WMT
  **878.21**, BAC **-765.91**, NVDA **-578.31**. Gross was **$906.46500000** — three shares of AAPL — against
  **$1,499,091** of headroom. The count says "most names frozen"; the targets say "the desk's whole intended
  book is frozen", which is what makes this item #1.
- **Rule 179 — re-grade a change once its inputs stop being degenerate; UNVERIFIABLE is a state, not a
  verdict.** ADR-0133 read vacuous last cycle (every aim 0.0, zero gap, band never consulted). This cycle
  three aims went non-zero — AAPL **-21.458419**, MSFT **5.806959**, AMZN **-6.428999** — and AAPL's
  `deltaQty` **-15.939691** against `currentQty` **-3.0** is strictly narrower than its aim-to-holding
  distance, so the band is demonstrably being evaluated. Same commit, same tests, new verdict, because the
  book finally reached the code. Carry an unverifiable item forward and re-test it rather than closing it.

## 2026-07-31 15:05Z — the σ freeze cleared by itself and the book deployed; that revealed the hedge sizing on 8 of 28 names (no change; ADR-0133 at 3/6)

- **Rule 180 — a "hedged" status is a claim about COVERAGE, not just about sizing; make it prove what it
  could see.** `/api/hedging` reported `status` **ON-TARGET**, `tier` **STRUCTURAL**, against
  `netExposureUsd` **-60979.73** with `rawTargetNotionalUsd` only **18860.33** — a ~31% hedge presented as
  complete. `HedgeMath.structuralBetaHedge` skips any name with no assigned beta (its own test
  `structuralBetaHedgeSkipsNamesWithNoAssignedBeta` asserts this), and the live `instrument_attributes` has
  `hedge_beta` for only **8 of 28** equities — AAPL 1.25, NVDA 1.75, AMZN 1.20, JPM 1.10, MSFT 1.10,
  GOOG 1.05, SAP 1.00, JNJ 0.55, the original sim-era universe. **-$38,897 of -$60,974 net equity (63.8%)**
  is invisible to the hedge, **XOM's $24,442 — 28% of firm gross — included**. A missing input must degrade
  the STATUS, never silently shrink the TARGET.
- **Rule 181 — reconcile a suspected mechanism against the app's OWN published number before ranking it.**
  Recomputing `Σ βᵢ·Eᵢ` from live positions × live betas gave **-18,856.99** against the hedger's published
  `rawTargetNotionalUsd` **18,860.33**. That tie is what turns "probably the betas" into item #1; a
  hypothesis that cannot be tied to a number the app already prints is not ready to spend a cycle on.
- **Rule 182 — check a warm-up item again before acting on it: it may have closed itself.** Item #1 for
  three cycles was the ADR-0126 σ-cold freeze. This boot logged `still cold` at **103–117 of 121** stored
  prices vs **38–99** one boot earlier, the veto lifted, and the desk went from **3 shares of AAPL** to
  **21 positions** / gross **$76,657.72 → $86,777.90** with no code change at all. Downgrade, don't close:
  the store crossed the span by accumulation, so a weekend or outage gap re-freezes it.
- **Rule 183 — a first read of a warming subsystem is not evidence; re-read it before writing it down.** At
  15:03 the hedge axis read `status` **WARMING**, `covarianceReady` **false**, "no tradable proxy can be
  sized yet (price/covariance/betas missing)", and ES was absent from `/api/marks` — which looked like an
  unpriceable-proxy bug. Two minutes later the same axis read **ON-TARGET/STRUCTURAL** holding **0.050468**
  ES. The transient would have become a wrong item #1 and a wasted cycle.

## 2026-07-31 15:40Z — the hedge's covered subset went sign-inverted against the book, and a dead proxy price is the only thing not trading it (no change; ADR-0133 at 4/6)

- **Rule 184 — a partial risk sample has an unbounded SIGN error, not just a magnitude error; test the sign
  before you rank it as "under-coverage".** Last cycle `Σ βᵢ·Eᵢ` was **-18,856.99** against net equity
  **-60,974** — under-sized but correctly signed, which read as a coverage gap. This cycle the same sum is
  **+5,657.02** against net equity **-38,817.87**: NVDA's **+10,371.04** at beta **1.75** (**+18,149**
  alone) dominates the eight covered names and flips the subset positive. `HedgeMath.structuralBetaHedge`
  sets `hedgeNotional = systematic.negate()`, so the structural tier would **SELL** the proxy against an
  already net-short desk. Beta-covered net **-11,126.16**, uncovered **-27,691.71 (71.3%)**. Escalate the
  item; do not just re-state it.
- **Rule 185 — when two defects interact, RANK BY THE ORDER THAT IS SAFE TO FIX, not by size alone.** The
  proxy having no price (item #2) is the only reason the wrong-signed target is not being traded — the axis
  reads WARMING and sizes nothing. Fixing the cheaper, more obvious defect first would convert a passive
  hedging gap into an active anti-hedge. Sequencing is part of the diagnosis, so write it into the register.
- **Rule 186 — discharge Rule 183 with a MONOTONIC series, not a second look.** ES's
  `providerTimestampMillis` was **frozen at 1785509694000** across three polls 30 s apart — age
  **2477.5 s → 2507.5 s → 2537.7 s**, past 42 minutes — while AAPL ticked at **0.4/1.7/1.1 s** and NQ
  advanced **1785511513000 → 1785511603000**. Six consecutive `/api/hedging` reads over two minutes all
  said WARMING. One re-read can still catch a transient at the wrong moment; a climbing age on a frozen
  timestamp cannot.
- **Rule 187 — an unpriceable position silently leaves the firm total the loop optimizes.** The HEDGE book
  holds ES `quantity` **0.022800** with `hasMark` **false**, `mark` **0.00000000**, `netExposure`
  **0.00000000**, `grossExposure` **0.00000000**. CLAUDE.md defines "total" as the whole book *including*
  the hedge, so headline gross **$77,739.62** understates money at risk by the proxy's notional. Check
  `hasMark` on every held name before trusting a firm exposure total.
- **Rule 188 — separate the tape from the till before blaming a mechanism.** PnL fell **-85.28** run-over-run
  with `unrealizedPnl` **-225.80426780** against `realizedPnl` **796.09988240** on a net-short book into a
  rising tape: that is **market**. The mechanism-attributable cost is turnover — ~**$1.6M** traded notional
  on a **$77.7k** book, `fees` **105.584233 → 129.292241**, FILLED **4495** / CANCELLED **1509** with every
  cancel reasoned *"fusion re-plan — passive order superseded by a fresh target (ADR-0084)"*.

## 2026-07-31 16:05Z — the sign inversion un-flipped with nothing fixed, while coverage got worse (no change; ADR-0133 at 5/6)

- **Rule 189 — a partial-sample sign error can self-clear WITHOUT a fix; track coverage, never sign, as the
  invariant.** Last cycle `Σ βᵢ·Eᵢ` was **+5,657.02** against a negative book and I ranked the *inversion* as
  the headline. This cycle it reads **-5,786.42** against net equity **-89,268.235** — correctly signed,
  nothing changed. The entire difference is NVDA's net going **+10,371.04 → -393.20**: one name at beta
  **1.75** was carrying the inversion. Meanwhile the real defect worsened — uncovered net
  **-83,809.485 = 93.9%** of |net| vs **71.3%** last cycle, so the hedge sizes off ~**6.5%** of the
  systematic risk. Had I written the item as "sign inverted", this cycle would have read as a fix and closed
  it. Write the defect as the measurable that cannot flip by luck.
- **Rule 190 — the report's Postgres ERROR log contains the LOOP'S OWN failed psql queries; check the SQL
  exists in app code before ranking it.** `column "hedge_beta" does not exist` (15:30:46Z) looked like a
  live app defect gating the betas. It is a past cycle's ad-hoc query against an EAV table
  (`instrument_attributes` is `instrument_id | name | value`), sibling to `relation "instrument_attribute"
  does not exist` (15:06:33Z) and `column a.attr_key does not exist` (15:06:40Z). `grep -rn hedge_beta
  --include=*.java --include=*.sql` hits only `backups/*.sql`. One grep separates a defect from my own shell
  history.
- **Rule 191 — "stale" and "absent" are different failures, and a VERIFY-BY written for one cannot grade the
  other.** Last cycle's item #2 VERIFY-BY was "ES's `providerTimestampMillis` advances between two polls".
  This cycle ES has **no row at all** in `/api/marks` (**36** marks returned, no ES), so that test is
  unrunnable — not passing, not failing. State existence before freshness in any VERIFY-BY on a feed.
- **Rule 192 — before blaming refdata for an unpriceable instrument, check the config already has a
  fallback.** ES and NQ both carry `yahoo` symbology (**ES=F**, **NQ=F**) and multipliers (**50**, **20**),
  and `jethro.hedge.equity-proxy-candidates=ES,NQ` already lists a fallback that `HedgeAdvisor.candidates()`
  filters by live price. NQ **is** priced (**1785512939000**) yet the axis still pins `proxyId` **ES** at
  `status` **WARMING** with `covarianceReady` **false**. The gap is in the selection path or the covariance
  gate, not the reference data — a wrong diagnosis here would have shipped a pointless migration.

## 2026-07-31 16:35Z — ADR-0133 graded BAD; the scorer's revert had failed and the rejected code was still trading

- **Rule 193 — the scorer's auto-revert CANNOT survive a conflict, and it is guaranteed to hit one; check
  that a ❌ BAD revert actually LANDED before trusting the ledger.** `git revert e61c7f5aa` conflicts on
  `docs/loop-findings.md`, `reports/last-analysis.md` and `reports/must-fix.md` — the loop's own memory
  files, rewritten every cycle — while the three code hunks apply cleanly. Because the loop commits its
  analysis *into the same commit as its code*, every revert attempted one cycle later is structurally
  guaranteed to conflict and abort, leaving the graded-bad **code** live. Three occurrences now
  (`efccc6502`, `e61c7f5aa`, plus a `revert-failed` heartbeat). A ❌ BAD verdict is decorative unless a
  later cycle confirms the mechanism left the running code. **Resolution that works:** `git revert
  --no-commit`, then `git checkout --ours` the memory files and keep the ADR (annotated
  `Status: Reverted`) — revert the code, never the memory.
- **Rule 194 — a correct derivation can still be the wrong change; "the desk is DORMANT" is not by itself
  a reason to widen what may trade.** ADR-0133's arithmetic was right and its prediction came true: the
  band was scaled to a full-conviction position while the interval it was tested inside shrank with
  conviction, six names were permanently vetoed, and capping it unstuck them — gross went
  **17,957 → 167,401**. It still scored ❌ BAD, PnL **-$495.57**. The buffer was not strangling a
  profitable book; it was incidentally suppressing turnover on names with **no measured out-of-sample
  edge**. Removing the suppression bought spread, not return. Before unsticking a name, ask whether that
  name has edge to capture — the execution dial is downstream of that question, never a substitute for it.
- **Rule 195 — when an execution dial turns out to be enforcing a conviction floor, the floor is the
  mis-set thing, not the dial.** The band was doing `min-forecast-to-route`'s job by accident. The fix is
  never to widen the accidental gate; it is to set the deliberate one (the ADR-0049/0059/0064 edge gate)
  where it belongs. This is the standing priority restated with a measurement behind it: work on edge, not
  the combiner or its execution dials.
- **Rule 196 — separate the realized leg from the unrealized leg to split mechanism from market.** This
  cycle `unrealizedPnl` **-210.25727251** on a near-flat-net book (**+2,801.04706250**) is the tape; the
  **realized** leg carries the change's cost, because a ~9x gross put on against no edge pays spread on
  every name it opens. Unrealized ≈ market, realized ≈ what your mechanism actually did.

## 2026-07-31 17:00Z — the manual revert verified out of the code; the hold bought the reading that killed the next change

- **Rule 197 — before "fixing" a gate the desk appears to be ignoring, check whether it is switched off ON
  PURPOSE, and check what arming it would leave tradable.** The selector reports `measured` **31**,
  `tradable` **15**, `edgeGated` **16**, while `/api/fusion/targets` routes non-zero deltas in gated names
  (CVX **0.207468**, GOOG **4.873257**) and holds GOOG **-35**, NVDA **-27**, CVX **-25**. That looks like
  a leak worth closing against `totalFees` **229.638225** on a `firmTotal` of **293.55386805**. It is not:
  the ADR-0049/0059 veto exists at `FusionExecutor.java:139`, is already reduce-only-exempt, and is
  deliberately disabled by `application.properties:414` `require-backtest-support=false` and `:401`
  `edge-gate.enabled=false` under ADR-0122's exploration mode. **Arming it today leaves ONE tradable
  name** — of the 13 supported names carrying a target, only JPM (`combinedForecast` **5.921**) clears
  `min-forecast-to-route=5.0`. That is the DORMANT state ADR-0122 was written to escape. A config flag
  with an ADR behind it is a decision, not a bug; read the ADR and count the survivors first.
- **Rule 198 — the desk's conviction is concentrated in exactly the names its own OOS backtest rejects,
  and that is a verdict on the SOURCES, not the combiner.** Besides JPM, the only names clearing the 5.0
  floor are both edge-gated: MSFT **5.4686**, CAT **-5.2810**. A forecast stack that loads onto names
  whose measured history says the algos lose there cannot be repaired by moving the gate or reweighting
  the fusion — this is the standing "work on EDGE" priority with a mechanism attached. The fix is a new
  OOS-validated predictor taken through the ADR-0049 gate; every dial downstream of that is noise.
- **Rule 199 — a ❌ BAD revert's honest credit is the EXPOSURE it removed, not the PnL that happened
  alongside it.** `4f67f0515` verified out (`uptimeSeconds` **1431** postdates it; `band(...)` back to
  `scale × width`; ADR-0133 `Status: Reverted`), gross **$167,400.77 → 113683.50400000**, `totalPnl`
  **$228.93 → 301.79886805**. But applying Rule 196: `unrealizedPnl` **-210.25727251 → -7.47426487** on a
  net that moved **+2,801.04706250 → -33,407.76400000** is mark-to-market, and the mechanism's own
  realized leg went the *other* way, **402.49713476 → 309.27313292**. Claim the gross; do not claim the
  headline.
- **Rule 200 — the ADR-0116 hold is productive time, not dead time.** With the pending change at
  **1/6 cycles** the contract forbids a code change, so the cycle went into read-only verification. That
  is what caught Rule 197 before it shipped as a "fix". Spend a hold cycle reading the code behind the
  next candidate, not waiting for the window to close.
- **Rule 201 — the scorer's revert defect is not fixed; only its symptom was.** Last cycle reverted the
  live BAD code by hand, but `scripts/score-change.py:357` still calls plain `git("revert", "--no-edit",
  sha)` and aborts at `:359`. The pending change is *itself* a revert, so a ❌ BAD verdict on it hits the
  same guaranteed conflict. Fixing a symptom under time pressure leaves an item OPEN — rank the cause,
  and write the VERIFY-BY against the scorer's stdout and the snapshot's `revertApplied`, not against the
  code that happened to get reverted this once.

## 2026-07-31 17:30Z — the redeploy is a trading strategy nobody wrote: 3.09x turnover per boot, for no change of view

- **Rule 202 — the loop's own 30-minute restart liquidates and re-buys the names whose sensors cold-start,
  and it is the largest non-market cost on the book.** Grouping today's `fills` by minute, with each of the
  8 post-13:50Z heartbeats treated as a boot (window `heartbeat…+2min`): post-boot minutes did
  **$277,330.06** over 11 minutes (**$25,211.82/min**) against **$1,144,024.38** over 140 other minutes
  (**$8,171.60/min**) — **3.09x**, and **9.9%** of fills carrying **19.5%** of turnover, so the post-boot
  orders are *large* (whole-position liquidations, not the ADR-0080 incremental path). The 17:12 minute,
  **39 seconds after boot**, alone did **$81,499.01** of turnover against a whole-book gross of
  **$81,647.97**. Boot times are set by the loop and are uncorrelated with the tape: none of this is a
  response to the market.
- **Rule 203 — three individually-correct decisions compose into an unbuffered liquidation; check the
  COMPOSITION, not each ADR.** `FusionPlanner.plan` (ADR-0065): a held name with no fresh view gets an
  *implicit target of zero*. `PositionBuffer` (ADR-0090): a flat target snaps the aim to zero and **an exit
  is not buffered**. Sensors cold-start on every boot (this run: trend still cold for UNH 143/193, EURUSD
  17/193, META 17/193, GOOGL 36/193). Chain them and *"my sensor hasn't finished re-seeding"* is executed as
  *"the desk wants to be flat"* — sold unbuffered, bought back when it warms. ADR-0065 cannot tell **no
  view** from **not yet warm**; the loop guarantees the latter every 30 minutes. Observed: CAT SELL 12 at
  17:11:30 → BUY 13 at 17:12:58, a reversal in 88 seconds across the reboot.
- **Rule 204 — when the realized leg falls by LESS than the fee bill rises, the loss is cost, not
  direction.** 17:00Z→17:31Z: `realizedPnl` **309.27313292 → 283.29807851** while `totalFees`
  **229.638225 → 248.070383**. That is the churn thesis quantified, and it is why `totalFees`
  **248.070383** now exceeds `firmTotal` **118.52242327** (ALPHA: `feesPaid` **241.749416** on `totalPnl`
  **-5.84496252**). Read the two series together before blaming a signal.
- **Rule 205 — the combiner is exonerated by its own weights; stop suspecting it.** Fusion already leans on
  the only two positive-expectancy sources — reversion **1.6796828791749714** (`avgReturnBps`
  **+4.874804359460039**, 63 cohorts) and social **1.5894668077730054** (**+6.22893692818407**, 21) — and
  holds down trend **0.6063985334926396** (**-1.884262124055958**) and xsreversion **0.35311503241404785**
  (**-6.858218349322071**). When the weighting is already correct and the book still loses, the loss is
  upstream (edge) or downstream (cost). This cycle it was downstream.
- **Rule 206 — Rule 200 paid out twice.** A second consecutive hold cycle spent on measurement produced the
  most concrete lever the register has carried in days. A hold is when to run the query you never have time
  for on a change cycle — the SQL grouping above took one query and replaced two cycles of "worth a
  targeted read" hand-waving at item #4.

## 2026-07-31 18:00Z — the book is not "churning": it is **fully liquidated and rebuilt from flat every loop cycle**, and the round-trip cadence is shorter than the horizon the edge is measured over

- **Rule 207 — the desk goes EXACTLY FLAT at every loop teardown; Rule 202's "3.09x churn" understated it.**
  Boot is `2026-07-31T17:39:03Z` (`/api/ops/jvm` `uptimeSeconds` **1283** against `/api/risk` `asOfMillis`
  **1785520826830**). Summing every LIVE fill executed *before* that instant, the ALPHA book nets
  **0.000000** across **21** names over **1687** fills — not "mostly reduced", **exactly flat**. The
  minute `17:38` — 2 seconds after the `2026-07-31T17:38:42Z` heartbeat and 19 seconds before the boot —
  did **11** fills and **$99,922.58** of turnover against the heartbeat's recorded gross
  **99920.63500000**: a round trip of ~100% of the book, in one 500ms burst. The new process then rebuilt
  from flat to gross **16111.97500000** by 18:00Z and **19327.75000000** by 18:02Z. One cycle earlier the
  same signature: minute `17:12`, **$81,499.01** turnover against a whole-book gross of **81647.96500000**.
- **Rule 208 — the forced flatten happens at TEARDOWN, not at boot, so cold sensors are the sequel, not the
  cause.** The 17:38 liquidation is 26 minutes into a healthy process, not 40 seconds into a fresh one. No
  `@PreDestroy` / shutdown-flatten hook exists in the source (grep for `PreDestroy|shutdownHook|flattenAll|
  liquidateAll|closeAllPositions` outside tests returns nothing). So the flatten is a *normal fusion
  re-plan* that took every name to zero at once — and next cycle's diagnosis must find what collapses all
  21 targets simultaneously, rather than re-explaining the cold-start rebuy that follows it.
- **Rule 209 — measure the whole-book round trip against the SIGNAL HORIZON, not just against the fee
  bill.** Every source in `/api/signals/telemetry` publishes `horizonSeconds` **3600**, while the loop
  round-trips the entire book every ~30 minutes. The desk is structurally incapable of holding a position
  as long as the horizon over which its own expectancy — reversion **+4.851871026828735** (63 cohorts),
  social **+5.298898880312067** (22) — is measured. No amount of sizing, fusion or hedge work can realise
  an edge the holding period is cut in half before it pays. This outranks the fee argument.
- **Rule 210 — reconcile the projection against `fills` BEFORE concluding "state was lost on restart".**
  The $99.9k→$16.1k collapse looks exactly like a projection wiped by a reboot. It is not: at one instant
  `/api/risk` `positions` and `sum(BUY − SELL)` over `fills` agree to the share on all five holdings
  (MSFT **20.000000**, PFE **-221.000000**, BAC **68.000000**, GOOG **1.000000**, ES **-0.053455**).
  Invariant 3 is intact and the liquidation was real trading. One query separated a data bug from a
  trading bug; run it first next time.
- **Rule 211 — a full re-seed does NOT warm a sensor; warm-up needs elapsed time, so it cannot outrun a
  30-minute process.** `ReversionForecastLifecycle` logs *"still cold for USD.SOFR.10Y after seeding
  **241 of 241** stored prices"* — the store was drained completely and the sensor stayed cold. 21 minutes
  after boot, trend is still **49 of 193** for TSLA and **1 of 193** for GOOGL. Any future fix that
  proposes "seed harder / seed more often" is therefore already refuted — the constraint is wall-clock
  warm-up against process lifetime, not store coverage.
- **Rule 212 — item #4's hedge is downstream of item #1, not an independent defect.** `/api/hedging`
  `covarianceReady` is **false** with the EQUITY axis `WARMING` and `targetProxyQty` **null** for a fourth
  consecutive cycle, and `netExposureUsd` flipped **-7368.29 → 8243.99** between cycles. A covariance
  estimate cannot converge on a book that is destroyed and re-drawn every 30 minutes. Do not spend a
  change on the hedge until the holding period is fixed.

## 2026-07-31 18:30Z — a finding I had to RETRACT, and the telemetry gap that let me publish it in the first place

- **Rule 213 — Rule 207 is WRONG and is hereby retracted: the desk does NOT flatten at every teardown.**
  Summing every LIVE ALPHA fill executed before the **2026-07-31T18:09:08Z** boot (`/api/ops/jvm`
  `uptimeSeconds` **1279** against `/api/risk` `asOfMillis` **1785522627137**), six names carry across
  the reboot: BAC **68.000000**, GOOG **1.000000**, MCD **-43.000000**, MSFT **37.000000**,
  NVDA **-30.000000**, PFE **-221.000000**. The heartbeat-minute bursts are episodic outliers, not a
  cadence — since 12:00Z the outsized ones are `16:05` (**$63,599.14**), `17:12` (**$81,499.01**) and
  `17:38` (**$99,922.58**), while `16:37` (**$28,990.48**), `18:06` (**$20,729.30**) and `18:09`
  (**$17,188.13**) are ordinary. I generalised "every teardown" from two consecutive cycles. **Two
  observations is not a cadence — before writing "every N", check the other N.**
- **Rule 214 — Rule 209 survives the retraction because it never depended on "every".** All sources
  still publish `horizonSeconds` **3600** against a ~30-minute process recycle, so the holding period
  is structurally shorter than the horizon the expectancy is measured over. When a finding is falsified,
  separate the part that rested on the false premise from the part that stands alone — do not discard
  both, and do not keep both.
- **Rule 215 — the order-level post-mortem the procedure mandates has NO evidence in it: FILLED orders
  carry a NULL `reason`.** Of the **523** FILLED orders since 12:00Z, **0** have a reason. All **249**
  populated reasons belong to CANCELLED orders and are the same string (`fusion re-plan — passive order
  superseded by a fresh target (ADR-0084)`); the only other is one REJECTED `no market data for MCD`.
  So `reason` is populated exclusively for orders that never traded. This is why three consecutive
  cycles each proposed a collapse mechanism and then falsified it — including this one. **Next change
  targets this**, with the trade-off stated up front: it moves no money and will most likely score
  ⚠️ INCONCLUSIVE, which is the correct outcome for buying evidence, not a failure.
- **Rule 216 — when the trigger is unknown, record the window as UNATTRIBUTED rather than crediting it.**
  PnL moved **+103.62** this window with `/api/risk/breaker` `halted: false`, regime `CHOP`/`CALM`,
  `volRatio` **1.04** and ordinary order sizes. With no trigger on any fill, market and change cannot be
  separated from the numbers. Writing down "unattributed" is the honest entry; inventing a cause is
  exactly the error Rule 213 just cost.
- **Rule 217 — the strategy books are not paying for their own fees; the hedge is carrying the firm
  total.** `/api/attribution` `firmTotal` **164.74096826** = HEDGE **160.19086234** + ALPHA
  **40.37358247** + MACRO **-35.82347655**, on `totalFees` **268.179380** of which ALPHA paid
  **261.858413**. Read the decomposition every cycle even when the headline is up — a rising total
  sourced entirely from the hedge is not evidence the strategy works.

## 2026-07-31 19:00Z — the missing trigger has a cause, and the hedge is masking a strategy book that turned negative

- **Rule 218 — the empty `reason` on FILLED orders is not a bug, it is a missing concept, and that
  changes where the fix goes.** Re-verified ⚠️ STILL-BROKEN: of this window's 60 `recent_orders`,
  **32 of 32 FILLED** and the **1 ROUTED** carry NULL, while **27 of 27 CANCELLED** carry text. The cause
  is in `OrderService.routeApproveAndFill`: reasons are written only on failure branches
  (`gate.reason()`, `"no market data for …"`, `"IOC — not marketable on arrival"`), while the success
  path is `transition(order, OrderStatus.ROUTED, null)` and submit publishes `publishOrderEvent(order,
  null)`. `reason` records *why a status changed*, and only failures have a status change worth
  explaining. So the trigger must be threaded from the deciding call site into `submit` — patching the
  order layer cannot recover information that was never passed to it. **Before proposing a fix for an
  empty field, read the write path: "never populated" and "populated with the wrong concept" need
  different fixes.**
- **Rule 219 — read the attribution decomposition for *frozen* books, not just for size.** HEDGE
  **160.19086234** and MACRO **-35.82347655** are byte-identical to last cycle's reading — neither book
  traded. ALPHA read **40.37358247** last cycle and **-13.33312927** now, so the entire run-over-run fall
  is the strategy book, and it fell *while gross rose* **+5619.53**. A book whose number does not change
  at all between cycles is information: it is not hedging, it is ballast. Check for identity, not just
  magnitude.
- **Rule 220 — `hedgeMasking: true` plus `covarianceReady: false` means the headline is flattered by a
  hedge that cannot act.** The firm total **111.03425652** is positive only because a static hedge P&L
  sits on a strategy book that is now negative net of the **264.094908** in fees it paid. Rule 217 said
  the strategy books were not covering their fees; one cycle later ALPHA is negative outright. **When a
  decomposition shows the total sourced entirely from a book that did not trade, promote it to a live
  bleed in the register — do not wait for the headline to go negative.**
- **Rule 221 — one cycle from a verdict is the worst moment to get impatient.** The scorer printed
  `still accumulating evidence (5/6 cycles)`. Five cycles of evidence on the revert would have been
  thrown away for a change that could have shipped 30 minutes later. The hold is cheap; the evidence is
  not.

## 2026-07-31 19:30Z — the hold cleared and the missing trigger got fixed where it was actually lost

- **Rule 222 — thread the trigger from the call site that DECIDES; you cannot recover it downstream.**
  Item #1 re-verified ⚠️ STILL-BROKEN at **36 of 36 FILLED** orders NULL against **24 of 24 CANCELLED**
  populated, then shipped as ADR-0134. The fix had to go where the information still exists: every
  deciding call site already held a sentence (`signal.rationale()`, `thesis()`, the hedge advisor's
  `rationale()`, the fusion target's entry/reduce/exit/stop-cut plus its forecast) and simply never passed
  it to `submit`. Written into `orders.origin_reason` **at insert**, before any status exists, so no
  transition can overwrite it. **When a field is empty on the happy path, the fix belongs at the producer,
  not the persister** — and a second column beats overloading the first when the two answer different
  questions (a REJECTED row is worth more carrying both the want and the refusal).
- **Rule 223 — a "no-money" change earns its cycle when it unblocks the money items behind it.** This one
  moves nothing and should score ⚠️ INCONCLUSIVE by construction. It was still the right spend: four
  consecutive cycles guessed at mechanisms and falsified them a cycle later (Rule 207 was retracted
  outright) for want of exactly this evidence, and the register's next item cannot be diagnosed without it.
  **Count the cycles a missing measurement has already wasted before dismissing a telemetry fix as
  low-value.**
- **Rule 224 — the frozen books stayed frozen; ALPHA is the whole move in BOTH directions.** HEDGE
  **160.19086234** and MACRO **-35.82347655** are byte-identical to last cycle for a second consecutive
  reading, while ALPHA went **-13.33312927 → 28.99506278**. Last cycle Rule 219 read the same identity on a
  fall; this cycle it is a rise. **The sign flips, the diagnosis does not: a book whose number never
  changes is ballast, and a headline sourced from it is flattered either way.** Firm total **153.36244857**
  with `hedgeMasking` **true** and `covarianceReady` **false**.
- **Rule 225 — the +31.01 is UNATTRIBUTED, and that is the honest entry until next run.** Breaker clear,
  regime `CHOP`/`CALM`, `volRatio` **0.99**, and the move sits on ALPHA positions no change of mine
  touched. With no trigger on any fill, market and change still cannot be separated — the last window that
  will have to be recorded this way, which is the point of the change.

## 2026-08-03 13:30Z — the origination trigger named the mechanism that had held the book flat for three days

- **Rule 226 — an UNESTIMABLE view is not a view of FLAT, and conflating them liquidates the book.**
  ADR-0124 correctly returns agreement 0 at one effective source (`1 − Σŵᵢ²`, the residual degrees of
  freedom of the weighted variance, is zero there — the dispersion cannot be estimated). But that 0
  multiplies the combined forecast to exactly 0, `targetQuantity` maps 0 to a target of flat, and ADR-0090
  works a flat target IN FULL. So a collapse in source BREADTH executed as a full-urgency decision to
  LIQUIDATE. **When a statistic's "I don't know" is encoded as the same value as "the answer is zero",
  check what the consumer does with that value — the statistics can be right and the decision still wrong.**
  Fixed as ADR-0135: hold the inventory already held, zero delta, no exit AND no entry.
- **Rule 227 — the exit trigger was structural and daily, not a market event.** Since ADR-0113 the
  price-driven sensors advance only when the tape PRINTS, so at every equity cash close they go silent and
  only the snapshot-based cross-sectional source is left. Breadth collapses to one at every close, by
  construction, and the desk round-tripped its entire book on it — ALPHA **-18.59706568** having paid
  **281.28065700** in fees, a firm total of **32.51192011** positive only because HEDGE carries
  **86.93246234**. **A cost that recurs on a calendar boundary is a mechanism, never noise; go find the
  boundary before theorising about the market.**
- **Rule 228 — Rule 223 paid out in ONE cycle.** The ADR-0134 origination trigger scored ⚠️ INCONCLUSIVE
  as predicted (it moves no money), and on the very first window it covered it named the trigger — four
  distinct FILLED origins where there had been none — that four previous cycles of guessing had failed to
  find. **Evidence-buying changes are scored INCONCLUSIVE and are still sometimes the highest-value spend
  on the board; judge them by what they unblock, not by their ledger row.**
- **Rule 229 — the obvious fix was the trap.** Giving the one-source case a non-zero agreement would have
  un-dormanted the book instantly, and would have handed the whole book to `xsreversion` — the only source
  still speaking and, at **-5.153198** avgReturnBps, the WORST-measured of the five. **When a change would
  put risk on, check WHICH source is about to size it before congratulating yourself on removing the
  blocker.** The fix must never turn a breadth failure into a licence to trade without corroboration.

## 2026-08-03 14:00Z — ADR-0135 verified; the re-entry blocker is a warm-up requirement no boot can satisfy

- **Rule 230 — a change was HELD, not stacked.** The scorer printed `74a47adee still accumulating evidence
  (1/6 cycles)` with `.pending-baseline.json` present, so no code shipped this cycle. ADR-0135 verified on
  its own terms: all nine one-source names read `estimable: false`, `agreement: 0.0`, `deltaQty: 0.000`, and
  produced **zero** liquidation orders. **Stopping the exit was never going to cause a re-entry — verify a
  fix against what it CLAIMED, and do not let a correct fix imply the symptom is gone.**
- **Rule 231 — a warm-up requirement longer than the PROCESS LIFETIME is a permanent veto wearing a
  temporary costume.** The risk-cut σ sensor needs `vol-span=120` ⇒ 121 prices at the 30 s cadence ≈ 60.5
  min; the durable seed supplies 27–85 (`… of 121`, every equity); `uptimeSeconds` is **727** and the loop
  tears the app down each cycle. So `stopArmed` is false on every boot, forever. **Before reading a "still
  cold" WARN as warm-up noise, compare the samples REQUIRED against the samples the process will ever LIVE
  to see — if the second is smaller, it is not warming up, it is deadlocked.**
- **Rule 232 — find the vetoer by ELIMINATION, not by assumption.** Two controls can freeze a name.
  `edgeGate: null` proved the ADR-0064 gate was silent, which left ADR-0126 as the only candidate; then
  `streamVolMeasuredNames: 1` of 22 confirmed it, and `PositionBuffer.java:164` explained why the aim never
  accumulates — an unpermitted name has its aim **re-seeded to held** every cycle, so the ADR-0080 path is
  reset to zero forever, not merely slowed. `insideBuffer: 22` and all `aims` at `0.0` are the fingerprint.
  **When intent is supposed to accumulate and telemetry shows it pinned at the held value, look for a
  re-seed, not a slow rate.**
- **Rule 233 — the conviction was there the whole time; only the permission was missing.** Thirteen names
  carried estimable views (MCD `-8.446` → `targetQty -670.773`, BAC `+6.507` → `+3090.103`) against
  **$1,500,000** of idle headroom. **A DORMANT book with live forecasts is never a signal problem — go
  straight to the permission chain, because no amount of signal work moves a book that is not allowed to
  trade.** And per Rule 229, the fix must ARM the stop with a measured σ, never bypass `stopArmed`:
  deleting the protection would open positions the ADR-0086 cut cannot price an exit for.

## 2026-08-03 14:30Z — the "permanent" σ veto was a closed-session artifact; the live defect is 82% of gross in one name

- **Rule 234 — SUPERSEDES Rule 231. A warm-up requirement longer than the process lifetime is NOT
  automatically a permanent veto, because the durable seed store GROWS between boots.** I recorded last
  cycle that σ could never arm (121 prices × 30 s ≈ 60.5 min vs `uptimeSeconds` ~727). Falsified this
  cycle: the ADR-0071 mark store accumulates while the tape prints, so successive boots seeded AAPL
  **75 → 107**, NVDA **85 → 117** of 121, `streamVolMeasuredNames` went **1 → 6**, and the book re-entered
  by itself at the open (`fusion entry — target increase`, NQ 14:14Z, NVDA 14:26Z). **Before calling a
  warm-up deadlocked, compare the seed depth across TWO consecutive boots — a rising seed is a warming
  sensor, and a weekend of no prints looks identical to a deadlock in a single snapshot.**
- **Rule 235 — measure a stuck sensor against its SIBLING on the same data, not against its own spec.**
  The decisive evidence was not the warm-up arithmetic; it was that `covarianceCoveredNames` = **19** and
  `streamVolMeasuredNames` = **6** on the same `mark-stream` basis at the same span of 120. One data
  source, one span, one estimator covering the book and one covering under a third. **When two estimators
  share an input and disagree on coverage, the defect is in the laggard's seeding path — that comparison
  localises it in one line, where the spec arithmetic sent me to a wrong conclusion for a whole cycle.**
- **Rule 236 — an under-deployed book is not merely small, it is CONCENTRATED, and the concentration is
  the real risk.** Targets summed to **$1,208,080** of |notional| against **$28,761.93** held — 2.4% of
  intent — but the damaging part is the shape: **NQ was $23,492.67 of the gross, 81.7%**, a naked short
  index future, because the 14 equity legs that would diversify it had `aims: 0.0`. **When a gate blocks
  names selectively, check what the SURVIVING names add up to before celebrating exposure coming back —
  a partial release of a veto is a concentration event, not a deployment.**
- **Rule 237 — attribute a fresh position's mark-to-market to the OPEN, not to last cycle's change.** PnL
  fell **$23.64** the same window ADR-0135 sat pending, which invites blaming it. It is causally
  impossible: every entry fired at `sources=2`/`sources=3` and ADR-0135 only alters the `sources=1`
  branch. The move was **-0.084%** on a 15-minute-old $23.5k short. **Check the order `reason` strings
  against the change's actual code path before crediting or blaming it — ADR-0134's origination triggers
  make this a lookup, so there is no excuse for guessing.**

## 2026-08-03 15:00Z — a fix that guards one branch of a defect gets tested on the branch next door

- **Rule 238 — when you scope a defect's fix to one branch, the untouched branch is not "out of scope", it
  is the next incident.** ADR-0135 shipped last cycle to stop a breadth collapse executing as a decision to
  be flat, and explicitly left `sources=0` to ADR-0065's orphan sweep. The very next live window: NQ read
  `forecast=-6.867533453373563, sources=2` at 14:32:21Z and `fusion exit — target decayed to flat
  [forecast=0.0, sources=0]` at 14:32:51Z — thirty seconds — taking the MACRO book to `grossExposure
  0.00000000` with `realizedPnl -56.79950536`. **The mechanism is `0.0` meaning two different things:
  "measured flat" and "nobody spoke". Fix that conflation everywhere it is read, or fix it in the type —
  patching one call site just moves which call site fires.**
- **Rule 239 — a vacuously-satisfied confirm-next-run criterion is NOT a verification.** ADR-0135's
  criterion was "no decayed-to-flat order at one source". The window had **zero** `sources=1` orders of any
  kind (44 orders: 28 at `sources=2`, 9 at `sources=3`, 1 at `sources=0`, 5 hedge). The criterion passed
  because the branch never ran. **Write VERIFY-BY conditions that require the guarded path to EXECUTE and
  behave, not conditions an absence of traffic satisfies — otherwise "verified" just means "quiet".**
- **Rule 240 — prove deployment from a field the change ADDED, not from the commit sha.** `/api/fusion/targets`
  now carries `"estimable"` on every row, which did not exist before `74a47adee`. That is one grep and it
  is unforgeable, where a sha match only proves what was built. **Every change should leave one observable
  fingerprint in telemetry so the next cycle can confirm the binary, not the build.**
- **Rule 241 — check the timestamp direction before accepting the obvious cause.** NQ's provider clock is
  frozen (`647s` stale) and "stale feed killed the sources" was the clean story. It froze at **14:49Z**,
  sixteen minutes **after** the 14:32:51Z liquidation. **A stale-looking sensor at report time says nothing
  about its state at event time — order the two timestamps before building the causal chain on it.** This
  is the second consecutive cycle where the tidy first explanation was wrong (see Rule 234).
- **Rule 242 — entry paced, exit instant, is a structural ratchet down.** NQ took 17 orders across 18
  minutes to build and one cycle at FULL urgency to destroy; firm-wide `orders_by_status` is `FILLED 4901`
  / `CANCELLED 1750`, and NQ's forecast sat bit-identical at `-6.867543315104213` for 13 consecutive cycles
  while ADR-0084 cancelled the resting order on every one. **When accumulation is throttled and liquidation
  is not, the book cannot hold size no matter how good the signal — compare the two urgencies before
  concluding a desk has no edge.**

## 2026-08-03 15:30Z — the desk can grow a position but cannot start one

- **Rule 243 — when a book deploys but some names stay at exactly zero, check `deltaQty` against
  `currentQty` before blaming conviction or pacing.** `/api/fusion/targets` split with no exception this
  window: `deltaQty` exactly `0.0` on all four flat names (PFE `6.601885581804063`/3 src, HD, JPM, MSFT)
  and non-zero on all five held ones (XOM `-0.363738`, JNJ `-35.776603`, CVX `-6.065554`, BAC `-0.381741`,
  AMZN `-0.049792`). PFE carries the LARGEST forecast in the book and plans nothing while CVX trades on
  `-2.6889594393466063`. **A veto conditioned on `currentQty == 0` looks identical to "low conviction" and
  to "slow pacing" in aggregate exposure — only the per-name delta-vs-position split tells them apart.**
- **Rule 244 — `reduceOnly` on a flat position is an ABSORBING state, not a throttle.**
  `TargetPlanner.reduceOnly` returns zero identically when `cur.signum() == 0`, so any gate that routes a
  name through it (ADR-0126's σ-cold veto in `PositionBuffer.mayIncrease`) makes that name unopenable
  **forever**, at any conviction — the gate cannot clear itself because clearing it would require the
  position it forbids. **Audit every reduce-only path for whether the thing that lifts the gate depends on
  the trade the gate blocks.**
- **Rule 245 — a ranking argument built on an assumed coupling must be re-tested when the book moves.**
  The 15:00Z block ranked the zero-source sweep above deployment because "deploying more capital just feeds
  it". This window put `+57555.68` of gross on across XOM/CVX/JNJ/CAT/BAC/AAPL/AMZN/NVDA and the sweep
  fired **zero** times — it needs `sources=0`, which the ADR-0113 sensors produce at the CLOSE, while the
  opening veto costs every mid-session cycle. **Re-rank on measured co-occurrence, not on a plausible
  mechanism linking the two items.**
- **Rule 246 — make the veto reason observable before designing the fix for it.** Two mechanisms
  (σ-cold veto, ADR-0094 band from flat) predict the same zero delta and `/api/fusion/targets` reports the
  delta but never why it is zero. This register has already burned a cycle on a falsified trace here
  (Rule 234) and one on a reversed causal story (Rule 241). ADR-0134 solved the identical gap for orders
  and turned four cycles of guessing into a lookup. **When two candidate causes are indistinguishable in
  telemetry, the correct one change is the instrument, not a guess at the cure.**
- **Rule 247 — a signal that reverses sign inside the entry schedule is an execution-horizon bug, not a
  bad signal.** BAC filled at `+5.310419321398655` (3 sources) at 15:06:18Z and carries
  `-5.488089050299691` (3 sources) twenty minutes later; AMZN the same. `reversion` holds the dominant
  fusion weight `1.6917588861521635` in a `"trend": "CHOP"` regime, and the unwind runs at `-0.381741`
  shares/cycle against a 2,807-share gap. The cumulative form is `turnover_cost_by_name`: MSFT `189` fills,
  `193554.74` turnover, `currentQty 0`. **Compare the source's decay horizon against the time the schedule
  needs to reach target before concluding the source has no edge.**

## 2026-08-03 16:00Z — there IS edge here, just not at the horizon being traded

- **Rule 248 — a mechanism inferred from an aggregate is a hypothesis; three of them have now died here.**
  Last cycle read a clean split (4/4 flat names at `deltaQty 0.0`, 5/5 held non-zero) as a veto conditioned
  on `currentQty == 0` and ranked it #1. This window held names `PG` (`-106`), `JNJ` (`-76`), `NVDA` (`-44`)
  are ALSO at `deltaQty 0.0`, and the log carries **41** `fusion entry — target increase` orders including
  `PG SELL 52` from flat. `/api/fusion/targets` is stamped `15:59:43Z`, after the last order at `15:57:42Z`
  — the zero deltas were a snapshot instant, not a gate. **Check the endpoint's own timestamp against the
  order log before reading a cross-sectional split as a mechanism** (cf. Rules 234, 241).
- **Rule 249 — compare the source's measured horizon against the RE-PLAN cadence, not against the fill
  time.** `/api/signals/telemetry` `avgReturnBps` at 225s: `reversion 0.047`, `trend 0.031`,
  `xsreversion -0.101`, `social 0.490`, `momentum -0.666` — all inside ±0.7 bps, hit rates `0.424`–`0.504`.
  At 3600s the same sources read `reversion 3.1947226656494396`, `social 4.6399547951434625`. One side of a
  round trip costs `fee_bps 1.00` (`turnover_cost_by_name`) plus `avgSlippageBps 0.59`–`0.73` (`tca`).
  **The desk trades the one horizon where cost exceeds every source's gross expectancy** — and the two
  heaviest fusion weights (`reversion 1.6383242369546946`, `social 1.6087300321666014`) belong to the
  sources that only pay at 900–3600s.
- **Rule 250 — when fees exceed the loss, the defect is in the execution layer, not the signal.** `ALPHA`
  `-152.93147385` against `feesPaid 293.768903`; `firmTotal -76.36276038`, `totalFees 302.007152`. JNJ sold
  50 at forecasts `-18.13`/`-17.12`/`-14.93` then bought back 40 starting **31 seconds** later at `-0.32`;
  XOM sold 92 at `-5.06`…`-10.33` and bought back 60 at `+1.28`…`+3.75`. MSFT: `189` fills, `193554.74`
  turnover, flat. **Before concluding a desk has no edge, check whether it is being charged a round trip
  for every view it forms.**
- **Rule 251 — grade a guarded branch only on a window where it took traffic, and the wait can be long.**
  ADR-0135 sat unexercised for three windows; on the fourth a `sources=1` cycle appeared
  (`JNJ BUY 9 [forecast=-0.0, sources=1]`) and `fusion exit — target decayed to flat` fired **zero** times —
  the trigger it was shipped to remove. **Patience on a vacuous criterion is correct; grading it early
  would have banked a false verdict in either direction.**

## 2026-08-03 16:30Z — a failed auto-revert leaves rejected code running the money, silently

- **Rule 252 — when the ledger says REVERT FAILED, CHECK, and make that check the cycle's one change.**
  `74a47adee` (ADR-0135) was graded **❌ BAD** and its row carried `⚠️ REVERT FAILED (git conflict): the BAD
  commit is STILL LIVE`. `git merge-base --is-ancestor 74a47adee HEAD` returned **true** — the rejected
  mechanism had been running the desk for four cycles after being graded, while the loop spent those cycles
  ranking *new* items. The cause: `git revert` conflicted on `docs/loop-findings.md`,
  `reports/last-analysis.md` and `reports/must-fix.md` (later cycles append to all three), and because
  `git revert` is all-or-nothing a conflict in three **documentation** files aborted the **code** revert.
  This is the **second** occurrence (ADR-0133 / `e61c7f5aa`, completed by hand in `4f67f0515`). Resolution
  both times: revert the code, **keep** the memory files, **keep** the ADR marked `Status: Reverted`.
- **Rule 255 — grade a revert on the CODE, never on ancestry.** `--is-ancestor` is the right test to DETECT
  an unreverted commit, but it is the wrong test to VERIFY one: `git revert` adds an inverse commit and
  leaves the original in history, so the ancestry test returns true forever and a later cycle reading it
  would mark a completed revert STILL-BROKEN and revert it again. The proving metric is the absence of the
  mechanism in the source (`grep` over the touched package) and of its field on the endpoint.
- **Rule 253 — "it did what it claimed" and "it made money" are different verdicts; record both, and let
  the scorer's govern.** ADR-0135 was ✅ VERIFIED at the defect level and ❌ BAD on the vector, and both
  stand. The guard genuinely stopped the breadth-collapse liquidation; it also kept risk deployed against a
  view the desk had already measured as uninformative, and that carry did not earn. **Stopping a forced exit
  is not the same as having a reason to hold the position.** Collapsing the two verdicts into one is how a
  rejected mechanism survives its own revert.
- **Rule 254 — count a trigger's `sources=` before concluding it recurred.** `fusion exit — target decayed
  to flat` fires **2** times this window with the guard still live, which reads like a regression until the
  rows are opened: both carry **`sources=0`** (`PG BUY 106` FILLED, `CVX BUY 2` REJECTED at 16:08:19Z) — the
  ADR-0065 orphan sweep, explicitly out of ADR-0135's scope and byte-identical before and after. The
  one-source branch stayed clean. Same failure mode as Rules 234/241/248: an aggregate count is not a
  mechanism.
- **Trigger/attribution.** No PnL is claimed for this change. The window's `+1.70` is mark-to-market on
  positions not touched this cycle — market, not change. Reverting to previously-running code restores prior
  behaviour; what it buys is that the next measurement is attributable at all.

## 2026-08-03 17:00Z — a revert is verified by a trigger coming BACK, not by a grep returning empty

- **Rule 256 — verify a revert on the RUNNING BEHAVIOUR, not just the source tree.** `c20fb0b70` cleared
  every static check (`grep -rn "estimable" app/src/main/java/io/jethro/app/fusion/` → 0 lines; no
  `estimable` field on `/api/fusion/targets`; ADR index `0135 … Reverted`), but the check that actually
  closes the loop is that the branch ADR-0135 *suppressed* is firing again:
  `fusion exit — target decayed to flat [… sources=1]` on `PFE SELL 26`, `CAT SELL 1`, `JNJ SELL 63`,
  `CVX BUY 1`. A grep proves the code compiled without the mechanism; **only the restored trigger proves the
  desk is running it.** Extends Rule 255 — that rule said don't grade a revert on ancestry; this one says
  don't stop at the source either.
- **Rule 257 — the desk's weights and the desk's clock disagree, and the clock is winning.**
  `/api/signals/telemetry`: at **225s** every source is inside ±0.71 bps with `|t| < 0.71`; the expectancy
  only appears at **3600s** (`social +5.917`, `t=+1.34`; `reversion +3.079`, `t=+1.07`). The fusion weights
  already reflect that — `social 1.680` and `reversion 1.572` are the two heaviest. But the planner re-plans
  every **30s** at `fee_bps 1.00` per side, so it pays ~2 bps round trip to chase a 225s expectancy of
  `+0.042`. `JNJ` this window: 5 cancelled entries, `BUY 51`, `SELL 6/7/9/2`, `SELL 63` — a full round trip
  in 13 minutes on a view that needs 60. **Weighting a source correctly is worthless if the holding period
  is shorter than the horizon the weight was measured at.**
- **Rule 258 — the only significant number in the telemetry is negative, and that is a reason for patience,
  not a trade.** `xsreversion` at 3600s: `-7.765` bps, **`t=-2.19`** on 512 resolved — every other cell is
  inside `|t| < 1.35`. The weighter is already handling it (floor weight `0.25` vs `social` 1.680). Do NOT
  sign-flip a source on one significant t: that is the overfit this loop's INCONCLUSIVE wall was built from.
  Require the sign to persist on an independent window first, then zero the weight rather than invert it.
- **Trigger/attribution.** No PnL claimed. Window `-9.93` on total PnL is mark-to-market plus cost on
  positions the revert did not select — market, not change. Gross `+1,018.67` at **3.3%** of the firm cap
  with `$1,450,954` of headroom is the desired direction, not a risk event. No change shipped: `c20fb0b70`
  is at **1/6** cycles and stacking on it would destroy the attribution.

## 2026-08-03 17:30Z — the cadence doesn't just cost money, it picks the desk's worst forecasts to fill

- **Rule 259 — use `stdCohortMeanBps`/`cohorts`, never `stdReturnBps`/√`resolved`. It retires Rule 258.**
  `/api/signals/telemetry` publishes a clustered dispersion alongside the naive one. Rule 258 called
  `xsreversion` at 3600s "the only significant number" off `t = -2.19`, computed as if **517** overlapping,
  cross-sectionally-linked observations were 517 independent draws. There are **`33` cohorts** behind them
  (`stdCohortMeanBps 30.03`, `avgReturnBps -8.288`). On the honest denominator it is inside the noise band —
  and so is every other cell at every horizon (`social` 3600s `+5.690`/`28` cohorts/`28.27`; `reversion`
  `+3.146`/`71`/`30.92`; the entire 225s column inside `±0.31` bps). **No source in this universe has
  significant edge at any measured horizon.** Say it plainly; do not weight-tune against it. A significance
  claim is only as good as its denominator, and overlapping signal windows are not independent draws.
- **Rule 260 — a re-plan cadence shorter than the fill time is ADVERSE SELECTION, not just turnover cost.**
  `JPM` 17:13:02Z→17:17:35Z: forecast `14.48 → 12.23 → 9.64 → 9.40 → 9.21 → 5.90 → 5.05`, ordered qty
  `4 → 10 → 13 → 15 → 18 → 21 → 10`. The first five are `CANCELLED … superseded by a fresh target
  (ADR-0084)`; the two that **FILL** are the two weakest views on the ladder. Passive orders on strong
  forecasts get superseded before they fill, so only decayed ones reach execution — the desk systematically
  fills its worst signal. `JPM`: `realizedPnl -54.74`, `totalPnl -79.46` on the 46 shares built this way;
  **22 of 60** window orders are supersession cancels. This reframes the fix: not "trade less" but "let a
  passive order live long enough to fill on the view that placed it".
- **Rule 261 — do not read `targetQty` as intent.** `/api/fusion/targets`: `JPM` `targetQty 357.679` vs
  `currentQty 46.0` with **`deltaQty 0.0`**; `CAT` `-94.603` vs `0` with `deltaQty 0.0`. The published target
  and what the planner works toward differ by an order of magnitude with no field explaining the gap. Reason
  about sizing from `deltaQty` and the orders, never from `targetQty`.
- **Rule 262 — fees, not the market, are what put this book underwater.** `totalFees 324.11` against
  `firmTotal -205.30`; `ALPHA -280.72` on `feesPaid 314.30`, while `HEDGE +132.22` is the only book earning.
  Gross of fees the desk is up. When cost exceeds the entire measured expectancy, the edge work IS the cost
  work — that is not a retreat from the standing "work on EDGE" priority, it is the answer to it.
- **Trigger/attribution.** No change shipped (`c20fb0b70` at **2/6**). Window `-130.88` to `-205.30`;
  gross `+6,033.80` at **3.8%** of the firm cap with `$1,442,562` headroom — direction is right, not a risk
  event. Split: `NVDA` `-123.36` (`unrealized -141.35`, short 44) appears in **no** window order → market on
  an untouched position. `JPM` `-54.74` realized is the desk's own ladder → change-side. `MCD` `-27.35`
  realized was crystallised by the `sources=1` exit the revert restored → recorded against the pending
  change, not excused. The position table is **cumulative**, so no exact per-name decomposition of the
  window delta is claimed.

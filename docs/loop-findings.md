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

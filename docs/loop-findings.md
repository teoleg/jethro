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

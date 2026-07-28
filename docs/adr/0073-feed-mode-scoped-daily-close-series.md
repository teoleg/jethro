# ADR-0073: The daily close series is feed-mode scoped — a handover between feeds is not a market move

- **Status:** Proposed
- **Date:** 2026-07-26
- **Deciders:** Oleg
- **Tags:** risk, var, volatility, data-integrity, invariant-8

## Context

`daily_close` — one close per (day, instrument) — is the return series every **risk sensor** in the
platform runs on:

- `VarService` — historical VaR/ES and the parametric EWMA(λ=0.94) covariance behind `/api/var`;
- `InstrumentVolService` — per-name measured daily volatility, which **vol-targets position sizing**
  in `StrategyLifecycle`, `HypothesisEvaluator` and the order path;
- `SimIndicatorsSource` — the previous-session close behind every change chip on the UI.

It was the one end-of-day artifact that was never scoped by feed mode. Both writers show the defect in
the clearest possible form — `EodService.rollover()` and `MarketHistoryRecorder.recordOnce()` each
write `firm_equity` **with** `feed_mode` (its primary key includes it) and, in the same method, write
`daily_close` **without** it, keyed `(day, instrument)` with an upsert. So a LIVE session's closes and
a SIM session's closes land in the same series and overwrite each other on shared days.

That is a direct violation of **hard invariant 8 / ADR-0029**: sim, live and replay data are never
aggregated across modes. And because the sim's AAPL and the live feed's AAPL are different price
processes at different levels, the close-to-close "return" at a handover is the ratio of two unrelated
price levels — a phantom, not a market move.

**The observed contamination**, from the running database. The seeded bootstrap history runs
continuously to 2026-07-17; live sessions ran 07-20 → 07-24 and again on 07-27; sim sessions on 07-26
and 07-28 onward. The two blocks interleave:

| day | close (AAPL) | stream | close-to-close as read before this ADR |
|---|---|---|---|
| 2026-07-17 | 190.000000 | seed | — |
| 2026-07-20 | 326.950000 | live | **+72.08%** |
| 2026-07-24 | 333.480000 | live | +3.82% |
| 2026-07-26 | 189.859198 | sim | **−43.07%** |
| 2026-07-27 | 333.480000 | live | **+75.65%** |
| 2026-07-28 | 192.524266 | sim | **−42.27%** |

Four fabricated days of ±40–75% in the last eight observations of the risk window. The EWMA estimator
weights the most recent observations most heavily, which is precisely where they sit. Measured daily
volatility, contaminated series vs. the clean seed block over the same trailing window:

| instrument | daily vol as measured (contaminated) | daily vol on the clean block | overstatement |
|---|---|---|---|
| GOOG | 22.43% | 2.22% | 10.1× |
| AAPL | 18.65% | 1.74% | 10.7× |
| JPM | 17.51% | 1.41% | 12.4× |
| JNJ | 16.86% | 1.48% | 11.4× |
| NVDA | 16.77% | 2.50% | 6.7× |
| ES | 9.98% | 0.84% | 11.9× |

The clean figures are ordinary (AAPL ~1.7%/day ≈ 27% annualised; ES ~0.8%/day ≈ 13%). The contaminated
ones are not volatility at all. The tell is decisive: names that have only ever run under one feed —
GOOGL 0.42%, BRK.B 0.79%, GS 0.89%, TSLA 0.92% — are uncontaminated and sane. **The 10–12× is a
function of how many feed boundaries a name has lived through, not of the name.**

So the platform's answer to "how much is at risk right now" — the first sensor in the owner's strategy
thesis — has been wrong by an order of magnitude for every name that spans a mode switch, and every
position vol-targeted off it has been sized against a fiction. The `training_bars` store (ADR-0038)
already keeps its own table specifically because the runtime series is "sim-contaminated at the tail";
this ADR fixes the cause rather than working around it a second time.

## Decision

**Tag every recorded close with the stream that produced it, and never take a return across a
handover.** Two parts, both mechanical:

1. **Rows carry a `feed_mode`.** `daily_close` gains the column; its primary key becomes
   `(day, instrument, feed_mode)`, so a LIVE close and a SIM close for the same name on the same day
   are two distinct observations in two distinct series and neither overwrites the other. Both session
   writers stamp `Provenance.mode()`, exactly as they already do for `firm_equity`/`book_equity`. The
   bootstrap history loaded by `HistorySeeder` is tagged **`SEED`**: reference history, loaded once
   before any session ran, the prior every mode starts from — not another mode's session output, so
   admitting it is not aggregating sim with live.

2. **Readers select observations by two rules** (`DailyCloseSeries`, pure and exactly tested):
   - **admissible rows** = the running feed mode's own closes, plus `SEED`; where a day carries both,
     the session's own observation of its own tape wins;
   - **admissible returns** = only between two consecutive closes from the *same* stream. This is what
     actually removes the phantom — filtering rows alone still leaves the one boundary pair where the
     seed hands over to the session. The cost is at most one dropped observation per boundary: a gap
     the estimator handles, unlike a fabricated 72% day, which it cannot.

**Historical rows are re-tagged deterministically from the platform's own session record** — never
from any rule about prices, which would be a threshold on money data with no provenance. `firm_equity`
already carries `feed_mode` per day, so the V47 migration reads the mode from there: days before the
first session this platform ever closed are the seeder's (`SEED`); session days whose mode is recorded
unambiguously take it; and **session-era days with no unambiguous recorded mode are deleted** — a close
whose feed mode cannot be established is not an admissible risk observation, and `daily_close` is
derived data (ADR-0014) the running session re-accrues.

Nothing about how any risk number is *computed* changes: the VaR method, the EWMA λ, the estimator
windows and the money types are all untouched. This ADR changes only **which observations are
admissible**, which is why it carries no new dial and no number of its own (invariant 7 / ADR-0016).

## Consequences

**Good.**
- Per-name vol and firm VaR/ES stop reporting a feed handover as a market move. Vol-targeted sizing
  gets a real σ, so a position sized against a 10× overstated vol is no longer 10× too small — the
  first sensor in the strategy thesis starts telling the truth.
- The invariant-8 violation is closed at its source, and the pattern now matches the two mode-scoped
  writes sitting beside it in the same methods.
- A sim→live switch no longer silently corrupts the risk history of every name it touches. This
  matters far more on a live feed than in sim: that is the run whose VaR is the real answer.

**Costs and limits, stated plainly.**
- **The migration deletes rows.** Currently ~8 session days whose mode is not recorded in
  `firm_equity`, against a ~1,560-day seed block that is unaffected. Derived data, re-accrued by the
  running session; but it is a delete, and it is irreversible in place.
- **One observation is dropped at each handover.** Deliberate, and cheap at these window lengths.
- **The seed block's realism is a separate question.** It is a real proxy history rescaled to each
  instrument's start level; returns are invariant to that scaling, but it is not this feed's tape.
  Using it as the prior in every mode is unchanged behaviour, not something this ADR endorses.
- **`training_bars` is untouched.** It already keeps its own clean copy; consolidating the two stores
  is a separate decision.
- Nothing here can move PnL on its own. It changes what the risk sensor reports and what vol-targeted
  sizing reads; the edge gate, the conviction floor, the pre-trade guardrail and the firm drawdown
  breaker are all untouched, and the deterministic floor is untouched.

## Alternatives considered

- **Scope strictly to the running mode, dropping the seed.** Cleanest reading of invariant 8, but in
  sim it leaves ~1 day of history against a 250-observation VaR window — the honest answer becomes
  "insufficient history" for months. Tagging the bootstrap as reference data preserves the depth
  without mixing two sessions.
- **Reject implausible returns at the reader.** Would need a threshold on a money-derived quantity with
  no provenance, and would silently eat real gap risk — exactly the tail VaR exists to measure.
- **Leave `daily_close` alone and give each risk sensor its own clean store**, as `training_bars` did.
  Duplicates the series a third time and leaves the next consumer to rediscover the same trap.

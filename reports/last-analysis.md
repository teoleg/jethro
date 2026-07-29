Every dollar of the firm's loss is in positions the desk opened and then closed — the book it still holds is profitable.

*Every figure below is read from the live endpoints, `logs/report.md`, `reports/run-status.json` or the
scorer, or computed from them by script; none is authored here (invariant 7 / ADR-0016). The t-statistics
are computed by script from the telemetry's own published cohort dispersions
(`avgReturnBps / (stdCohortMeanBps/√cohorts)`); the bps-of-turnover figures are computed by script from
`/api/risk` realized PnL over the report's `turnover_cost_by_name`.*

**0. Scoring state — this decides what I am allowed to do.** `scripts/score-change.py score` prints
`d9f8969cc still accumulating evidence (3/6 cycles) — held, not scored this run`, and
`reports/.pending-baseline.json` is present. **No code change this cycle**, per the contract. Analysis and
memory only.

**1. Money.** Total PnL **−$60.91** live (`/api/risk` `.total`; the report header, taken ~7 min earlier,
read −$55.35, and it moved again to −$68.00 mid-analysis — the book is live and moving). Run-over-run
**−$18.14**; over the last three runs **−$55.96**, from the $0.61 that had been frozen for four days.
`pnl_growth_pct` −6179.05 against `pnl_target_pct` 1.0, `on_track=false`, `stale=true`, **UNDERWATER**.

**2. Risk.** Gross **$18,238** = **1.2%** of the $1.5M firm cap, headroom **$1,482,564**; net **$3,250** =
**0.3%** of the $1M net cap. Gross rose $119 this window. **Not DANGER**: 1.2% of the exposure cap, and
−$61 against a `maxFirmDrawdown` of $50,000. Exposure rising off dormant is the goal, not the problem.

**3. Cause — and it is not what any previous cycle named.** Split the book by whether the position is
still open (script, from `/api/risk` `.positions`):

- **Names now FLAT — round-tripped: NQ, JPM, GOOGL, ES → total −$68.12.**
- **Names still OPEN — MSFT, JNJ, AAPL, NVDA, AMZN → total +$7.21.**
- Firm total −$60.91. The two reconcile exactly.

**The entire firm loss, and more, sits in positions the desk opened and then closed. The inventory it is
actually holding is up.** The desk's *views* are not losing money; its *round trips* are. That also settles
the change-vs-market question with no guessing: a realized round-trip loss is 100% the trading logic — no
untouched inventory, no market move to blame.

**4. Danger.** No. But the cost rate is the alarming number, not the level.

**5. Order-level post-mortem — the round trips, and what they cost per dollar traded.** LIVE turnover today
is **$64,453 against a gross book of $18,238 — 3.53× the book turned over** in roughly seventy minutes.
Firm realized PnL is **−9.80 bps of turnover**; fees are **0.88 bps**. **The loss is ~11× the commission.**
It is not cost of execution, it is the *direction* of the round trips — the desk is systematically buying
higher than it sells. Per name (realized, as bps of that name's turnover):

- **NQ −$35.82 on $8,492 → −42.19 bps.** The rule-66 failure completed: eight `REJECTED — no market data
  for NQ` exits, then a flip to BUY and an add, then finally `SELL 0.007661 FILLED` at 14:51:50. The exit
  the desk wanted at 14:10 was executed 40 minutes and one accumulation later.
- **GOOGL −$20.79 on $13,991 → −14.86 bps** — the single largest turnover in the book, now flat.
- **JPM −$12.46 on $11,911 → −10.46 bps** — last cycle's whipsaw, now closed and fully paid.
- MSFT / NVDA / AMZN realized are each exactly **−1.00 bps** — i.e. pure fee. They have only been built,
  never round-tripped, and they cost nothing but commission. That contrast *is* the finding.
- **AAPL: the prediction landed exactly.** Last cycle I flagged that the combiner was about to flip the
  book's only winner (short −4 sh, +$9.25, target +87.54). It flipped it: AAPL is now **long +10**, realized
  **+$8.38** banked, unrealized **−$9.19**, total **−$0.81**. The winner was converted into a scratch.

**6. Memory applied.** Rule 63 (sole-source fade buys more as price falls), rule 65 (agreement ≡ 1.0 at
n=1) and rule 66 (a refused exit becomes an add) all recur here, and rule 64 kept me from reading
seventy minutes of PnL as evidence about a signal.

**7. The mechanism, and why GOOGL is the clean case.** GOOGL took the largest turnover in the book while
the log says its **`trend` sensor (2 of 193 prices), its `reversion` sensor (2 of 241) and its risk-cut σ
sensor (2 of 121) were all cold** — "this name cannot be stopped out until its mark history has
accumulated." Only `xsreversion` was warm, because a cross-sectional fade needs one snapshot, not history.
So a newly-promoted name is **structurally n=1**, and ADR-0119's `agreement = |Σwᵢfᵢ| / Σwᵢ|fᵢ|` is
**identically 1.0 at n=1**, so it is **structurally full-conviction**, and its stop is **structurally off**.
Three failures compound on precisely the names carrying the least information — and the one warm source is
`xsreversion`, which measures negative expectancy at every horizon. TSLA (10 of 241) and ORCL (0–3) are
queued in the same state.

Multi-day LIVE telemetry, t by script at 225/900/3600s: **`reversion` +0.38 / +1.26 / +0.73** (984/305/88
resolved) — the only source positive at every horizon. **`xsreversion` −0.35 / −0.41 / −1.36** — negative at
every horizon. `trend` −0.14/+0.08/−0.52, `momentum` −0.04/+0.31/−1.45, `social` −0.50/−1.75/−0.79.

**Decision.** No change — the scorer holds the floor for three more cycles. Committing reasoning and memory
only. **Next cycle's change, stated now so it is falsifiable and not retrofitted:** make conviction require
corroboration *and* a warm risk sensor — n=1 must score near-minimum agreement rather than maximum, and a
name whose risk-cut σ sensor is cold cannot be stopped out so it should not be sized into. One coherent
change, with an ADR, since it is a degenerate formula plus a risk-model precondition, not a parameter tweak.
**What I will check first:** whether META (`sources:1`, agreement 1.0, target +62.79, 13 consecutive
posted-and-cancelled BUYs ratcheting 1→8 without a single fill) has become a fourth GOOGL by then — if it
round-trips at a double-digit-bps loss, that is the fourth independent instance and the fix is confirmed
before it ships; if it fills and holds profitably, my mechanism is wrong and holding-period discipline
against the 30s re-plan is the better target instead.

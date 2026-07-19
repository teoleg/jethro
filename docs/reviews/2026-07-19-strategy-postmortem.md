# Strategy post-mortem — first live composition (2026-07-19)

Source: the diagnostics workbook exported from a running sim session (2026-07-18 19:59), read
sheet by sheet. This is the trader read of *why the book bled* and the sequenced plan to fix it.

## The headline: the book is flat, not blown

Comprehensive P&L **−$413.44** on **$1.41M gross / $866k net** exposure = **−0.03%**. The "lost my
million" feeling was reading *exposure* (position size) as loss. The book is essentially flat; the
loss is a slow bleed, and it is diagnosable.

## Where the money actually moved

| Book | What it is | Clean P&L | Read |
|---|---|---|---|
| MACRO | ES / Treasury futures | **+$1,446** | carrying the book — mostly a +2.84 ES long that drifted up |
| ALPHA | equity momentum | **−$1,494** | the loser: every single-name equity bleeding |
| AI | rates hypotheses | **−$364** | directional rates bets, none backtested |
| HEDGE | the equity hedge | **−$2** | tiny, correct — the P1-1 runaway is gone |

Costs: **$84 fees + ~$77 slippage = ~$161**, i.e. **39% of the loss**, across 51 fills on $414k
traded notional. ES alone was 32 of 51 fills.

## Four findings

**F1 — momentum is the wrong strategy for this tape.** The correlated-factor sim is a near
random-walk with mean-reverting microstructure; momentum buys the breakout and the tape reverts
into it. Closed AMZN realized **−$560** (the single biggest loss) — a textbook whipsaw. A
mean-reversion strategy already exists in the codebase; in a mean-reverting market it should be the
primary alpha, chosen per instrument by the OOS backtest gate (ADR-0027) rather than momentum run
blindly by a single `jethro.strategy.algo` switch.

**F2 — the biggest risk in the book is unhedged and invisible.** The $866k net is dominated by a
**+$773k ES long in MACRO** — the strategy's own directional futures bet. The hedger only covers
single-name equities (a $50k net book) and ignores the $773k futures position entirely. This is
review finding **P2-1**: index futures sit *outside* the equity hedge axis. "Target-flat" today
means the *smallest* exposure is flat while the *largest* runs naked.

**F3 — the AI sleeve is in probation, working as designed (corrected).** The 5 hypotheses show
`backtested=N` and include BUY ZB / SELL ZN in the same minute, which *looks* like ungated
self-contradiction. But `backtested` here is the momentum OOS backtest, which ADR-0027 deliberately
**decoupled** from AI autonomy (gating the AI sleeve on a different strategy's backtest was a
category error that silently revoked all autonomy). The real gate is the AI's own **measured track
record** (`AutonomyEnvelope`): with no scored outcomes yet, every thesis is in **probation** and
resized to the 1-unit minimum to *build* a record — the −$364 is bounded probation tuition. Once it
has a full record with non-positive P&L, autonomy auto-revokes to human-review-only. So F3 is not a
bug to fix; the contradictory small bets are the intended "earn size with measured results" seeding.
The only real lever here is whether probation size / `min-track-record` are set conservatively
enough — a number for Oleg, not a code fix.

**F4 — over-trading.** 39% of the loss is transaction cost; ES churns. Thresholds too tight /
holds too short for the cost model.

## Estimation-quality findings from the 2026-07-18 math review — DONE (ADR-0041)

**P1-2/P1-3/P1-4 are shipped** in ADR-0041 (estimation rigor): covariance burn-in gate raised so the
hedge path sizes on a settled estimate (structural tier covers the warm-up), the SOFR curve now
bootstraps par→zero, and the VaR windows are labeled honestly. No further action.

## Implementation plan

**Phase A — hedge engine correctness — DONE.** ADR-0041 (40-day covariance gate; structural tier
covers the warm-up) and ADR-0042 (multi-proxy ES/NQ selection by measured ρ², switch hysteresis,
unwind-before-build, quarantine tradability) are implemented and green. This is the mechanism
Phase B sizes against.

**Phase B — hedge the whole book, not just single names (F2 / P2-1) — DECISION NEEDED.** The $773k
ES long in MACRO is the book's biggest risk and is completely unhedged. But it is *the momentum
strategy's own directional futures bet* — folding it into the target-flat hedge axis would neutralise
the strategy's futures alpha. So this is not a straight fix: **is MACRO's futures exposure intended
directional alpha (leave it, and label it a directional book) or unintended risk (hedge it into the
axis at β(proxy))?** A money/strategy call for Oleg. If "hedge it", it also needs β(NQ→ES) refdata
(ADR-0040 follow-up).

**Phase C — strategy selection (F1) — ADR + decision.** Momentum loses on this mean-reverting tape;
a mean-reversion strategy already exists. Make the OOS backtest gate pick momentum vs mean-reversion
*per instrument* from multi-seed out-of-sample Sharpe (default: no trade when neither clears),
superseding the single global `jethro.strategy.algo` switch. Biggest expected P&L impact.

**Phase D — AI autonomy — NO CODE (see F3).** The probation/track-record gate already handles this
by design (ADR-0027); the only lever is whether probation size / `min-track-record` are conservative
enough — a config number for Oleg, not a code change.

**Phase E — churn reduction (F4) — number decision.** 39% of the loss is transaction cost. Widen
`threshold-sigmas` / lengthen `lookback` / add a per-instrument re-entry cooldown so the edge clears
the ~3–4bp round-trip. The specific numbers must be set/approved (no invented risk dials), calibrated
against the TCA sheet.

Per CLAUDE.md design-first + the no-invented-numbers rule, B/C/E each carry a money/strategy/number
decision and do not land unilaterally. Phase A (correctness) is already shipped.

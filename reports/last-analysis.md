The desk was intending positions its own forecast contradicts — the aim is now bounded by the target it is chasing (ADR-0102).

*Every figure below is quoted from the live endpoints and the report; none is computed here. The ledger's
numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** Total PnL `$747.98` on `/api/risk` `.total` at report time, `$760.78` when I re-read the
endpoint mid-analysis. The SITUATION header puts the window at `+83.46` and the last three runs at
`+242.33`; `run-status.json` reads `on_track` true, `stale` false, `underwater` false, `pnl_growth_pct`
far above the 1% target. The book is **not bleeding** — PnL has gone from `−825.93` to `+747.98` on the
day. Attribution splits it `ALPHA +996.84`, `MACRO +376.99` (unchanged to the cent for a **seventh**
cycle, still the stranded `0.000029` ES), `HEDGE −625.85`.

**2. Risk.** Gross exposure `$46,558.92` at report time, `$38,852.99` live — the report's
`EXPOSURE RISING` flag (`+3,141.97` on the window, `+10,955.79` over three) had already reversed by the
time I read the endpoint. VaR95 `$383.18`, ES95 `$537.02`, breaker `halted: false`, firm gross limit
`1,500,000` — we are at ~3% of it, so nothing is near a declared limit. The real risk story is not the
level but the **swing**: gross has gone `14,471 → 36,443 → 61,909 → 35,603 → 62,117 → 43,417 → 46,559`
across recent runs. A denominator that moves 4× on its own is what makes risk-adjusted PnL unstable.

**3. Cause.** Last cycle's change (ADR-0101, the measured buffer width) scored **⚠️ MIXED** with the
risk-adjusted read improving `0.01502 → 0.01622`, and PnL has kept climbing since. It was not the
culprit and is not reverted.

**4. Danger.** No. Not bleeding, exposure falling on the live read, nowhere near the breaker. So this is
not a de-risk cycle — it is a cycle to fix something that is quietly costing money.

**5. Order-level post-mortem.** No trigger opened a loser: `GOOGL −161.84` and `SAP −85.46` are closed
and flat, and every live position is a winner except the hedge. The window's orders are the same five
names being worked over and over — JNJ bought 11/13/14/15/18, JPM sold 2/3/4/5/7, AAPL sold 5/6/8/10 —
against `2,660` fills and `442` cancels.

**6/7. Change vs market — and what those orders were actually chasing.** The `+83.46` is on positions
ADR-0101 never touched; the change is credited with the turnover falling and with none of the PnL. But
reading `/api/fusion/targets` alongside those orders exposed the real problem, and it is not a market
move at all:

> The desk's **intent** was on the wrong side of its own forecast. JNJ carried an aim of `+8.043404`
> against a target of `−219.420787` while the book held `+104` — the largest position in the firm, long,
> in the name whose own forecast (`−12.64`) says short, with the aim path walking it *further* long.
> EURUSD carried an aim of `−19268.293125` against a target of `−13006.790342`. AUDUSD (`−11494.13`
> against `+17285.02`) and GBPUSD (`−2024.44` against `+19015.93`) were about to **open** positions the
> wrong way round from flat.

## Diagnosis — the mechanism

The aim recursion `aim ← aim + a(T − aim)` unrolls to an EWMA of the target sequence, so the aim is a
convex combination of the targets the desk held in the **past** — which is not the hull of the target it
holds **now**. Whenever the target moves faster than `1/a`, the intent can outgrow it or invert against
it. The desk's only source that passes its own edge gate is a **mean-reverting** one measured at the
900 s rung, which crosses zero repeatedly inside that window, so this is the steady state here, not a
corner case. Gârleanu & Pedersen's aim is a weighted average of the *current and expected future*
targets — every element a position the model wants; averaging over a realised past admits neither.

The arithmetic says it is expensive: being the wrong side of the passing source swings `±8.53` bps per
horizon against a measured `2 × 1.43` bps round trip — about 6:1 in favour of correcting it, the same
comparison ADR-0101 used to set the buffer width.

## The change

Clamp the aim into the closed interval between flat and this cycle's target (ADR-0102, Proposed, same
commit). No new dial and no new number — the bound is the target the planner already computed. Two
properties hold by construction and are asserted as tests: `|aim'| ≤ |aim|` and
`sgn(aim') ∈ {0, sgn(T)}`, so it can only ever shrink intent or move it onto the side the forecast is
on — it can never open, enlarge or side-flip a position. The flat-target branch returns before the
clamp, so the ADR-0086 cut, the ADR-0065 unwind, the ADR-0027 breaker and the pre-trade guardrail keep
their exact semantics. An inversion is cut once to flat and the position is then rebuilt through the
buffer at the derived rate rather than round-tripped. `./gradlew -Pci test` is green.

**Expected next.** Excess gross the planner never asked for comes off, wrong-side entries are not opened,
and wrong-side holdings are cut — so this should read as exposure down with PnL flat-to-up. **If it
scores BAD**, the thing to doubt is the assumption that the target is a clean statement of intent at the
cycle cadence; do not retry the clamp — go instead to the levers still open: (a) the overlay POSTURE
question ADR-0098 and ADR-0100 both deferred (HEDGE is `−625.85` and 15% of firm gross, and
`equity-rebalance-floor-usd = 0` hedges from the first dollar with no declared appetite); (b) the absence
of any absolute book-level volatility target, which is what leaves gross free to swing 4×; (c) the frozen
MACRO book, seven cycles unchanged around a stranded `0.000029` ES; (d) the ADR-0086 chandelier exit,
still `riskCuts: []` and zero fires — though note a trailing stop is the wrong shape for a mean-reversion
book and a *time* stop at the measured horizon would be the honest version; (e) `turnover_cost_by_name`
in the report is STILL erroring, and it is the aggregate that would grade exactly this decision.

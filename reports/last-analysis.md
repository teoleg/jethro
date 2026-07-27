Gave the desk the one thing it has never had — an absolute risk level: the book's measured ex-ante volatility is now capped at the median of its own planned volatility (ADR-0104).

*Every figure below is quoted from the live endpoints, the report or the ledger; none is computed here.
The ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** Total PnL `$811.00` on `/api/risk` `.total` at report time, `$824.48` when I re-read the
endpoint mid-analysis. The SITUATION header puts the window at `+9.68` and the last three runs at
`+146.48`; `run-status.json` reads `pnl_growth_pct 28.31` against a `1.0` target, `on_track` true,
`stale` false, `underwater` false. The book is **not** bleeding — PnL has run `−825.93 → +824.48` on the
day. Attribution splits it `ALPHA +1,083.73`, `MACRO +376.99` (unchanged to the cent for a **ninth**
cycle, still the stranded `0.000029` ES), `HEDGE −649.73`, fees `$437.66`.

**2. Risk.** Gross `$24,791.90` at report time, `$29,386.44` live; net `−$1,051.45` → `+$2,633.77`. The
report flags **EXPOSURE RISING** and it is real, not a stale read. VaR95 `$317.88`, ES95 `$422.71`,
breaker `halted: false`, firm gross limit `1,500,000` — about 2% of it, nowhere near a declared limit.

**3. Cause — last cycle's change.** ADR-0103 scored **❌ BAD** and the scorer reverted it: gross
`$819.54 → $27,640.96` against PnL `$801.62 → $814.36`, risk-adj `0.97813 → 0.02946`. It put $26.8k of
gross back on and bought `+$12.74` — inside the noise deadband — with it. So the exposure rise in (2) is
**100% attributable to last cycle's change**, order by order, and none of it to the market. The revert is
committed but the running JVM predates it (`uptimeSeconds 544`, started before the scoring commit), which
is why the live read still shows ADR-0103's book.

**4. Danger.** No — PnL rising, breaker clear, two orders of magnitude below the firm limit. Not a
de-risk-or-die cycle. But it is the *third* consecutive cycle whose verdict turned on exposure moving
several-fold for reasons unrelated to the desk's appetite, and that is the thing worth fixing.

**5/6/7. Order-level post-mortem, memory, and change-vs-market.** No trigger opened a loser: all seven
live ALPHA positions are winners and the window's orders are the same handful of names being worked
toward a target — `JNJ` bought ten times, `AAPL` sold eleven, `GOOG` round-tripped, against 2,785 fills
and 465 ADR-0084 re-plan cancels. `docs/loop-findings.md` has carried "**no absolute book-level
volatility target — nothing anchors gross, which is why it swings 4×**" as open lever (b) for three
cycles; three separate postmortems named it and none acted on it. This cycle I did. I claim **no PnL
credit or blame** for the window: the `+9.68` is market on positions nothing of mine touched.

## Diagnosis — every size control on this desk is relative; none sets a level

`ADR-0083` splits the per-name cash budget by measured σ and is *budget-neutral by construction*
(`Σᵢkᵢ = |C|`). `ADR-0079` scales the book by how much of it is one bet, back to "the risk the per-name
budget already implied" — its own words. `ADR-0086` cuts a position that has gone wrong. Not one of them
states how much risk the **book** should carry, so the level is
`unit-notional × (how many names happened to clear the gate) × (how strong their forecasts happened to
be)`. Both middle factors are properties of the cross-section on the day. That is why measured firm gross
has read `$14.5k → $62.1k → $43.4k → $46.6k → $7.9k → $27.6k` across consecutive evaluations with nothing
about the desk's appetite changing — and why PnL-per-unit-of-exposure, the objective, has been measured
against a denominator that moves several-fold on its own. Worse than unmeasurable: it means the desk
carries its largest risk in whichever cycles the cross-section happened to shout.

The reason this stayed deferred for three cycles is the **number**. A conventional vol target ("12%
annualised") is a money figure with no provenance here, and the firm's declared appetites
(`max-firm-drawdown 50000`) sit two orders of magnitude above a `$25k` book, so a target derived from
them could never bind.

## The change (ADR-0104, Proposed, same commit)

Cap the book's measured ex-ante σ — `√(eᵀΣe)` over the covered names, the same statistic and the same
covariance ADR-0079 already prices concentration with — at the **median of its own planned-σ series**,
scaling every covered target by `min(1, σ_ref/σ_planned)`. The reference is not a chosen volatility
target: it is the desk's own typical planned risk, measured on whatever stream it trades, so the same
code self-calibrates to a sim, live or replay feed and **no money/risk number is introduced**. Strictly
one-way and asserted as tests — it can only shrink the book, never flip a name's side, never ratchet
(the series samples the raw pre-brake σ), and never set a target flat, so the ADR-0086 cut, the ADR-0027
breaker and the pre-trade guardrail keep their exact semantics. The empirical bet is the standard
volatility-targeting result (Harvey et al., *JPM* 2018; Moreira & Muir, *JF* 2017): returns are not
proportional to risk across risk states, so trimming the hot ones raises return per unit of risk.
`./gradlew -Pci test` is green.

**Two honest caveats, stated up front.** (i) The next scored window contains **two** deployments — the
scorer's revert of ADR-0103 *and* this brake — because the running JVM predates the revert. Gross will
fall for both reasons and the split will not be cleanly attributable; do not read the whole move as this
change. (ii) The brake is silent for its first 30 planning cycles (fifteen minutes at the 30s cadence)
after each restart, so roughly half of a measurement window is unbraked. Both are in the deferred
register rather than papered over.

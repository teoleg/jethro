Stopped the desk liquidating a whole position every time its mean-reverting forecast crossed it: an exit is what a risk control ORDERED, not what the intent arithmetic happens to read (ADR-0107).

*Every figure below is quoted from the live endpoints, the report or the ledger; none is computed here.
The ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** The report's SITUATION header reads total PnL `$922.59`, the window at `+25.40` and the
last three runs at `+67.26`; `run-status.json` reads `pnl_growth_pct 11.96` against a `1.0` target, with
`on_track` true, `stale` false, `underwater` false. Live `/api/risk` `.total` has since read
`923.08648774`. The book is **not** bleeding and is well ahead of the owner target. `/api/attribution`
splits it `ALPHA +1,255.29`, `MACRO +376.996` (unchanged to the cent for a **twelfth** cycle),
`HEDGE −709.20`, fees `$465.78`.

**2. Risk.** The report shows gross `$0.00` / net `$0.00` — the book was completely flat when the report
was cut, six minutes after a restart. Live gross has since read `$2,677.67` against net `−$2,631.36`.
Breaker `halted: false`; VaR reads `"note": "no positions"`. Exposure is falling, not rising: the header
records `−9,680.61` on the window and `−19,777.96` over three runs.

**3. Cause.** Last cycle's change (ADR-0106, `0f7d25a17`) scored **⚠️ MIXED** — `$899.05 → $922.59` for
gross `$12,139.06 → $0.00`. It was strictly one-way by construction and cannot have added exposure, so
the PnL rise is a genuine improvement and the collapse to flat is the book unwinding, not the change
adding risk. No culprit.

**4. Danger — none, and I checked rather than assumed.** Not bleeding, exposure falling, breaker clear,
firm gross a fraction of a percent of the declared limit. De-risking would be the wrong move this cycle.

## The real problem: the desk holds almost none of the book it plans

This is where I spent the cycle, because the flags are green and the money is not moving much.
`/api/fusion/targets` plans 23 names — `SAP 245`, `GOOGL −469`, `BRK.B 366`, `ORCL 457`,
`EURUSD −66,100` and so on, hundreds of thousands of dollars of intended notional — and reports
`deltaQty` of **exactly zero on every equity name**, against a live gross of `$2,677.67`. The desk is
carrying a rounding error of its own plan while filling `3,004` orders and paying `$403.17` of ALPHA
fees against `$1,255.29` of ALPHA P&L. A quarter of what the strategy book makes is going to the cost of
getting there, and it never reaches the size at which its edge could pay for that.

`signals_telemetry` says the edge is real and not a blip: `reversion` measures `+8.58` bps at the 900 s
rung on `500` resolved observations over `81` cohorts (hit rate `0.855`), and `+9.48` / `+6.42` at the
other two rungs — the same sign, the same order of magnitude, three horizons, thousands of observations.
Every other source measures significantly negative and sits pinned at the ADR-0097 `MIN` weight of
`0.25` against reversion's `2.19`. The desk has one good, well-evidenced, mean-reverting source and is
failing to hold it.

**5. Order-level post-mortem — the mechanism.** The tape is the giveaway. AAPL is bought `1, 3, 3, 4, 5,
7, 8` in small rated steps and then sold `22` and `13`; JNJ is bought `19, 9, 28, 6` and then sold `14`,
`14`, `34`; JPM the same shape. Small accumulations, one large reversal. Reading the code: ADR-0090
established that a change of view is a rebalance and only a **flat target** is an exit, but ADR-0094
moved order derivation into `PositionBuffer.bufferedDelta`, which keys the full-speed unbuffered branch
on `aim.signum() == 0`. That was faithful until ADR-0102 began clamping an inverted intent to flat in
one step — which, with a mean-reverting source, is the steady state. So every crossing of the forecast
over the held position is read as a cut and dumps the whole position in one MARKET order. ADR-0102's own
worked example records the dump (`gap = 0 − 104 ⇒ delta = −104.000000`) as intended behaviour. The clamp
is right; calling its output an exit is not.

It is worse than a wash because of ADR-0084's asymmetry: an entry POSTS and pays no spread, the
crossing-liquidation CROSSES and pays the full spread on everything it built for free.

**6. Memory.** `docs/loop-findings.md` says the hedge has been intervened on four times in five cycles
(two BAD) — I left it alone. It also says not to re-attempt ADR-0087's removal of the source-weight MIN
floor; I did not, although the four measured-negative sources holding `1.00` of weight against
reversion's `2.19` remains the obvious next lever.

**7. Change vs market.** Cleanly separable this cycle: ADR-0106 could not add exposure by construction,
and the exposure move is the book unwinding to flat across a restart. The `+25.40` is realised P&L on
positions the change did not open. I credit ADR-0106 with the hurdle correction, not with the P&L.

## What I changed

`PositionBuffer.bufferedDelta` now keys the unbuffered branch on the **target** being flat — how every
risk control actually orders an exit — and works the part of a gap that crossed flat in one step at the
ADR-0080 derived rate. Strictly one-way, asserted over every held/aim/target sign combination: it can
never open or enlarge a position, never slow a cut a control ordered (a flat target still returns
unbuffered and unrated, so the ADR-0086 stop, the ADR-0065 unwind, the ADR-0027 breaker and the
guardrail are untouched), and never slow a same-side de-risk. No dial and no number introduced.

**The honest trade-off, stated up front:** positions will persist longer, so average gross exposure
should rise. The claim under test is that the turnover saved plus the edge captured over the horizon the
gate priced exceed the carry. If exposure grows and P&L does not, the scorer will mark this ❌ BAD and
revert it, and that is the correct outcome.

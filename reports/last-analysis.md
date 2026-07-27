The desk's two worst names are its two most expensive to trade, and six more names quoting the same 20 bps touch are queued in the planned book — so a name now pays its own quoted round trip before its first fill, not the blend of the names the desk already trades (ADR-0099).

## Situation — every figure below is quoted from the live endpoints; none is computed here

**1. Money.** Total PnL `$467.95` on `/api/risk` `.total`. The SITUATION header puts the window at
`+133.49` and the last three runs at `+234.95`; `run-status.json` has `on_track` true with
`pnl_growth_pct` far above the 1% target, `stale` and `underwater` both false. The book is **not
bleeding**. `/api/attribution` splits it `ALPHA +641.96`, `MACRO +376.98`, `HEDGE −550.99` — MACRO is
unchanged to the cent for a fourth cycle (frozen, position ~0), so the live desk is ALPHA plus the
hedge overlay.

**2. Risk.** Gross exposure `$58,663.42`, net `−$1,087.77` — the firm is very nearly beta-flat and
still making money, which is the structure working. Gross **fell** `−3,245.34` on the window, against
`+44,191.91` over three runs. VaR95 `$594.96`, ES95 `$760.90`; the breaker reads `halted: false` and
firm gross sits far under Oleg's `1,500,000` limit. Not a danger state on any of the four tests.

**3. Cause.** Last cycle's ADR-0098 hedge-churn shrink scored ✅ **GOOD**. Live confirmation:
`/api/hedging` now reads `status: ON-TARGET`, `held −0.070036 → target −0.085113 ES — largest delta
under the 5785.79 no-trade band, holding`, with `churnSigmaUsd 3187.26` trimming a raw
`−$26,330.51` target. The hedge has stopped churning, and gross fell on the window. It remains the
worst book, but its window loss is a fraction of the desk's window gain.

**4. Danger.** No — not bleeding, exposure not rising, breaker far away. So this cycle is a
risk-adjusted-return problem, not a de-risking one. And that is where the deterioration is: the
ledger's own risk-adjusted column has gone `0.01627` at `$14,470` of gross to `0.00798` at `$58,663`
over three runs. The desk is scaling gross faster than it scales PnL.

## Diagnosis — the mechanism, from the order-level post-mortem

The window's fills are almost entirely ALPHA in `AAPL/GOOG/JPM/MSFT/JNJ`, the profitable core. The
losses are in two names the desk has since closed: `GOOGL −$161.84` and `SAP −$85.46` — together more
than half of what the whole firm has made. They are also, by a wide margin, **the two most expensive
names the desk has ever filled**: `/api/fusion/targets` `.edgeGate.roundTripBpsByInstrument` reads
`GOOGL 20.11` and `SAP 8.16` bps against every other name at `0.41–2.09`, and the passing source's
measured net edge is `+9.07` bps at `t = 9.32`. A 20 bps round trip against a 9 bps edge loses on
every trip; no signal quality repairs that.

The trigger is structural, not bad luck. ADR-0075 tests each name against **its own** measured round
trip — but a name is only measured *after* it has traded, so an unfilled name is charged the desk
**blend** (`1.48` bps). The desk therefore enters every new name believing it costs the average of the
names it already trades, and pays for the correction out of PnL. The veto works; it arrives one
discovery loss too late. And the same trigger is loaded again: six names the desk has **never** filled
(`BRK.B`, `NFLX`, `ORCL`, `GS`, `TSLA`, `GOOGL`) all quote a `20.0` bps touch, and the current planned
book wants roughly `$120k` of gross in them.

The missing input was never missing — the live `QuoteCache` has carried bid/ask per name since
ADR-0025 and the cost model has simply never read it.

## The change (ADR-0099, Proposed, same commit)

A name with no measured round trip is charged the one its **own live quote** implies —
`20000·(ask−bid)/(ask+bid)`, one full touch per round trip — floored at the blend it is charged today.
Measured TCA always wins where it exists, so this is only a bootstrap. It is **strictly one-way**
(`max(blend, quoted)`): no hurdle can fall, the gate's test is monotone in cost, so it can only ever
remove a trade. The desk-wide verdict is provably untouched, since that verdict is taken at the
cheapest cost in map-plus-blend and nothing added is below the blend. No new dial and no new number.

Checked against the desk's own TCA the estimator lands at ratio `0.99` on GOOGL and `0.98` on SAP —
exactly the wide names, where ADR-0084's passive limit is superseded and the desk crosses — and errs
conservatively (`1.5–2.1×`) on the tight names, where it does not. Feed-agnostic by construction.

## Attribution honesty

The window's `+133.49` sits mostly on ALPHA positions held across several cycles in names this cycle's
change does not touch; I cannot separate that from the market's own move on the numbers alone, and I
am claiming none of it. What **is** attributable to last cycle's change is the hedge going quiet
(`ON-TARGET`, delta inside its band) and gross falling on the window. The `$247.30` lost in GOOGL and
SAP is not market noise — it is the measured cost of two round-trip-expensive names, and it is the
thing this cycle removes going forward.

The desk is priced out of its own universe: the cost an unmeasured name is charged is dearer than the edge can pay, so a name that has never filled can never fill — fixed by pricing it from its own prints (ADR-0112).

*Every figure below is quoted from the live endpoints, `logs/report.md` or the ledger; none is authored
here. The ledger's numbers are the scorer's.*

## Situation triage

**1. Money.** Total PnL **$5,654.20**, **−$0.03** since last run and **−$83.16** over the last three.
`run-status.json` reads `pnl_growth_pct −1.25` against a `1.0` target — `on_track` false, **`stale`
true**. The BLEEDING flag is technically right and practically meaningless: −$0.03 on a book holding
nothing is a rounding artefact, not a bleed. The three-run figure is the real one, and it is negative.

**2. Risk.** Gross **$0.00**, net **$0.00**. `/api/var` reads `"note": "no positions"`, `/api/breaker`
`halted: false`. All ten position rows read `quantity: 0`, every P&L realised, `unrealizedPnl 0.00`
across every book. The desk went flat at 19:54:38 (the last row in `recent_orders`, `HEDGE ES SELL`)
and has placed **nothing** in the 66 minutes since. Zero risk, and therefore zero opportunity.

**3. Cause — last cycle's change DID reach the JVM, and it is not the culprit.** `/api/ops/jvm` reads
`uptimeSeconds 2224` against a report stamped `1785186002053`, i.e. a boot at **20:22:58Z**; ADR-0111
committed at **20:22:10Z**. It is live, and visibly so: `/api/fusion/targets` now publishes
`momentum: 0.0, trend: 0.0` against `reversion: 2.900`. Scored **⚠️ MIXED**, −$0.03 / ±$0.00 — no
material change, which is the honest verdict. Its own ADR named the next lever correctly: *"REDUCING
ROUND-TRIP COST is the next lever and no re-weighting substitutes for it."*

**4. Danger.** No. Bleeding-plus-adding-risk is the state to fear and this is its opposite — a book at
zero that cannot get back on. De-risking here would be de-risking nothing. Rule 18 applies in reverse.

**5. Order-level post-mortem.** The window's orders are two names: **AAPL** (a 74-share BUY at 19:54
closing the last short, 3.8862 bps of slippage — an aggressive exit, as ADR-0084 intends) and **JPM**,
plus ES hedge clips. `orders_by_status` reads FILLED 3084 / CANCELLED 553 / REJECTED 45; every
cancellation carries `fusion re-plan — passive order superseded by a fresh target (ADR-0084)` and every
rejection `no market data for JPM`. No trigger opened a loser this window because **no trigger opened
anything** — which is the finding, not the absence of one.

**6. Memory.** Rule 19 (`deltaQty: 0` on a live target book is a BLOCK, read the `aims`) and Rule 20
(two individually-correct controls composing into a halt) are exactly what fired again, one rung lower
down. Last cycle's closing line said the binding constraint was execution cost, not re-weighting. It
was right, and this is that change.

**7. Change vs. market.** Cleanly separable this window, because there were no positions: the −$0.03 is
**neither** — it is fee/FX dust on a flat book, not a market move and not an effect of ADR-0111. The
−$83.16 over three runs is dominated by the 19:00–20:00 window in which the book was flattened while
holding $10.4k gross, i.e. realised P&L crystallising, not this cycle's code. **ADR-0111 gets credit
for nothing and blame for nothing.** Its real effect — narrowing the vote to one source — is visible in
the target book (3 of 4 live names now combine to exactly `0.0` because `trend` is their only voice),
but that did not cost money this window; it removed breadth the cost gate was already refusing.

## The mechanism

Reading `/api/fusion/targets` live rather than the narrative: `mayIncrease: true`, and yet every `aim`
reads exactly `0.0`. The desk-wide gate is open and every single name is reduce-only. The arithmetic is
entirely mechanical:

- `reversion` clears at the ADR-0082-selected 225 s rung — `avgReturnBps 1.913961`, `stdErrorBps
  0.403198`, `t = 3.68`. So the **most a name may cost and still clear `t ≥ 2` is 1.107565 bps**.
- The **blend an unmeasured name is charged is 1.326575 bps** — *dearer than the hurdle*.
- Of 27 names with a live mark, **8** carry a measured round trip, and only **ES (0.430), AAPL (0.897)
  and JPM (1.075)** sit under 1.107565. Everything else is shut.

That is a **closed loop**: a name is measured only after it fills; an unmeasured name is charged the
blend; the blend fails the hurdle, so it is held reduce-only, so it never fills, so it is never
measured. The tradable universe has frozen to whichever names happened to have filled before the hurdle
tightened — and ADR-0099, which exists to break exactly this loop, is **inert on this feed**: every one
of the 27 marks carries `bid: null, ask: null`, so the quoted rung has nothing to read.

## The change

**ADR-0112 (Proposed, shipped): a third rung on the per-name cost ladder.** A name with neither a fill
nor a quote is charged the effective spread **its own print series implies** — Roll's implicit
estimator, `s = 2·√(−cov(rₜ, rₜ₋₁))` (Roll, *Journal of Finance* 39(4), 1984), which is one round trip
in exactly the sense the other two rungs use the term. Floored at the cheapest round trip the desk has
**actually paid anywhere**: it may infer that an unfilled name is cheaper than the average of the names
it already trades, never that it is cheaper than anything it has ever executed.

That floor is also the safety proof. `EdgeGate` takes the desk-wide verdict at the cheapest entry in
the map, and no entry this rung adds can be below it — so the desk-wide verdict, every source's
`netEdgeBps` and the ADR-0101 buffer's reference cost are **bit-for-bit unchanged**, and only a
per-name hurdle moves. It cuts both ways: a name whose own tape reads *dearer* than the blend is
charged more than today and stays shut. Where Roll's model does not hold (non-negative autocovariance —
a trending tape) it returns **no measurement** and falls back to the blend, rather than taking a root
of `|cov|`, which is the classic misuse of this estimator. Twelve tests, including the worked example
by hand and the desk-wide invariance. Full suite green.

**Honest cost, stated so it can be checked rather than re-derived:** this raises exposure by design.
If the reversion edge does not survive contact with the newly-admitted names, the loss is larger, not
smaller — and the scorer should say so.

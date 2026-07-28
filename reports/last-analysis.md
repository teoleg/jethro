Sized the one name we hold by how much its sensors actually agree — trend and reversion were fighting each other to a small residual, and the desk was taking a full-conviction position on the difference (ADR-0119).

*Every figure below is read from this run's `logs/report.md`, the live endpoints or the ledger; none is
authored here (invariant 7 / ADR-0016 — the scorer owns every number that gates money). The agreement
arithmetic quoted at the end is a JUnit assertion computed by code, not by me.*

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `$-0.05`, **up** `+$1.32` on the last run; **down** `-$1.24` across the last
three. Realized is fees only (`$0.04` on the two LIVE fills). Not bleeding this cycle, but underwater
and off the growth target — and the move is mark drift on two legs unchanged since 13:40:54Z, so it is
not attributable to anything the loop did.

**2. Risk.** Gross `$763.08`, net `$84.63`, gross **up** `+$1.12`. VaR95 `$8.26`, ES95 `$12.42`, breaker
`halted: false` — nowhere near it. The rise is mark drift, not sizing: `orders_day.total: 2`, both from
13:40Z. Two positions: short 1 AAPL (`$339.23`) and the `0.001136` ES hedge (`$423.86`).

**3. Cause — and last cycle's prediction is falsified.** ADR-0118 scored ⚠️ MIXED and its branch did
**not** fire. I predicted the desk would buy back 1 AAPL and go flat; it did not, and the reason matters
more than the miss: AAPL's aim **flipped from `+6.031064` LONG to `−14.915227` SHORT** in one
thirty-minute cycle. The aim is now on the *same* side as the holding, so ADR-0118 correctly stayed
out. The branch is not broken — the input to it is unstable.

**4. Danger.** No. Exposure is not rising by decision and the breaker is far away. The real problem is
the opposite of a crisis: the desk is **frozen and off-target**, carrying `$763` of gross across two
legs to earn mark noise minus fees.

## Attribution — change vs market

**100% market, 0% change.** Zero orders this window; both legs are the same size they were four hours
ago. Nothing in the PnL or exposure move is mine to claim or be blamed for.

## What I actually found

Why did AAPL's aim flip sign in thirty minutes? Because it is the residual of two sensors fighting:
trend `+11.222357223010645` against reversion `−10.408272759827817`, netting to `−1.5229724354072096`
after a diversification multiplier of `1.1497986707349463`. **Every other name in the cross-section had
its sources pointing the same way** — AMZN both short, NVDA trend-dominated, and so on. The one name the
desk held was the single name it understood least, and it was sized as if it understood it best:
`−14.915227` shares off that residual, then an ES hedge bought against it, so gross exposure was paid on
both legs for a view that did not exist.

The mechanism: only the **mean** reaches sizing. Two sensors agreeing quietly on +1.5 and two sensors
fighting to a net +1.5 produce the same position, despite nothing like the same confidence about the
sign. The averaging step shrinks the posterior mean under conflict — correctly — but nothing was
widening the posterior variance. Worse, the ADR-0076 multiplier then scaled that residual back *up*, on
a diversification assumption the disagreement itself contradicts.

## What I shipped (ADR-0119)

The combined forecast is now multiplied by the sources' **agreement** — `|Σwᵢfᵢ| / Σwᵢ|fᵢ|`, the share
of their gross conviction that survives as a net view. It is the efficiency ratio the desk already uses
over *time* (ADR-0113 for trend, ADR-0100 for the hedge's own path), read *across* sources instead.

No dial, no threshold, no number needing provenance: it is a ratio of the running sums the average is
already built from. Strictly one-way by the triangle inequality — the value can only shrink, never grow
— and **exactly 1 whenever the contributing forecasts share a sign**, so every agreeing name and every
single-source name is byte-identical to before. A sweep over the whole forecast grid asserts the bound,
the exact-1-on-same-sign property, shrink-only and sign preservation. The scalar is surfaced on
`/api/fusion/targets`, because it was always *derivable* from the published contributions and neither I
nor the owner derived it for two cycles running — that is what made the AAPL diagnosis wrong twice.

**Honest cost, recorded so it is not forgotten.** Trend and reversion are *structurally* opposed at
different horizons, so a real trend fighting a real reversion is sized down even when one of them was
right. That trades expected return for a lower variance of being wrong about the sign — the correct side
of that trade while measured LIVE expectancy is negative, but it should be revisited if the edge gate
opens and the book comes out systematically under-sized.

## Predicted next, so it can be checked rather than re-derived

AAPL's target shrinks from `−14.915227` toward roughly an eighth of that (agreement `0.12321282549722257`
on this cycle's readings), which is still same-side and still larger than the 1 share held, so **I expect
no order and a ⚠️ MIXED score again**. The check is `/api/fusion/targets`: an `agreement` field should
appear, near `1.0` on AMZN/NVDA/JNJ and far below it on AAPL, with AAPL's `targetQty` an order of
magnitude smaller than `−14.915227`. If the aim keeps converging on cancellation, ADR-0118's exit
branch becomes reachable and the two legs unwind — that is the mechanism by which this de-risks, and it
is a next-cycle outcome, not this one. If `agreement` reads `1.0` on AAPL, the sensors have stopped
disagreeing and this diagnosis has expired.

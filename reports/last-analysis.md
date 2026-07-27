Both price sensors are running ~1.5× the scale they claim, so over a third of the cross-section is pinned at the forecast cap — eleven names reading the identical +20 are one number to the planner; the desk now measures each source's own scale on the stream and rescales it back, one-way so it can only shrink the book (ADR-0092).

## Situation (read from the live endpoints; every number below is quoted, none computed here)

1. **Money.** Total PnL is **higher** for the third run running — the situation header reads `-91.34`
   against `+41.42` on the window and `+294.75` over the last three. Still `UNDERWATER` in absolute
   terms, but the 3-iteration growth target is cleared many times over and the book is **not bleeding**.
2. **Risk.** Exposure **fell hard**: gross `18,920.07` against `-36,084.08` on the window and
   `-60,236.92` over three runs; net `2,354.41`, down from `22,272`. Historical VaR95 is `153.18`,
   ES95 `237.87`, the breaker is untripped and the firm gross limit sits two orders of magnitude above
   the book. Nothing is near binding, and the trajectory is the right way for once.
3. **Cause.** Last cycle's change (`39a30344c`, ADR-0091 — stop netting the fusion target against the
   hedge overlay) scored **⚠️ MIXED** and stays in. Read honestly, the MIXED is a deadband artefact:
   gross fell by more than two-thirds and net by 95%, and PnL rose `+46.87`, three dollars under the
   `$50` noise band that would have made it ✅ GOOD. **This is the change's own doing, not the market** —
   what unwound is precisely the double leg it stopped creating, a position move it directly caused. The
   PnL half I do *not* claim: at `+46.87` against a `$50` deadband it is inside the noise the ledger was
   built to ignore, and I am not going to read a win into it.
4. **Danger.** No. PnL rising, exposure falling, breaker far away — the opposite of a danger state, so
   this cycle goes at a mechanism rather than de-risking.
5. **Order post-mortem.** ALPHA's window is a directional slice, not churn: JNJ/AAPL/GOOG/JPM sold and
   MSFT bought in the same direction cycle after cycle, working toward a distant target. Cross the
   fills against per-name PnL and the split is **cost, not view** — the winners (`AAPL +146.99`,
   `MSFT +55.80`, `JPM +19.25`, `JNJ +6.72`) are exactly the names whose measured slippage is
   `0.59–0.72 bps`, and the losers (`GOOGL -161.84`, `GOOG -157.76`, `SAP -85.46`) are the three worst
   round trips on the desk (`10.05`, `2.07`, `4.08 bps`). The ADR-0072/0075 per-name cost gate has since
   flattened GOOGL and SAP, so that trigger has already been fixed and is not this cycle's target.
   I also checked the fee story directly against the fills table rather than trusting the cumulative
   figure: the `297.01` in `/api/attribution` is **two SIM days**, and the live regime is `$10.40` of fees
   on `$93k` traded in 25 minutes. Turnover is no longer the emergency it was three cycles ago — which
   is why I did not go after it again.
   Still on the book and still not free: `MACRO -0.002530 ES` against `HEDGE +0.002526 ES`, two legs in
   one instrument that cancel at the firm level for `$1,378` of gross. The pair nets `+92.22` and costs
   about a dollar a cycle, so it is logged, not chased.
6. **Memory.** Applied: do not re-attempt the source-weight floor (ADR-0087) or aim-smoothing
   (ADR-0088) — both reverted for growing exposure with no PnL. The standing item the findings file has
   carried for six cycles is **forecast saturation**, and it is what I took.

## Diagnosis and the change

The mechanism, measured on the live target book rather than inferred: the trend source's readings
average `E|f| = 15.17` against the `10` its mapper assumes, with a **median of 18.86 and 11 of 23 names
pinned exactly at the ±20 cap**; reversion averages `14.68` with 6 of 23 pinned. Every one of the four
phase-2 scaling constants is a *claim* about how big a source's readings are, and nothing has ever
checked one. Two costs follow, and the second is the expensive one. Sizing is linear in the forecast,
so an inflated scale is an inflated book (`$381,464` planned against `$18,920` held) and an inflated
turnover bill to grind toward it. Worse, **a clipped forecast carries no cross-sectional information**:
eleven names reading the identical `+20` are one number to the planner, so the source has degenerated
from a forecast into a sign function and the selection information that justifies running 23 names
instead of one is gone. That is the concrete mechanism behind the finding this file has been recording
as "every name points the same way".

So: measure it. A new pure `ForecastScalars` keeps an expanding mean of each source's |claim| — Carver's
forecast-scalar step, estimated on the stream instead of taken on trust — and the registry publishes
`clamp(claim × TARGET_ABS / max(TARGET_ABS, mean|claim|))`. Expanding rather than decaying because the
estimand is a property of the sensor, not a regime, which also means no half-life to invent; measured on
the claim and not on the output, so it cannot feed back into its own estimate; and **one-way** — an
over-delivering source is scaled down, an under-delivering one is left alone rather than levered up on
an estimate, because the two changes this loop has had reverted were both books that grew on an
estimate. When a sensor keeps its promise the whole thing is a no-op. Worked example pinned as a test:
at a measured `E|claim| = 15` two names claiming 18 and 24 used to size 900 and 1,000 shares — an 11%
gap on a reading 33% stronger — and now size 600 and 800, the full 33% expressed and both smaller. A
claim of 45 still clips: the cap is un-jammed, not removed.

Expected effect, stated in advance so the ledger can contradict it: gross down as the planned book
shrinks toward the size `unit-notional-usd` was actually set for, turnover down with it, and the
cross-section finally sizing on evidence rather than on the cap. If gross does *not* fall, the suspect
is not this change but the ADR-0089 stream covariance, which the boot log shows still cold — the
concentration multiplier and volatility budget are both no-op until it warms.

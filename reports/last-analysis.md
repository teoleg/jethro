No change — `c20fb0b70` is at 4 of 6 measurement cycles; and I am retracting my own headline claim from last cycle, because the "we fill our weakest forecast" gap disappears once exits are excluded from the comparison.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `recent_orders`,
`turnover_cost_by_name`, or the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

**Situation.** Total PnL **$-234.56**, gross **$55,978.97** (3.7% of the firm cap, $1,444,021 headroom),
net **$-44,089.50**. Run-over-run PnL **-44.70**; over three runs **-160.13** with gross **+4,574.51**.
UNDERWATER and off the +1%/3-iteration target. Not DANGER: the book is nowhere near the exposure cap or the
drawdown breaker, so rising gross is the intended direction, not a risk event to de-risk into.

**Step 0 — the pending change.** `scripts/score-change.py score` reports `c20fb0b70 still accumulating
evidence (4/6 cycles)` and `.pending-baseline.json` is still present, so it is held, not scored, and the
contract forbids stacking a new change on top of it. Deployment is confirmed on behaviour for a third
window: the restored ADR-0135 branch fired as `fusion exit — target decayed to flat [sources=0]` on XOM and
JPM (17:37:13Z) and `[sources=1]` on NEE (17:39:15Z). Recorded against it: NEE is now flat at
`realizedPnl -25.58`, and JPM's 46 shares were flattened by that branch and have left the position table.
Not graded a regression — a revert restores prior behaviour by construction and the counterfactual is
unknowable — but not called costless either.

**The correction.** Last cycle I ranked item #1 on the claim that the 30s re-plan cadence *systematically
fills the desk's weakest forecasts*. Re-tested on this window, it does not hold. Pooled over all ALPHA
orders, filled `|forecast|` averages `5.685` against `8.579` cancelled — a large adverse gap. But exit
orders carry `forecast=0.0` and always fill, so including them manufactures that gap; restricted to
`fusion entry` orders it inverts to `8.698` filled vs `8.579` cancelled. Within-name the sign is consistent
(5 of 6 names) but the gaps are `0.15`–`1.43` on 1–3 fills each — noise at this sample size. That was my own
bad denominator, the same error Rule 259 was written about. The register carries the retraction.

**What survives, and why item #1 stays #1.** The cost is unambiguous: `totalFees 334.40` against
`firmTotal -234.56`, with ALPHA paying `324.01` in fees on `-304.50` of PnL while HEDGE is the only book
earning (`+126.75` on `9.28` of fees) — gross of fees the desk is up. And the execution failure has a
cleaner proof than the one I retracted: `27 of 59` order rows are supersession cancels (up from `22/60`),
and **four names — NVDA, HD, PG, PFE — placed entry orders at forecasts of 5.4 to 6.5 and filled none of
them**, every one cancelled before it could execute. The defect is not "we fill our worst view"; it is "a
name's view can fail to reach the market at all", while the names that do trade pay ~2 bps a round trip.

**Decision.** No code change this cycle — `c20fb0b70` must finish measuring. Once it scores, the one change
targets letting a passive entry order live long enough to fill on the view that placed it, verified on three
numbers together: supersession cancels below `27/59`, zero-fill names below `4`, and gross exposure not
falling.

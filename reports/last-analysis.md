The hedge is losing more than the whole desk makes — and it is losing directionally, on a beta-weighted net that is 0.5% of the book (ADR-0098).

## Situation — every figure below is quoted from the live endpoints; none is computed here

**1. Money.** Total PnL `$327.26` on `/api/risk` `.total`. The SITUATION header puts the window at
`+88.17` and the last three runs at `+142.58`; `run-status.json` has `on_track` true with
`pnl_growth_pct` far above the 1% target, `stale` and `underwater` both false. The book is **not
bleeding**. But the composition is the story: `/api/attribution` splits it `ALPHA +458.94`,
`MACRO +376.99`, `HEDGE −509.21`. The two strategy books make `+835.94`; the hedge hands back
**61% of it**.

**2. Risk.** Gross exposure `$37,162.38`, net `$6,366.73`. Gross moved `+718.89` on the window
against `+36,404.57` last window — the six-run exposure ramp has **stopped**. VaR95 `$347.51`,
ES95 `$474.70`, VaR99 `$550.54`; the firm drawdown breaker is `halted: false` and nowhere near
Oleg's `max-firm-drawdown`. Not a danger state: PnL rising, gross flat, breaker far away.

**3. Cause — last cycle helped, and it is measurable.** `8cfad322b` (ADR-0097) scored ⚠️ MIXED with
risk-adjusted PnL improving. `fusion_targets` now reads `reversion 2.091` with every other source
pinned at the `0.25` MIN — exactly what the change was built to do — and the ramp it was aimed at is
gone. Credit is real, and it is a change effect rather than the market: the flag it silenced had
fired on six consecutive commits with different content, and it stopped on this one.

**4. Danger.** No. So the right move is the largest standing drag, not de-risking.

**5. Order-level post-mortem.** `recent_orders` is ALPHA working AAPL/JPM/JNJ/GOOG/MSFT, plus **one
HEDGE ES order every cooldown, alternating BUY and SELL**, `0.0009`–`0.011` contracts each. The
hedge holds `−0.001148 ES` — about `$312` — and trades several times that every minute. Its target,
per the `/api/hedging` rationale, is `Σβ·E = −$195.49`: against `$37,162` of firm gross the
beta-weighted net is **0.5%**, i.e. the strategy book is already nearly beta-neutral and the hedge is
chasing a number smaller than the amount that number moves between one hedge and the next.

**6. The loss is not cost.** HEDGE fees are `$40.02`, and `tca` shows 355 ES fills at `0.204` bps —
under `$50` all-in against `−$509.21`. The other `~$460` is **directional**. That also kills the
lever the findings memory had queued (widen the ADR-0069 band): trading the same wrong position less
often leaves most of the loss in place.

**7. Why the direction is adverse, not unlucky — change vs market.** The target is minus the
beta-weighted net of a book whose one gate-passing source is `reversion` (`+10.24` bps at the 3600s
rung, hit rate `0.845`). A reversion book is short after a rally and long after a selloff, so
`−Σβ·E` is **long the proxy after a rally and short after a selloff** — a momentum position on ES,
taken on `trend`, the one view the desk measures as significantly negative at every rung
(`−8.61`/`−6.51`/`−3.51` bps). Sized off a real exposure that is the price of risk control; sized
off churn it is a losing bet controlling nothing. The HEDGE line has gone `−323.33 → −451.51 →
−509.21` across the last three cycles — monotone, and independent of what changed above it — so this
is a standing mechanism, not this window's market. Conversely the window's `+88.17` sits on ALPHA
positions the ADR-0097 weight change directly re-signed, so that part is a change effect; the MACRO
book is unchanged to the cent again and contributes nothing either way.

## Change

**ADR-0098 (Proposed, same commit).** The hedge now neutralizes only the systematic exposure that
stands clear of its own churn: `T' = sign(T)·max(0, |T| − k·σ)`, where `σ` is an EWMA(`λ=0.94`, the
decay `CovMath` already uses) of the hedge target's **step between the moments the hedge can act**,
sampled once per cooldown. Strictly one-way — the magnitude can only fall and the sign can never
flip — so an over-estimated `σ` can never lever the book up. A real hedge is untouched (the ADR-0038
`$456,000` example at `σ = $900` trims 0.2%); a target inside its own churn is set flat and the
residual is unwound by the ordinary delta path. `σ` is measured from the stream, not dialled, so it
carries to a live feed unchanged. Deliberately **not** the ρ² route (ADR-0095, already reverted) and
not a hand-set dollar floor. Green on `./gradlew -Pci test`.

**Expected next:** the HEDGE book's fee line and its directional bleed both fall, with firm gross
slightly lower. If HEDGE is still materially negative next cycle, the remaining question is the one
this ADR deferred — whether a mean-reversion book wants a beta overlay at all — and not another
tweak to how the overlay is sized.

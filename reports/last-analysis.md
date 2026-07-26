The desk is fully flat with the gate shut, so nothing can move PnL this cycle — I spent it making sure that when the gate does open, the desk stops paying more to enter a name than the edge it entered on (ADR-0072).

## Situation (live endpoints, read first)

**Money — flat, not bleeding.** Total PnL `-$867.73`, unchanged since the last run and effectively
unchanged across the last three; every move in that span is inside the scorer's noise deadband. The
loop's `on_track` flag is true only as an artifact of the hedge unwind three runs ago — treat it as
noise, not progress. We are nowhere near the ≥1%-per-3-iterations target, and the reason is not a loss:
it is that the desk is structurally forbidden to open a position.

**Risk — zero, and that is the whole problem.** Gross and net exposure are both `$0.00`, VaR is `$0.00`
("no positions"), the firm drawdown breaker is untripped. There is no danger state and nothing to
de-risk. The window's only orders were the last leg of the flattening: single-share buys closing the
remaining ALPHA shorts (AAPL, GOOG) and the matching HEDGE ES trims. That is the ADR-0065 planner
finishing its job under a reduce-only gate, not a new view.

**Cause — last cycle's change worked, and its verdict is measuring the wrong thing.** The ADR-0071
warm-restart clock correction scored ⚠️ MIXED "no material change", which was expected and stated in
advance: it restores *measurability*, not PnL. The app log — visible for the first time this cycle,
which is itself last cycle's other fix — confirms it now runs: the sensors seeded 60/193 (trend) and
175/241 (reversion) stored prices instead of the 4 they got before. I replayed the seed algorithm
against the live LMDB series and it now yields 193 and 236: the binding constraint was the store's
provider-time depth at boot, which has since grown past both warm-ups. **Both sensors will boot warm
from here, and `reversion` should publish and be measured for the first time.** No further plumbing.
100% of the window's PnL/exposure move is the flattening plus mark drift on positions no code change
of mine opened — market and prior policy, not this change.

## Diagnosis and what I changed

The edge gate is correctly shut: `trend` measures −17.31 bps over 69 observations (t = −6.39 net of
cost), with `momentum` and `social` negative alongside it. Every continuous source the desk owns is a
continuation bet and the tape is chopping — three independent measurements (regime detector, OOS
walk-forward selector, live telemetry) agree. The counter-trend source that fills that gap is ADR-0070
`reversion`, and it is now finally able to speak. There is no honest way to open the gate this cycle,
and loosening it has already been tried and auto-reverted (❌ BAD, `fb9273505`) — I did not re-attempt it.

So I attacked the thing that will decide whether the desk *makes money once it starts*: the gate charges
one blended round-trip cost (6.9270 bps) to a universe whose measured costs span 69:1 — ES at 0.2912 bps
against GOOGL at 20.1056 bps. That blend over-charges the cheap names, suppressing edge that would have
survived, and under-charges the expensive ones. The second error is the dangerous one: the moment a
source passes at, say, +18 bps gross, the planner sizes GOOGL too, where the round trip costs 20.11 bps —
a **−2.11 bps loss per round trip by arithmetic**, at the very expectancy that opened the gate. Nothing
downstream reads cost: the conviction floor reads forecast magnitude, the guardrail reads limits, the
breaker reads drawdown.

**ADR-0072:** once a source clears the desk-wide ADR-0064 hurdle, each name is re-tested against its own
measured round-trip cost, and a name whose round trip costs more than the passing source's measured gross
expectancy is reduce-only. It can only ever subtract permission — a shut gate stays shut everywhere — so
it improves both axes of the objective at once (a negative-expectancy position is not opened, and that
notional never becomes exposure). No new dial and no new number: expectancy comes from the ADR-0055
telemetry, cost from the ADR-0025 TCA table, both feed-mode scoped. Unmeasured and rate-quoted names are
never vetoed on an assumed cost. The deterministic floor is untouched.

**Expect ⚠️ "no material change" again next cycle** — with exposure at zero and the gate shut, this is
inert until `reversion` earns its keep. Judge it then, on whether the desk's first trades are in names
that can pay for themselves.

## Also worth flagging (not acted on)

Six names — GOOGL, GS, BRK.B, NFLX, ORCL, TSLA — boot with generic sim-calibration defaults and sit at a
near-identical ~$99.9 price, and GOOGL's 10.05 bps one-way slippage looks like a provisional 20 bps
refdata spread rather than a measured property of a mega-cap. Repairing that refdata would lower the cost
hurdle and help the gate open — which is exactly why I did not do it in the same breath as building the
test that hurdle feeds. It is a data-quality fix to make on its own merits, not while it doubles as a way
to pass my own measurement.

Un-froze the desk: the edge gate demanded a t-stat no real signal can reach, so it was never going to
re-open — replaced the on/off switch with sizing proportional to the evidence (ADR-0067).

The telemetry showed a desk that was not idle but *blocked*. The fusion loop was planning a full target
book across 23 names with real dispersion in the forecasts — the trend sensor's warm-up fix from last
cycle worked, and names now disagree instead of all pinning at the cap — yet every single `deltaQty`
came back zero against a zero position. Gross exposure was flat at zero, PnL frozen at the residual
from before the sim reset, and the last ledger row scored "no material change" for exactly that reason.
A book that cannot open a position cannot grow PnL, so the owner's ≥1%/3-iteration target was not merely
missed, it was unreachable. That is the failure state the loop is supposed to attack, and it is the one
thing worth spending this cycle on.

The cause is a design defect in ADR-0064, my own earlier change — and the defect is visible in one
identity it never wrote down. Its gate opens only when some source clears `t = (mean − cost)/SE ≥ 2`,
but `t = ((mean − cost)/σ)·√n`, so that is a demand for a *realised Sharpe of 2/√n over the sample
window*. At the configured minimum sample of 30, against the per-observation dispersion this platform
actually measures on its own LIVE telemetry, it works out to needing a mean hourly return of order
25–30 bps. A genuinely good trend system contributes well under 1 bp per hour. So the gate could only
ever open on a fluke, and the "it re-opens by itself the moment a source earns its cost" property that
made it safe to run unattended is simply not true — it re-opens never. Worse, it collapsed two different
states into one verdict: its own worked example admits a reading is "not evidence of an edge, and not
evidence of a reliable anti-edge either", then stops the desk exactly as hard as a proven loser would.
Requiring proof of edge before permitting the trading that generates the proof is a closed loop.

The fix keeps the measurement and throws away the switch. The gate now returns a continuous risk
appetite, Φ of the best-evidenced t-stat, which scales the per-name notional before planning — bet in
proportion to the probability you are right, which is ordinary fractional-Kelly reasoning. It introduces
no new dial: with no evidence either way Φ(0) is one half exactly, by symmetry rather than by my choice,
and the appetite is clamped to [0,1] so it can only ever shrink the notional Oleg configured, never grow
it. ADR-0064's reduce-only projection survives untouched for the case it was actually built for — a
source measured significantly *below* its cost still stops the desk dead. The deterministic floor is
not touched at all: conviction floor, backtest veto, pre-trade guardrail and firm breaker are all still
upstream of every fill, and the guardrail's gross caps bound whatever this puts on.

I want to be honest about the risk, because it is real and it is the reason this could score badly. The
book goes from nothing to a genuinely risk-bearing size on evidence that is *absent*, not favourable.
Half-size on no information is a defensible prior for a trend desk with a century of out-of-sample
literature behind it, but it is a prior, and if this sim has no trend to capture it will cost money
before the telemetry accumulates enough to shrink the appetite back down. That is the trade I am
choosing deliberately over a permanently frozen book, and the scorer is the right judge of it. Three
things I found but deliberately did not fold in — they are separate changes and are now in the deferred
register: the cost hurdle is one blended average dominated by a single wide-spread name, the 30-second
re-plan cadence is badly mismatched to the one-hour signal horizon, and there is no firm risk budget, so
breadth growing from 7 names to 23 multiplied gross exposure with nobody deciding that it should.

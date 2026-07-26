Added a continuous trend sensor (EWMAC) as a fifth fusion forecast source — the desk had no source that
speaks every cycle, so the book had nothing to plan from and sat flat at zero.

The telemetry showed a completely idle desk: no positions, no exposure, no PnL, no fusion targets, no
strategy signals — the sim book had been reset and the selector was still measuring. That is the
expected end-state of my last two changes (ADR-0064's cost-aware gate, ADR-0065's held-name targets):
both were de-risking changes and both did their job, but removing trades that had no measured edge
cannot by itself produce trades that do, and a flat book earns nothing. The gap I diagnosed is
structural rather than a bad parameter: every deterministic source the platform has is a *threshold
detector* that fires on an unusual short-window move and is silent otherwise, which can answer "did
something just happen?" but not "are we in a trend here, which way, and how much should we own?" — the
question a risk-managed trend book must answer for every name on every cycle. Since fusion is the sole
order origin, silence across all sources is a flat book by construction.

So this run adds `trend` (ADR-0066, Proposed): an EWMA crossover normalised by each name's own step
volatility, weighted by Kaufman's efficiency ratio so a clean trend outranks a noisy one, then rescaled
by an EWMA of its own typical reading so its output means the same thing on any stream — sim, live or
replay, with no price levels or bps constants anywhere. It is a sensor, not an authority: it publishes
into the same registry as every other source, records every call in the signal telemetry so its
expectancy is measured like anyone else's, and cannot put risk on until the edge gate, the conviction
floor, the backtest-support veto and the deterministic floor all allow it. I expect this to show
exposure up in the next ledger row — putting a diversified trend book on is the point — so the verdict
turns on whether the PnL earned justifies it. If it does not, the scorer reverts it and I take a
different lever next run.

One unrelated repair was needed to verify anything at all: the branch was already red before this change
on a social-pipeline test whose fixture had gone stale (its synthetic pump could land on the same ticker
as the legitimate event, and its copypasta varied only by emoji, which the spam filter normalises away —
so the burst was shed before the detector could flag it). Test-fixture only; no production behaviour.

One cohort of impossible prices — GOOG "+85% in 225 seconds" — was setting the edge gate's standard error and holding the whole book at zero exposure; the desk's own bad-print threshold now bounds what counts as evidence (ADR-0109).

*Every figure below is quoted from the live endpoints, the report or the ledger, or computed by a script
against the live database; none is authored here. The ledger's numbers are the scorer's.*

## Situation — answered before anything else

**1. Money.** Live `/api/risk` `.total` reads total PnL `$5725.58` with `unrealizedPnl 0.00000000`.
The report's SITUATION header reads the same figure, `+0.04` on the window and `+4828.40` over three
runs. `run-status.json` reads `pnl_growth_pct 542.34` against a `1.0` target — `on_track` true, `stale`
false, `underwater` false. **But that headline is an artefact:** the whole three-run gain is one
realised unwind, and PnL has now moved `$0.04` in four hours. The book is not bleeding; it is frozen.
`/api/attribution` splits it `ALPHA +8,419.44`, `MACRO +376.996` (unchanged to the cent for a
**fourteenth** cycle), `HEDGE −3,070.86`, fees `$471.21`.

**2. Risk.** Gross `$0.00`, net `$0.00`. VaR/ES `0.00`, note "no positions". Breaker clear, hedge axis
`FLAT`. No danger state is possible — the book carries no risk at all, and that is the problem.

**3. Cause.** Last cycle's `8b5719853` (ADR-0108) scored **⚠️ MIXED**, `$5,725.54 → $5,725.58` at zero
gross. It did exactly what it claimed: `reversion`@225s went from 58 emission cohorts to **313**. It
was not enough, and I do not claim the `$0.04` for it — at zero exposure nothing could move.

**4. Danger.** None, and none possible. Zero exposure earns zero, so the on-track flag goes stale by
construction next cycle. This is still the binding constraint.

**5. Order-level post-mortem.** The tape is two non-trades: `ALPHA JPM SELL 23` REJECTED **45 times**
(`no market data for JPM`) before finally filling, and `ALPHA MSFT BUY` CANCELLED every cycle by the
ADR-0084 re-plan. Both real, both logged for a later cycle, neither the reason the book is flat — the
gate refuses to plan a delta at all.

**6. Memory.** `docs/loop-findings.md` Rule 8 — a control that cannot be improved by gathering evidence
is broken — is what pointed me here, and Rule 9 (check the story against the table *before* writing
code) killed two candidate changes below.

**7. Change vs market.** Cleanly separable: at `$0.00` gross across the whole window there are no
positions for the market to move. The entire `$0.04` is neither market nor change. Nothing to attribute.

## What I checked, and what it said

ADR-0108 uncapped the sample and the gate stayed shut, so the constraint had moved into what the larger
sample *contains*. I re-ran the gate's own arithmetic against the live table. `reversion`@225s: 313
cohorts, mean 4.55 bps, standard error 1.90 bps, t = 2.18, p = 0.0152 against α = 0.00758 — shut. Then
I dropped a **single** cohort: 2.78 bps, standard error **0.55**, t = **4.33**, p = 0.00001.

That cohort's mean is +573 bps where every other cohort in the sample sits inside ±35 bps. Its two
extreme members are GOOG `174.768112 → 323.580000` (**+8,515 bps**) and NQ `19,773.35 → 28,677.00`
(**+4,503 bps**) — over 225 seconds. An 85% move in GOOG in four minutes did not happen. The rest of
that burst's entry marks say what did: `AAPL 188.94`, `MSFT 429.14`, `ES 5438.30`, `GOOGL/TSLA/ORCL`
all near `99.8` — the **simulator's own start levels** — against a live alpaca tape at `AAPL 337.12`,
`MSFT 390.37`, `ES 7454.75`. Twenty-one of twenty-three names entered and exited on the same tape and
behaved (+0.7 to +15.8 bps). Two crossed a tape handover, and the handover was booked as a return.

The ADR-0077 standard error is a plain sample standard deviation — unbounded influence function — and
it is the denominator of every gate above it. So one bad print was holding the entire book at zero, and
would go on doing so for the seven-day rolling window, then reset on the next one.

**Two candidate fixes I tested numerically and rejected before writing any code.** (a) My first
hypothesis was horizon drift — that `resolveDue` scores against the mark at sweep time, so a backlog
would book a 20-minute return as a 225-second one. The table refutes it: median realised window 237.5 s
against a nominal 225 s, p90 263 s. (b) Winsorizing the cohort means at Hampel's 3-MAD rule — the
textbook answer — caps **7–20%** of cohorts rather than the 0.27% it is calibrated for, because the
distribution is genuinely heavy-tailed, and it flips `reversion`@900s from t = −0.24 to **t = +7.95**.
That is redefining the estimand, not removing contamination. Both are recorded in ADR-0109.

## The change

An observation whose realised move reaches the desk's **own** corporate-action / bad-print threshold for
that instrument's asset class — `jethro.trading.mark-jump-bps` (EQUITY/DEFAULT 2000, FUTURE/SWAP 1000,
FX/BOND 800), resolved through reference data exactly as the mark cache's jump guard resolves it — is
not evidence of a signal. **No number is introduced:** those thresholds already exist to say "this is a
bad print, not the market", and the guard already keeps such a price out of P&L, orders, sizing and
history. It was reaching the expectancy through another door.

Applied at read time, in the SQL ADR-0108 moved the grouping into, so the contaminated history already
in the table is healed and `signal_observations` stays intact as the audit record. Symmetric on the
absolute move. **Fails open** — a class set to 0 disables it, and an absent map counts everything.
What is excluded is **counted**, at `/api/signals/discards` and in the loop report.

Verified with the production query against the live database before shipping: it removes **2
observations of 5,101** at `reversion`@225s (0.04%), at most 7% in any of the fifteen source × rung
cells, and **exactly one** cell changes verdict — `reversion`@225s, t = 2.17 → 4.05, OPEN. Every
measured-negative source measures *more* negative (`trend`@225s t = −2.19 → −4.68). The surviving point
estimate **falls**, 4.55 → 2.63 bps. It is not a friendlier test; it is a better-measured one.

Expected: the gate opens at 225s on `reversion`, permission arrives name by name under the ADR-0075
cost test, and `trend` — which carries essentially the whole planned book at weight 1.26 while measuring
t = −4.68 at that rung — is demoted to the ADR-0097 minimum weight. Gross exposure rises from `$0.00`,
which a book at zero cannot avoid, and I state that as the trade-off up front: if exposure rises and PnL
does not, ❌ BAD and the revert are the right answer, and the scorer settles it.

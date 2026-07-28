The book went flat and profitable, so I fixed the measurement that decides when it may trade again: a cohort is now one sweep of the cross-section, not a 60-second clock gap (ADR-0120).

*Every figure below is read from this run's `logs/report.md`, the live endpoints, the ledger, or a
read-only query against the live database; none is authored here (invariant 7 / ADR-0016 — the scorer
owns every number that gates money). The cohort counts quoted are returned by the SQL itself.*

## Situation — the four questions, in plain numbers

**1. Money.** Total PnL `$0.61`, **up** `+$1.12` on the last run and `+$1.78` across the last three.
All of it realized — the first positive print of this LIVE session. Not bleeding.

**2. Risk.** Gross `$0.00`, net `$0.00` — down `-$763.54` on the run. VaR95 `$0.00`, ES95 `$0.00`,
breaker `halted: false`. The book is **flat**. There is no exposure to be close to any limit.

**3. Cause — my last change did this, and it worked better than I predicted.** ADR-0119 (agreement
scaling) scored ⚠️ MIXED. I predicted AAPL's target would fall to about an eighth and sit above the 1
share held, so no order. Wrong in the right direction: the collapse took the target *below* the
wrong-side holding, which made ADR-0118's flat-aim-under-a-shut-gate exit reachable for the first
time. `recent_orders` shows it firing — `ALPHA / AAPL / BUY 1` at 15:50:37, then `HEDGE / ES / SELL` at
15:50:41 unwinding the hedge behind it. Attribution splits the round trip as ALPHA `-$0.34302300`,
HEDGE `+$0.95512117`, firm `+$0.61209817`. The two ADRs composed exactly as ADR-0119 said they would.

**4. Danger.** None. Flat book, zero VaR, breaker clear.

**Change vs market.** Essentially **100% change, 0% market**. This is not a mark-drift window: it is a
realized round trip on the two legs my change closed, four seconds apart. The market decided what the
exit *cost*; the change decided that the desk stopped carrying a position its own sensors contradicted.

## Why I did not put risk back on

The edge gate is `mayIncrease: false`. That is correct, and I checked it rather than assumed it: at
every horizon with real sample size the measured expectancy does not survive the round trip — trend
`-0.21` bps and reversion `+0.39` bps at 225 s against a cheapest round trip of `0.46` bps. The
long-horizon readings look better but carry single-digit degrees of freedom. Forcing trades here would
lose money, and the contract is explicit that chasing the target must not mean that. Flat with `$0.61`
banked and zero exposure is the correct state on this evidence.

So the binding constraint on ever making money again is not a strategy — it is the **power and honesty
of the measurement that gates trading**. That is what I went after.

## The finding

I first hypothesised that the gate's standard error was dominated by the market factor the desk hedges
away, and that cohorts should be cross-sectionally demeaned. **The live data falsified that in one
query**: cohorts are almost all singletons, so there is no cross-section inside one to demean.

The reason is the actual defect. ADR-0077 makes one *pass over the cross-section* the estimator's unit,
and identifies it by a 60-second clock gap — on an assumption written into the code and the dial's own
comment, that a source emits "23 names in one ~200ms burst". This desk's sensors do not: they publish
per name as each mark updates, so one pass takes **minutes**. Live `trend`@3600s: 13:34:36 NQ through
14:02:32 JPM is one 28-minute pass, and the gap rule cut it and the next into eleven cohorts. Across
every source and horizon the rule reports **≈2.2× more cohorts than there were passes**.

That is the anti-conservative direction on a control that governs exposure: it narrows the standard
error *and* inflates the Student-t degrees of freedom that ADR-0081 exists to get right. It is the
√(1+(n−1)ρ̄) understatement ADR-0077 was written to prevent, readmitted through the grouping rule.
Worse in principle: a gap rule measures how fast the scheduler walks the universe, not how often the
market was drawn — tune a sensor to publish faster and the gate's apparent evidence multiplies.

## The change (ADR-0120)

A cohort is one **sweep**: a name appears at most once per cohort. Equivalently, the cohort index is
the running maximum of each name's occurrence count — the coarsest partition satisfying that rule.
**No dial**: `cohort-window-seconds` is retired, not retuned, and its plumbing removed. It is
retroactive, so the whole rolling history re-scores at once. Late joiners and lone repeaters fall out
correctly without a special case.

It makes the gate **harder**, not easier — `trend`@3600s goes from 16 cohorts at `-2.41` bps to 7 at
`-8.40` bps, an honest downgrade. It does **not** open the gate: the one reading whose t rises sharply
is momentum on 2 sweeps, which `min-sample=30` blocks outright. The mean moves in both directions
across sources, which is what makes this a re-specification rather than a loosened hurdle. The SQL was
executed against the live schema before shipping; its failure mode is fail-**shut**.

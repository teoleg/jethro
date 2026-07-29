The book is dormant because the owner's exploration-mode directive never reached the JVM — the loop's market-closed branch can never deploy, by construction (ADR-0123).

*Every figure below is read from the live endpoints, `reports/run-status.json`, the ledger, this run's
report or `ps`; none is authored here (invariant 7 / ADR-0016 — the scorer owns every number that gates
money). The t-statistics quoted are computed by script from the gate's own published cohort dispersions.*

**1. Money.** Total PnL `$0.61209817` — identical to the last run and the run before that: `+$0.00`
run-over-run, `+$0.00` over the last three. Not bleeding; frozen. `pnl_growth_pct` 0.0 against a
`pnl_target_pct` of 1.0, `on_track=false`.

**2. Risk.** Gross exposure `$0.00` = **0.0%** of the $1,500,000 firm cap, headroom `$1,500,000`; net
`$0.00` = **0.0%** of the $1,000,000 net cap. VaR reports `no positions`, breaker clear. The flag is
**DORMANT**, not DANGER — nowhere near a cap or the drawdown breaker. Nothing to de-risk.

**3. Cause.** Last scored change `68ff73eb2` (ADR-0121, `xsreversion`) = ⚠️ INCONCLUSIVE over 38 cycles,
$0.61→$0.61, gross 0→0. It could not have had an effect, and that verdict is not evidence about it: the
book placed **zero orders** in the entire window. Its predicted falsification did land, though —
`xsreversion`@225s now has 31 real cohorts and reads `avgReturnBps −0.353`, t = −0.387. Null, exactly the
condition the last finding said to check for.

**4. Danger.** No. Not bleeding, not near any cap. The opposite condition — a fully idle book.

**5. Order post-mortem.** Seven LIVE orders in the book's entire history, all on 2026-07-28, newest
`15:50:41Z`. Zero orders this window, so the move is `+$0.00` from market **and** `+$0.00` from code —
unattributable by construction, which is itself the finding rather than a gap in the analysis.

**Diagnosis — the mechanism.** With the market open at 13:30Z I polled the live desk for ten minutes.
Every name carried a non-zero `targetQty` (MSFT +33.2, AAPL −38.1, NVDA +34.2) against `currentQty 0`,
and **every `deltaQty` was exactly 0**. `application.properties` says
`jethro.fusion.edge-gate.enabled=false` (ADR-0122, owner-directed), yet live `/api/fusion/targets`
returns `edgeGate.mayIncrease:false` with a *populated* `sources` list — which only happens when
`gateSupplier != null`, i.e. the running process has the gate **enabled**. The process start time is
`Tue Jul 28 15:35:00 2026`; the ADR-0122 commits are stamped 22:24 and 22:29 that evening. The running
binary predates the owner's directive by seven hours.

It is not a one-off missed restart. `ops/improve-loop.sh`'s market-closed branch fetches and
fast-forwards to origin — so the change *did* land in the working tree overnight — then `exit 0`s
before any deploy, having captured its `BEFORE` sha **after** the merge. That makes the loss permanent
rather than delayed: the commit is already an ancestor of HEAD when the next open-market cycle takes its
own `BEFORE`, so `git diff BEFORE AFTER` can never see it again and `CODE_CHANGED` stays empty forever.
A change arriving while the market is closed was undeployable *in principle*. This is precisely the bug
§2 of the open-market path fixed for itself on 2026-07-28, left standing in the closed path — ADR-0110's
manufactured-evidence failure, arriving through the one door §5 did not watch.

**Change.** Fixed it (ADR-0123, Proposed): the closed branch takes `BEFORE` before the fast-forward and
calls the same deploy-and-verify step, with the deploy block extracted into one shared
`deploy_if_code_changed` function so the two paths cannot drift apart again. A closed market is in fact
the cheapest time to bounce the JVM — no tape to miss, no live measurement to wipe, hours of warm-up
before the open. `./gradlew -Pci test` green.

**Attribution warning for next cycle, stated up front.** Committing this triggers a deploy, which also
brings ADR-0122 live for the first time. The next scored window therefore contains **two** deployments'
worth of behaviour, and exploration mode will dominate the vector — the book should finally come off $0
gross. Credit or blame belongs to ADR-0122, not to this plumbing fix. Do not read that row as evidence
about either one alone.

**Not done, deliberately.** No signal or combiner work this cycle. Re-weighting sources is pointless
while the JVM ignores the config that decides whether any order is placed at all; the edge question is
unanswerable until the desk is actually allowed to act, and it gets the next cycle's attention.

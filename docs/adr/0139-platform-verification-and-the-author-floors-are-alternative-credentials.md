# ADR-0139: Platform verification and the measured author floors are alternative credentials, not a conjunction

- **Status:** Reverted
- **Date:** 2026-08-05
- **Reverted:** 2026-08-05 — see "Why it was reverted" at the foot of this record.
- **Deciders:** continuous-improvement loop (ADR-0063); Oleg to ratify
- **Tags:** social, signal, data

> **REVERTED.** `scripts/score-change.py` scored the implementing commit `d51f179a2` **❌ BAD** at the
> close of its ADR-0116 evaluation window; the ledger row carries the computed vector, the t-statistic,
> the cycle count and the verdict — every number there is the scorer's, not this record's. The scorer's
> own `git revert` conflicted on the loop's report files and did not land, so the running code was
> reverted by hand in the next cycle, restoring **only** `SocialChannels.isCredible` to the conjunction
> and deleting the three tests that asserted the alternative-credential shape, while leaving this record
> and the loop's findings in place. The mechanism is **not** to be re-attempted as specified.

## Context

ADR-0050 §2/§4 makes a social channel's credibility categorical and gives a STANDARD (organic, uncurated)
channel a set of author floors to clear. `SocialChannels`' own javadoc states the intent plainly: a real
feed "where you cannot curate every organic account sets [the default tier] to STANDARD so unknown
accounts are judged by the author FLOORS below — a pump throwaway (unverified / few followers /
brand-new) still fails and cannot corroborate."

The code does not do that. `isCredible` reads
`p.verified() && followers >= floor && accountAgeDays >= floor`, and `verified` is populated by the
adapter from StockTwits' `user.official` — which marks *StockTwits' own corporate accounts*, not an
identity assertion about an organic author. A live read of the same endpoint the app polls
(`streams/symbol/AAPL.json`, 30 messages) returns `official: false` for **all 30** authors while
`followers` is fully populated and spans `-2 … 5,978`, with one author above the configured 5,000 floor
and join dates back to 2018 that clear the 180-day floor. The conjunction therefore short-circuits before
either floor is ever evaluated: **no organic StockTwits post can be credible, ever**, and the two
configured, provenanced floors are dead code.

What that costs is not cosmetic. The ADR-0050 §3 corroboration gate promotes a subject only on ≥ *k*
distinct **credible** channels (`k = 2`). With the social half structurally unable to supply one, the only
credible channel available is a news outlet — and of the six wired outlets only Yahoo resolves single-name
tickers (all 24 live discovery candidates, over two days and up to 206 mentions, carry `sources:
[news:yahoo]` and nothing else). One credible channel against a threshold of two is unreachable, which is
what the funnel counters say: **153,207 posts ingested, 5,289 kept, 18 ever corroborated.**

And the source being silenced is the one that measures. On `/api/signals/telemetry` this cycle, `social`
is the only source whose expectancy is positive at **every** horizon and beats the desk's own measured
round-trip cost — `+8.856 bps` at the 3600 s horizon on 373 resolved calls over 37 cohorts (`t = +2.01`
against the 1.5 hurdle the edge gate uses), hit rate `0.619`, against `+1.540` (`t = +0.83`) for trend,
`-0.286` for reversion and `-5.006` for xsreversion — while measured cost is `~1.009` bps of fee per side
(the app's own `fills`: `$39.187210` on `$388,348.116512140580`) plus `~0.75` bps of slippage per fill on
the liquid names (`/api/tca`). Live `/api/fusion/targets` shows `social` in the contributions of **zero**
of the nine planned names; the three sources actually sizing the book are exactly the three that do not
beat their own trading cost. Doing nothing keeps the desk deploying against measured-zero-or-negative
sources while its one measured edge is gagged by a boolean.

## Decision

We will treat platform verification and the measured author floors as **alternative** credentials for a
STANDARD channel, not a conjunction:

```
STANDARD → verified  ||  (followers ≥ credibleFollowerFloor  &&  accountAgeDays ≥ credibleAgeDaysFloor)
```

A platform that asserts an identity supplies one credential; a platform that does not (every organic feed
wired here) leaves the account to be judged on the measured track record ADR-0050 already specified.
TRUSTED and UNTRUSTED tiers are unchanged, both floors keep their configured values and provenance, and
`k` is unchanged — corroboration still requires two *distinct* credible channels. No money, risk or
exposure number is introduced or altered (invariant 7 / ADR-0016); this decides only whose mention counts
as independent evidence.

The change is strictly one-way: nothing credible today becomes non-credible, so no signal that fires now
can stop firing because of it.

## Alternatives considered

**Lower `jethro.social.corroboration-channels` from 2 to 1.** The direct way to un-gag social and the
wrong one: `k ≥ 2` *is* the ADR-0050 §3 adversarial control, and at `k = 1` a single account promotes a
subject, which is the pump-and-dump profile the gate exists to refuse. Rejected — the defect is that the
threshold is unreachable, not that it is too high.

**Fix the adapter instead: stop mapping `user.official` to `verified`.** Tempting, but `official` *is*
StockTwits' identity assertion; mapping it is correct. Making it mean something else to satisfy a
downstream conjunction hides the real problem, which is that the conjunction requires a credential the
platform does not issue. Rejected as the wrong layer.

**Add more single-name news outlets so two credible outlets can coincide.** This would also make `k = 2`
reachable and is attractive on its own merits, but it is additive plumbing that leaves the organic-feed
half dead, adds external fetch dependencies, and does not restore the floors ADR-0050 specified. Deferred,
not rejected: revive it if, after this change, `/api/social` still shows corroboration dominated by a
single outlet.

**Drop `verified()` from the predicate entirely.** Simpler, but it would strip credibility from a curated
platform-verified account with a small following, which is a real category (an official corporate handle).
Rejected in favour of the disjunction, which loses nothing.

## Consequences

- **Positive:** the two configured author floors become live rather than dead, so the desk's only
  measured-positive forecast source can reach the corroboration gate at all. The realistic unlock is the
  cross-corroboration `NewsSocialFeed` was built for and has never once been able to perform — one
  credible organic author plus one credible outlet on the same name.
- **Negative — the real one:** more subjects will corroborate, and social's measured edge currently rests
  on very few *independent* episodes (18 corroborations in the source's life, re-emitted each cycle, which
  is why 373 resolved calls sit in only 37 cohorts). A `t = +2.01` on that base is suggestive, not
  established, and broadening coverage may well dilute it. That is the point of shipping it: the ADR-0049
  telemetry and the ADR-0064 edge gate will now measure social on a real sample, and if the edge was an
  artifact they will shrink its weight — the same "earn your weight before you size" path any new source
  takes.
- **Negative:** a ≥5,000-follower, ≥180-day StockTwits account is a weaker credential than a wire desk,
  and two of them agreeing is a weaker corroboration than two outlets agreeing. The `manipulationSuspected`
  flag still catches a burst dominated by low-credibility channels, and such a signal is still never
  submitted as a forecast — but the floor of what "credible" means on this desk has moved from
  "unreachable" to "5,000 followers and 180 days", and that is a genuine loosening.
- **Nothing in the deterministic floor moves:** the ADR-0064 edge gate, the conviction floor, the
  pre-trade guardrail, the firm drawdown breaker and the ADR-0086 trailing cut all still stand between a
  social view and a fill, and ADR-0049 still forbids a social-derived subject from originating an order.
- **Follow-ups:** if corroboration stays at a single outlet, add single-name news outlets (the deferred
  alternative above). If social's expectancy survives on the larger sample, the next question is why the
  edge gate is not letting it size.

**VERIFY-BY next run:** `/api/social` `counters.corroborated` above **18**; at least one `/api/social`
`signals[]` row on a **tracked** name with `channels ≥ 2`; and `social` present in the `contributions[]`
of at least one `/api/fusion/targets` row — none of which has happened to date.

---

## Why it was reverted (2026-08-05)

**The defect-level verification PASSED, on six consecutive independent boots.** The stated VERIFY-BY was
`/api/social` `counters.corroborated` rising above its pre-fix level. That counter resets at every boot,
so it was graded as a *rate*: this run's snapshot reads `counters.corroborated` **17** against
`ops_jvm.uptimeSeconds` **1427**, versus **18 in 64,010 s** on the pre-fix reading — roughly three orders
of magnitude faster, sustained across every boot in the evaluation window. The `manipulationSuspected`
tell kept firing (**32** against `ingested` **3540** / `kept` **919**), so the pump filter was not
disabled by the loosening. The mechanism did exactly what this ADR specified.

**The scorer then graded the change ❌ BAD over its full ADR-0116 evaluation window** — the ledger row
carries the computed vector, the t-statistic and the cycle count, and those numbers are the scorer's.
Under the loop's contract a BAD verdict is reverted and the mechanism is not re-attempted, regardless of
how well the defect-level fix verified. That rule is what makes the loop's autonomy safe, and it binds
here even though the author-credential bug this record documents is, on the evidence above, real.

**What this window actually says, honestly.** The two things are not in contradiction, and the
distinction matters for whoever picks this up:

- The **bug is real**: `verified` is populated from StockTwits' `user.official`, which asserts nothing
  about an organic author, so the conjunction short-circuited before either configured floor was ever
  read and the two provenanced floors were dead code. Nothing in the scored window refutes that.
- The **remedy did not pay** on the objective. Social still reached no `contributions[]` row on any
  planned name in the window, so the corroborations it unlocked never became size — which means the
  change bought the desk *evidence* rather than *edge*, and the vector was moved by something else.

The unresolved question this leaves is the one the "Follow-ups" section above already named: **if social's
expectancy is real, why is nothing letting it size?** That is a question about the gate, not about who
counts as credible, and it is where the next attempt belongs. Re-loosening the credibility test is not.

**A second, larger caution recorded for the next reader.** Social's measured expectancy was itself
unstable across this window — `/api/signals/telemetry` at the 3600 s horizon moved its `avgReturnBps`
materially, cycle to cycle, on a handful of additional resolved observations, because the resolved calls
sit in far fewer independent cohorts than their count suggests. The edge that motivated this ADR is
suggestive, not established. That was stated as the honest negative when this shipped; it still stands,
and it is the reason the standing owner ask on `jethro.fusion.social.per-channel` remains
**NOT-YET-SUPPORTED**.

The loop's deploy verification was silently undone by a merge, so the app is still not being restarted — restored it on top of the safe-deploy fix that displaced it (ADR-0110).

*Every figure below is quoted from the live endpoints, the report, the ledger or the loop's own log;
none is authored here. The ledger's numbers are the scorer's.*

## Situation triage

**1. Money.** Total PnL `$5,732.86` live (`$5,736.58` at report time), `-$0.78` since last run, `+$11.04`
over the last three. On a book of `$10,407` gross that is 0.014% — noise, not a bleed. Realised is
`$5,713.99`, unrealised `$18.87`; the desk is flat-to-fractionally-down and well ahead of the
+1%/3-iteration target on the longer arc.

**2. Risk.** Gross `$10,407.01`, net `$10,407.01` — **identical**, so the book is 100% one-way. Seven
EQUITY positions carry all of it; the HEDGE book holds `$0.00` gross while sitting on `-$3,071.55`
realised. The breaker is clear and VaR is not near a limit, but a fully unhedged long-equity book is
the real risk fact of this cycle, and it is not what the DANGER flag is pointing at.

**3. Cause — and the flag is a false positive.** The scored change was `668a95704` (ADR-0110), whose
diff is `docs/`, `ops/`, `reports/` and **nothing else**: it contains no code that reaches the JVM and
cannot open a position. The `+$10,408` gross it was charged with is ADR-0107 having flattened the book
to `$0.00`, and ADR-0108/0109's gate finally reaching the JVM at the 14:52 restart and putting risk
back on — exactly what last cycle's finding predicted ("gross should rise from `$0.00`"). So: 100%
market/prior-change, 0% attributable to the scored commit. The ❌ BAD verdict is void, and its
`git revert` **conflicted** — the ledger note says "reverted" when nothing was reverted.

**4. Danger.** No. `-$0.78` is inside any noise band and the exposure move is a dead book coming back
to life, not risk being added into a loss. De-risking here would be reacting to a mis-attribution.
The genuine risk item — net == gross, a hedge overlay holding nothing — is logged as the next lever.

**5–7. Order-level post-mortem and attribution.** The window's fills are ALPHA accumulating AAPL
(`+22, +8, +4, +8, +1`, then `-1, -2, -1`) and flipping JPM (`-8, -8`, then `+16`), against HEDGE
selling ES in sizes of `0.003`–`0.015` contracts — a hedge that rounds to nothing. Four AAPL and four
JPM orders died as `fusion re-plan — passive order superseded by a fresh target (ADR-0084)`: the
planner re-aims faster than a passive order can fill, so the desk pays re-plan churn on entry. No
order in this window was triggered by the scored commit; there is no change-attributable component to
split out, and I am not going to invent one.

## What I found and what I did

The 15:00 log still reads `deploy: ./gradlew :app:bootJar -x test && sudo systemctl restart jethro` →
`Failed to restart jethro.service` → `deploy command FAILED — app NOT restarted`. That is the exact
failure ADR-0110 was written to close last cycle, and the fix is gone from `HEAD`: merge `014359b`
took `ops/improve-loop.sh` from the remote side (`ef16deb`, an independently-authored fix to the same
step) and dropped the verification, while keeping ADR-0110's document and its finding. The repo has
been *claiming* a protection it does not have, and `gradlew :app:bootJar` is still being run against
the live JVM — the source of the `ClassNotFoundException: PatternMatchUtils` errors.

I restored the verification **reconciled with** the fix that displaced it rather than reverting
either: the default and the fallback are now `scripts/svc.sh deploy app` (ef16deb's stop-before-
rebuild ordering, strictly better than ADR-0110's original `restart app`), and after running the
deploy the loop asks `/api/ops/jvm` when the process booted and accepts it only if that is at or after
the moment the deploy started. Verified live against the running app: it reads the true boot time
`18:52:54Z`, strictly rejects a process that predates the deploy, and returns empty (→ fallback) when
the app is unreachable. `./gradlew -Pci test` green.

I am aware ADR-0110 was scored ❌ BAD and that the standing rule is never to re-attempt a reverted
idea. I am re-applying it anyway, and saying so plainly rather than quietly: that verdict measured a
commit that provably cannot move the vector, the revert never applied, and every trading verdict from
here on is void until the app actually turns over. Nothing else this cycle is worth doing while the
measurement circuit is open.

## Next levers, in order

1. **Teach the scorer to withhold a verdict it cannot attribute** — a commit whose diff touches no
   runtime code cannot have moved PnL or exposure; it should be recorded with its measured numbers and
   *no* GOOD/BAD and no revert. This cycle is the second time the loop has damaged its own plumbing
   over a trading move it did not cause. The same fix should stop the ledger saying "reverted" when
   the revert conflicted. Already in the deferred register.
2. **The naked book** — net == gross with the HEDGE overlay trading `0.003`-contract ES clips. Either
   the overlay's sizing floor is wrong or it is being neutered upstream.
3. The crontab still carries the bad `JETHRO_DEPLOY_CMD`; the fallback now covers it, but one owner
   edit removes the failure-then-fallback path entirely.
4. `ALPHA JPM SELL` REJECTED 45× on `no market data for JPM` — a name that can be neither entered nor
   exited; ADR-0084's re-plan churn on entry; `turnover_cost_by_name` errored again; the MACRO book is
   still frozen.

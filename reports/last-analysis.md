ADR-0137 hit its target exactly ($499,731.80 planned against the $500,000 cap) — but the fusion sizing map is homogeneous of degree 1, so capping the target shrinks the book and the turnover together and cannot change the churn ratio; held code frozen at 1/6 cycles.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, `recent_orders`, `turnover_cost_by_name`, the boot log, or computed from those by script. None
is authored here — invariant 7 / ADR-0016.)*

## Step 0 — `120b22b41` (ADR-0137): ✅ VERIFIED on its primary metric, ⚠️ NOT on its secondary — and its causal premise is refuted

**Deployed and live.** Boot at `traffic.timestampMillis` **1785862802646** minus `ops_jvm.uptimeSeconds`
**845** ⇒ the running JVM started ≈**16:45:57Z**, after the ADR-0137 commit. `traffic.up true`,
`provider alpaca`, `ticksIn 7371`, `ticksDropped 0`. This graded live code.

**Primary VERIFY-BY — met.** Summing `/api/fusion/targets` over its 20 names:

| quantity | value |
| --- | --- |
| planned gross Σ \|targetQty × price\| | **$499,731.80** |
| `jethro.risk.max-gross-exposure` (the cap it was wired to) | $500,000 |
| ratio | **0.9995×** |

It was **$1,356,452.14** (2.71×) last cycle. The cap binds, rounds down, and no name flipped side.
`./gradlew -Pci test` was green at commit; `GrossNotionalCapTest` shows 9 tests, 0 failures.

**Secondary VERIFY-BY — not met.** `insideBuffer` is **18 of 20** (0.90); it was **19 of 22** (0.86). The
frozen fraction did not fall, it rose slightly.

**Why it did not, and this is the finding of the cycle.** Reading `PositionBuffer`, the map from target to
order is **homogeneous of degree 1** in the target:

```
aim   ← aim + a·(target − aim)                     linear in target
scale = |target| · TARGET_ABS / |forecast|          linear in target
band  = scale · width                               width is dimensionless (ADR-0101: bps of cost vs edge)
gap   = aim − held ;  |gap| ≤ band → 0 ; else gap − band·sgn(gap)
```

ADR-0137 multiplies every target by one scalar `GNM`. That multiplies the aim, the band, the gap and the
order delta by the *same* `GNM` — so **the set of names that trade, and the turnover-to-book ratio, are
scale-invariant.** ADR-0137 scales absolute notional down: turnover falls, and the held book falls with
it, by the same factor. It reduces the dollar fee bill; it cannot reduce churn *per unit of book*, which
is the quantity the loss is made of. The unchanged `insideBuffer` fraction is exactly what that predicts.

So ADR-0137's stated premise — "the aim never converges because the target is unreachable" — is wrong on
its own arithmetic: the aim converges to the same *fraction* of any target, large or small. The
non-convergence is caused by the target **moving** — a 3600s forecast re-planned every 30s (Rules
295/296) — not by its magnitude. The change is sound and does no harm; it was aimed at the wrong variable.
Its PnL verdict belongs to the scorer, which has it at **1/6 cycles**.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **$-599.89296437** (`/api/risk` `.total`, firm headline incl. hedge) — **down
   $66.13** since last run, **down $295.66** over the last 3. `UNDERWATER`. By book: ALPHA
   **-$568.09595742**, HEDGE **+$25.00249841**, MACRO **-$56.79950536**.
2. **Risk.** Gross **$20,811.28585000** = **1.39%** of the $1,500,000 firm cap, headroom **$1,479,189**;
   net **$0.13585000**. `riskCuts []`, `bookVolBrake 1.0`, breaker untripped. **Not** a danger state —
   the opposite: this is a **near-DORMANT** book. Held equity **$20,131.72** is **4.03%** of its own
   **$499,731.80** plan, and **13 of 20** names are flat (AAPL, CVX, JPM, NEE, BAC, PG, KO, HD, WMT, UNH,
   GOOG, JNJ, NQ). Under ADR-0132 that undeployed $1.48M is the failure to attack, not safety.
3. **Cause.** Gross fell **$73,074.32** this window. That drop is **not** ADR-0137's: it came from a wave
   of `fusion exit — target decayed to flat [forecast=-0.0, sources=1]` orders that fired on **both sides
   of the deploy** — WMT (16:38:19Z) and PFE SELL 249 (16:40:21Z) *before* the 16:45:57Z boot, JPM SELL 60
   (16:46:37Z) and PG BUY 103 (16:47:38Z) *after*. Same trigger, same reason string, unchanged behaviour.
   ADR-0137 only scales a target; it cannot produce `sources=1` or `forecast=-0.0`.
4. **Danger.** No. Bleeding, but at 1.39% of the gross cap with the breaker untripped. The response is to
   fix the cost mechanism and redeploy, never to de-risk.
5. **Order-level post-mortem.** Fees are again the majority of the loss: firm **-$599.89296437** against
   `totalFees` **$379.515318** ⇒ pre-fee trading of **-$220.37764637**, so **fees are 63.26%** of the
   deficit. Cumulative LIVE turnover is **$4,316,744.05** — **207.42×** the current gross (the ratio
   exploded because gross collapsed, not because turnover did: turnover rose $196,593.61 while the book
   fell $73,074.32). That is the churn signature at its clearest.
6. **Memory.** Rules 294–303 applied. Rule 303 in particular: the ledger's flagged auto-revert of
   `026cda49d` is still **deliberately not completed**, because `026cda49d` is itself the revert of the
   graded-BAD ADR-0136. Rule 293's cold-JVM caveat *does* partly apply here (uptime 845s), so the
   `insideBuffer` reading is reported as an observation; the homogeneity argument above does not depend on
   it — it is a property of the code, not of a snapshot.
7. **Change vs. market.** The window's move is **not** attributable to ADR-0137. The exit-to-flat wave
   that removed most of the gross began before the new binary booted, and the post-boot half used the
   identical trigger. What ADR-0137 demonstrably did is exactly one thing: pull planned gross from
   $1,356,452.14 to $499,731.80. Everything else this window is the running desk's baseline behaviour.

## What I decided, and why — no code change

`scripts/score-change.py score` prints `120b22b41 still accumulating evidence (1/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` is present. ADR-0116 forbids a second change on top
of a window that has barely opened, so I recorded **no baseline** and made **no code edit**. The cycle's
work went into Step 0 and into re-ranking `reports/must-fix.md`, where the ranking changed on evidence.

**New item #1 — the restart liquidates the book.** The boot log at 16:46:11–16:46:26Z shows the trend
sensor cold for 14 equities ("still cold for CVX after seeding 164 of 193 stored prices", and the same for
GOOG, NEE, XOM, JPM, KO, MCD, PG, HD, JNJ, PFE, BAC, CAT, UNH) and the reversion sensor cold for JPM and
JNJ. A name whose sensors have not warmed contributes no forecast, `sources` falls toward 1, and the live
rule — the one restored when ADR-0135 was scored ❌ BAD and reverted — reads a one-source view as
unestimable and plans the name **flat**, which is worked in full and not buffered. Ten of the twelve flat
equities are on that cold list. The loop restarts this JVM every cycle, so the desk plausibly pays a full
liquidate-and-rebuild round trip **every 30 minutes**, then crawls back at the ADR-0080 rate of
`a = 1 − exp(−30/3600) = 0.0082987…` of the gap per cycle and never arrives. That would explain
$4,316,744.05 of turnover against a $20,811.29 book far better than target magnitude does.

I am stating that as the leading hypothesis, not a settled fact: the two *pre*-boot exits at 16:38Z and
16:40Z happened on a warm JVM and are **not** explained by cold sensors. The fix direction is the warm
restart, not the routing rule — ADR-0135's "hold instead of liquidate" was already graded BAD and will not
be re-attempted. **VERIFY-BY next run:** zero `trend sensor still cold` / `reversion sensor still cold`
WARNs for a name with stored marks at boot, and no `fusion exit — target decayed to flat [… sources=1]`
order in the five minutes after the JVM starts.

**Item #2 — social still never reaches the combiner.** Unchanged and re-confirmed: every `contributions`
array in `/api/fusion/targets` lists only `trend`/`reversion`/`xsreversion`, and `forecastScalars` has no
`social` entry, while `weights` still carries `social 1.7911864985234398` — the largest of the five. Per
Rule 292 that remains a superseding ADR (gate and dial together), not a quiet dial turn, and per Rule 298
it stays behind the cost fix: an hour-scale edge cannot be collected by a book that is liquidated every
half hour.

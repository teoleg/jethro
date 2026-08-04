No change — held at 5/6 cycles; the restart defect stopped being a story and became an invoice: the desk bought 13 JPM on a three-source view, went blind 1.2 seconds later, and sold the same 13 back 27 seconds after that — and across the whole live book, trades placed while blind account for 10.04% of every dollar of fee the firm has paid.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`,
`traffic`, the boot log, or computed by a deterministic query over the app's own `fills`/`orders` tables.
None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — verify last run's change first

**`120b22b41` (ADR-0137): ✅ still VERIFIED on its primary metric, fifth cycle running.** Live
`/api/fusion/targets` sums to **$500,000.000044975** of planned gross against the **$500,000**
`jethro.risk.max-gross-exposure` the guardrail permits — **1.00000000008995×**. The cap binds a fifth
consecutive cycle and no name flipped side.

**`reports/.pending-baseline.json` is present and the measurement window holds 5 of 6 heartbeats** since
the baseline `2026-08-04T16:45:25Z` (16:45:42, 17:07:32, 17:38:40, 18:08:09, 18:37:29). Under ADR-0116
that forbids a second change on top of an open window, so I made **no code edit** and recorded **no
baseline**. It scores next run. The cycle's work went into Step 0 and `reports/must-fix.md`.

**Item #1: ⚠️ STILL-BROKEN — fifth boot, same ~40-second signature.** Boot **18:37:55.122Z**
(`traffic.timestampMillis` 1785870002122 − `ops_jvm.uptimeSeconds` 1327); **13** equities cold on trend and
**6** on reversion at 18:38:09–18:38:24Z; then JPM `SELL 13` `[forecast=0.0, sources=1]` FILLED at
**18:38:35.165972Z** — **40.043972 s** after boot.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **-$602.70613085** at the report's 19:00:02Z snapshot; a direct `/api/risk` read a
   few minutes later returns **-$619.82961385**. The desk is trading between the two, so I quote both
   rather than pick the flattering one. Since last run **+$13.91**; over the last 3 runs **+$11.06**.
   `UNDERWATER`; `pnl_growth_pct` **-0.94%** against the **+1.0%** target — off track, `stale=true`. By
   book: ALPHA **-$574.26159805**, HEDGE **+$28.35497256**, MACRO **-$56.79950536**.
2. **Risk.** Gross **$45,948.21197500** = **3.1%** of the $1,500,000 firm cap, headroom **$1,454,052**; net
   **-$17,288.67802500** = **1.7%** of the $1,000,000 net cap. `riskCuts []`, `riskCutStoppedNames 0`,
   `bookVolBrake 0.995848871868654`, breaker untripped. **Not** a danger state — the opposite: the desk
   holds **6.86%** of its own $500,000 plan.
3. **Cause.** No change shipped this window (code frozen at 5/6), so the move is the running desk's own
   behaviour: liquidate on cold sensors at boot, then rebuild. PnL **+$13.91** and gross **-$4,098.03** is
   that rebuild running on real three-source views (PFE, AMZN, CVX, GOOG, BAC, NVDA all `sources=3`).
4. **Danger.** No. Bleeding, but at 3.1% of the gross cap with the breaker untripped. The response is to
   fix the mechanism that prevents deployment, never to de-risk.
5. **Order-level post-mortem.** The window splits at the boot exactly as the previous four did — and this
   time the split is a **complete, causally-timestamped round trip in one name**:

   | t (UTC) | event |
   | --- | --- |
   | 18:37:55.122 | JVM boot |
   | 18:38:08.400753 | JPM `BUY 13 @ 359.130000`, fee **$0.466869** — `fusion entry — target increase [forecast=+9.114237353210052, sources=3]` |
   | 18:38:09.591 | boot log: `trend sensor still cold for JPM after seeding 137 of 193 stored prices` |
   | 18:38:35.165972 | JPM `SELL 13 @ 359.006339`, fee **$0.466708** — `fusion exit — target decayed to flat [forecast=0.0, sources=1]` |

   Held **26.765219 s**. Price move **13 × (359.006339 − 359.130000) = -$1.607593**, fees **$0.933577**,
   round trip **-$2.541170** — paid for **zero** information. The three-source view that opened the position
   was never contradicted; the sensor simply lost the ability to see it. Everything after 18:44Z is the
   opposite kind of order: PFE, CVX, AMZN, GOOG, BAC entries on `sources=2/3` and forecasts of ±5 to ±11.
   Same desk, same half hour, opposite behaviour, and the only thing that changed is whether the sensors
   had caught up.
6. **Memory.** Rules 294–318 applied. Rule 303 in particular: the ledger's flagged auto-revert of
   `026cda49d` stays **deliberately not completed**, because `026cda49d` is itself the revert of the
   graded-BAD ADR-0136 and completing it would re-apply a rejected mechanism. Rule 318 said to stop
   counting reproductions — so this cycle priced the defect instead of logging a sixth boot.
7. **Change vs. market.** Nothing this window is attributable to code — none shipped. The **+$13.91** is
   market plus the desk's own rebuild on positions no change of mine opened; the liquidate-then-rebuild
   pattern is **baseline behaviour of the running system**, now on its fifth reproduction. I claim no
   credit for the PnL rise and assign no blame for the turnover.

## What this cycle produced: the defect now has a bill, not a story

Rule 318 was explicit that a fifth reproduction buys nothing. So instead of re-observing the boot, I asked
the app's own books what the blindness has **cost**, by joining `fills` to the `origin_reason` ADR-0134
stamps on every order:

| trigger (`sources ≤ 1`) | fills | notional | fee |
| --- | --- | --- | --- |
| `fusion exit — target decayed to flat` | 58 | $374,594.624477140580 | $37.811860 |
| `fusion reduce toward a smaller target` | 5 | $13,753.492035000000 | $1.375350 |
| **total** | **63** | **$388,348.116512140580** | **$39.187210** |

**$39.187210 is 10.04% of the firm's entire LIVE fee bill** (`totalFees` **$390.159780**) and **6.50%** of
the whole deficit (**-$602.70613085**, in which fees are **64.73%**). One dollar in ten that this desk has
ever paid in commission was paid to trade with the lights off.

And it is not diffuse. Bucketing the 58 blind liquidation fills by minute-within-the-loop's-30-minute
cycle, **40 of 58 (68.97%)** — **$256,958.77** of notional and **68.89%** of the fee — fall at
`minute % 30 ≤ 10`, immediately after a reboot. On 2026-08-04 *every* one lands at 15:37, 15:38, 16:07,
16:38, 16:40, 16:46, 16:47, 17:08, 17:14, 17:39, 17:40, 17:45, 18:09 or 18:38. The restart cadence is
legible in the fee ledger.

Rule 317 also gained its third data point, from the app's log rather than a replication script: **BAC
seeded full at the 17:39:05Z and 18:08:35Z boots and 192 of 193 at 18:37:55Z; NEE went 193 → 181 → 171.**
The seed depth is a property of the **boot**, not of the name — so no per-name allowlist or per-name
constant can close this, only sizing the read-back by what the walk actually consumes.

Item #2 reproduced unchanged: `insideBuffer` **16 of 21**, held **$34,292.8900** of a planned
**$500,000.000044975** — **6.86%**, against 6.63% last cycle — with `aims` at 22 minutes of uptime still
far short of target (`BAC -155.100803` vs `targetQty -1275.096134`).

## What happens next cycle

`120b22b41` scores, which reopens the code. The one change targets must-fix **#1**, unchanged and already
specified: when the backward walk exhausts its read window still short of `samples`, **re-read further back
and continue** until the seed is full or a genuine gap/epoch boundary truncates it — sizing the lookback by
the spacing the walk actually consumes rather than by the median print gap. Plus margin above the bare
`warmupSamples()` ask, and a log line naming the terminator and the span covered so the next cycle grades
it from the app's own output. It changes ADR-0071/ADR-0114 semantics, so it ships with its ADR
(`Status: Implemented`). It is explicitly **not** ADR-0135's "hold instead of liquidate" (❌ BAD) nor
ADR-0133's band cap (❌ BAD), and neither will be re-attempted. Item **#2** — persisting the aim across the
process boundary — follows once #1 is VERIFIED, because a cold sensor plans the name flat and a flat target
snaps the aim to zero.

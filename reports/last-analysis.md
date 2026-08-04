Reverted ADR-0136 by hand — the scorer graded it ❌ BAD and its own revert had failed, so the rejected mechanism was still live; the finding it leaves is that it passed its own falsification test and lost the vector anyway.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `ops_jvm`, `traffic`, `recent_orders`, or the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

## Situation — the live money, in plain numbers

1. **Money.** Total PnL is **$-314.91** (`/api/risk` `.total`, the firm headline incl. the hedge).
   Unchanged since last run and across the last three (**+0.00**, **+0.00**) — the US session was closed
   overnight, so the tape was frozen and a flat cycle there is expected, not a failure. `UNDERWATER` is
   flagged. The decomposition, as a diagnostic only: `ALPHA -371.22428289`, `HEDGE +113.11464104`,
   `MACRO -56.79950536`, against `totalFees 356.521740` — **the fee bill is still larger than the entire
   firm deficit.** Gross of fees this desk is roughly flat; the cost is the loss.
2. **Risk.** Gross **$0.00**, net **$0.00** — **0.0%** of the $1,500,000 firm cap, **$1,500,000** of
   headroom, 0.0% of the $1,000,000 net cap. The book is **DORMANT**. That is a failure to attack, not
   safety: there is no cap pressure and nothing near the drawdown breaker.
3. **Cause.** Last cycle's change `e3b33679d` (ADR-0136) was scored **❌ BAD** by
   `scripts/score-change.py` at the close of its ADR-0116 window, and the scorer's own `git revert`
   **conflicted and did not land** — so a mechanism the scorer had already rejected stayed in the running
   code for a full further cycle. Deployment is confirmed, so this graded live code and not a stranded
   commit: `ops_jvm.uptimeSeconds 64358` at `traffic.timestampMillis 1785850202149` puts boot at
   **`2026-08-03T19:37:24Z`**, after the commit's `19:15:32Z`.
4. **Danger.** No. `DANGER` is not flagged; we are not bleeding near the cap or the breaker. The live
   problem is the inverse — an idle book with the whole budget unused.

## What I did, and the finding underneath it

Completed the failed revert: `FusionLifecycle` and the `application.properties` provenance comment
restored to `e3b33679d^`, `ConvictionFloorRoutingTest` deleted, ADR-0136 and the ADR index annotated
**Reverted** with the rationale, the loop's accumulated findings kept. `./gradlew -Pci test` green.

The finding is worth more than the revert. **ADR-0136 passed its own falsification test and still lost the
vector.** Both halves held — sub-floor `fusion reduce toward a smaller target` orders went 13 → 0 across
the boot split, the sub-floor `fusion exit — target decayed to flat` still routed so no exit was trapped,
and `fusion_targets` proved real suppression rather than absent opportunity (`NEE`, `JNJ`, `NVDA` planned
non-zero sub-floor deltas that never became orders). The mechanism did exactly what it specified, and the
objective got worse. ADR-0136 had itself recorded that the sub-floor dribble was a *minority* of turnover;
the window has now priced that admission. **Verifying a mechanism is not verifying a fix.**

## Change vs market — attributed honestly

None of this window's PnL move is attributable to any code: the move was **+0.00** with the session closed
and the tape frozen. The revert shipped this cycle has not traded yet, so it will be graded on the next
open, not on this flat window.

What the window does establish is structural, and it shows up in `recent_orders` rather than in the PnL.
Post-boot, `NEE`, `GOOG`, `JNJ`, `CAT`, `CVX`, `PG`, `XOM`, `NVDA`, `AAPL`, `AMZN` and `BAC` — the entire
routed book — exited in one sweep, every row tagged `fusion exit — target decayed to flat` and **every one
carrying `sources=1`**, where the entries preceding them carried `sources=2` and `sources=3`. That is the
breadth collapse at the cash close emptying the book, and it is why the book is DORMANT now. It is now
must-fix item #1. Three mechanisms have been scored BAD against this bleed (ADR-0135, its own revert, and
ADR-0136); all three were routing rules, and the defect is **sensor availability upstream of every routing
rule** — since ADR-0113 the price-driven sensors advance only on prints, so they fall silent at every close
and leave one effective source behind. The desk is not deciding to be flat; it is losing the ability to
hold an opinion, and paying a full round trip for it. The next attempt goes there, and per the register it
must not be another conviction-floor variant nor ADR-0135's hold-through-the-collapse (graded BAD: it kept
risk deployed against a view already measured uninformative). The untried lever the ADR-0135 record already
names is decaying inventory at the ADR-0080 partial-adjustment rate while breadth is absent, so sensor
silence costs neither a full round trip nor a full position's carry.

Shipped the fix for the restart liquidation: the sensor seed's read window is no longer a guess made before the walk — it extends until the walk is satisfied, and every short seed now names why it stopped.

*(Every figure below is read from `/api/risk`, `/api/fusion/targets`, `reports/run-status.json`, the
scored ledger or the boot log. None is authored here — invariant 7 / ADR-0016.)*

## Step 0 — verify last run's change first

**`120b22b41` (ADR-0137) has been SCORED: ⚠️ INCONCLUSIVE** — risk-adjusted return **-0.000284**/cycle
over 7 cycles, **t = -1.39** against the 1.5 hurdle. Kept, not reverted, per ADR-0116. Its primary
VERIFY-BY held to the end: planned gross **$500,000.000044975** against the **$500,000**
`jethro.risk.max-gross-exposure` cap — **1.00000000008995×** — where before the change the planner was
targeting a book it was forbidden to hold. So the mechanism it claimed is verified; the PnL effect is
not distinguishable from noise, which is the honest verdict for a planning-side identity.

**`reports/.pending-baseline.json` is gone.** The measurement window closed, so ADR-0116's freeze is
lifted and this cycle makes a change — the first in six.

`026cda49d`'s flagged auto-revert stays **deliberately not completed** (Rule 303): it is itself the
revert of the graded-BAD ADR-0136, so completing it would re-apply a rejected mechanism.

## Situation — the live money, in plain numbers

1. **Money.** Total PnL **-$628.48**; **-$17.73** on the run and **-$25.84** over the last three. The
   book is bleeding, slowly and steadily. The heartbeat reports growth **0.49%** against the **1.0%**
   owner target: `on_track=False`, `stale=True`, `underwater=True`.
2. **Risk.** Gross **$26,441.20** — **1.8%** of the $1,500,000 firm cap, **$1,473,559** of headroom;
   net **-$20,310.31**, **2.0%** of the $1,000,000 net cap. Nowhere near a cap and nowhere near the
   breaker. This is a **DORMANT** book, which under ADR-0132 is a failure to attack, not safety.
3. **Cause.** Not the market and not last cycle's change: gross fell **-$24,572** on the run while the
   planner's target book sat at the full **$500,000** cap. The desk is not choosing to hold $26k — it
   is being emptied and refilled. Fees are **64.73%** of the entire deficit.
4. **Danger.** No DANGER state: bleeding, but with 98% of the gross cap unused. The correct response is
   the opposite of de-risking — remove what keeps liquidating the book.

## Diagnosis — the mechanism, now measured end to end

The register has carried one item at #1 for five cycles and last cycle priced it. Fills whose
originating trigger carries `sources ≤ 1` are **63** fills, **$388,348.12** of notional and **$39.19**
of fee — **10.04%** of the firm's entire LIVE fee bill — with **69%** of them landing inside ten minutes
of a restart. The tightest instance is one JPM round trip: bought on a three-source view at
**18:38:08.400753Z**, the JVM's own `trend sensor still cold for JPM after seeding 137 of 193` WARN
**1.19 s** later, sold back at **18:38:35.165972Z** on `sources=1`. Held 26.8 seconds, cost **-$2.54**,
learned nothing — the view was never contradicted; the sensor just went blind.

The blindness traces to one line. `SensorWarmup` sizes its history read as `2 × samples × step`, where
`step` is the **median** inter-print gap. The median is the wrong statistic for that job, and the class's
own documentation says why: print gaps are heavy-tailed. The median describes the *typical* gap; the
window has to cover the *sum* of `samples` gaps, which the tail dominates. So the window is
systematically short by an amount that varies with whatever the tail did on that particular boot — which
is exactly the signature the loop measured and could not explain with any per-name rule (BAC seeding
**193 → 192**, NEE **193 → 181 → 171** across three boots). The walk then runs off the oldest point it
*read* while the stored series continues below it — retention is 12h against seeds spanning tens of
minutes — and returns short as though the history had ended.

## The change (ADR-0138)

The read window stops being a guess made ahead of the walk and becomes a consequence of it: when the walk
exhausts what it read while still short, the window doubles and the walk repeats — until the seed fills,
a genuine hole truncates it, or the series demonstrably ends. The shallowest filling window wins, so the
replayed horizon stays as close to live consumption as the store permits. "The series ends here" is read
off the request itself (stopping more than one gap tolerance above the `since` asked for proves nothing
was stored below it), so it costs no extra scan and needs no new store API. The step is monotone across
extensions, so a deeper, more coarsely downsampled read can never turn normal print gaps into fabricated
outages. And every seed now reports its terminator, span, step and read count, so the next cycle grades
this from the app's own log rather than from a replication script.

This is **not** ADR-0135's routing rule, which is graded ❌ BAD and is not re-tried: the defect is a blind
sensor, and the fix belongs where the blindness is. No money, risk or exposure number is introduced — the
class replays prices into a sensor that publishes a conviction, with fusion, the edge gate, the conviction
floor and the pre-trade guardrail all still standing between it and a fill.

## Attribution — change vs market

Nothing is claimed for the change yet; it has not run. This window's **-$17.73** is the running desk's
own liquidate-and-rebuild cycle plus market — **baseline behaviour**, not attributable to code, since no
change was live during it. The scorer owns the verdict from here.

**VERIFY-BY next run:** zero `sensor still cold` WARNs at boot for any name with stored marks; no
`fusion exit — target decayed to flat [… sources ≤ 1]` order within five minutes of JVM start; the
`sources ≤ 1` share of `totalFees` down materially from **10.04%**; and any seed still short naming its
terminator and span in the log.

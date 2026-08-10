# Last analysis — 2026-08-10 19:00Z

**The churn fix from last cycle is VERIFIED and closes; the mirror defect it exposed — the desk sheds a
position its own live view contradicts at 0.83% per cycle — is fixed this cycle (ADR-0147).**

## Situation, in plain numbers

**Money.** Total PnL is **-$372.57**, down **$123.42** on the last run and **$186.94** across the last
three. The book is bleeding, steadily, and the owner's target (+1% every 3 iterations) reads
**-12.07%**. UNDERWATER.

**Risk.** Gross **$104,241.68** — **6.9%** of the $1.5M firm cap, **$1,395,758** of headroom; net
**$31,749.88**, **3.2%** of the net cap. Gross rose **$18,992.85** this run. That is not a danger state,
it is the ADR-0132 deploy mandate working; the drawdown breaker is nowhere near.

**Danger.** No. Bleeding *without* being near the cap or the breaker is a PnL problem, not a de-risking
one, so the answer is to fix what is losing — not to cut size.

**Cause.** Last cycle's change (`4e1ed98a3`, ADR-0145) scored ⚠️ INCONCLUSIVE and was kept. Its *defect*
verdict is ✅ VERIFIED on its own pre-stated terms: `scripts/reversal-rate.py` puts the ALPHA same-name
reversal rate at **0.0659** (11 of 167 pairs) against a baseline of **0.1975** (32 of 162) and a
pre-stated MDE of **<0.080**, clearing the ≥150-pair gate. Fees are now **25.4%** of the loss
(`totalFees $94.669275` on `firmTotal -$372.57213777`) where the four windows before it read 41–48%. The
round-trip machine is off.

## Order-level post-mortem, and market vs change

This window's 60 orders are **57 `fusion entry — target increase`, 1 `fusion reduce`, 2 hedge** — the
round-trip signature is gone from the tape, which is the direct corroboration of the verdict above. But
36 of those 60 were CANCELLED by the ADR-0084 re-plan, and the per-name order sizes climb monotonically
each 30 s tick (BAC 5 → 7 → 10 → 12 → 18 → 21 → 23), which is unfilled intent accumulating, not churn.

**Attribution, honestly:** no logic was deployed this cycle or last (`git log` shows only `docs/`,
`chore(status)` and the ledger since `4e1ed98a3`), and `ops_jvm.uptimeSeconds` **21580** against a
19:00:02Z stamp puts JVM start at **13:00:22Z** — one continuous process. So **the -$123.42 and the
+$18,992.85 of gross belong to the market and to a book still building toward targets set days ago, not
to any change.** ADR-0145 gets credit for the reversal rate and for the fee share, both of which are
measured on its own mechanism; it gets no credit or blame for the PnL move, which cannot be separated
from the tape here and which the scorer already priced as insignificant (t = +0.68 over 13 cycles).

## What the telemetry actually showed, and what I changed

With the exit leg fixed, `fusion_targets` states the mirror defect without ambiguity. **Two of the six
planned names are held on the opposite side of flat from their own target**, at forecasts far above the
conviction floor — MSFT `f = -7.568314`, target `-47.677599`, **held +5**, routing **-0.041494**; WMT
`f = -7.312571`, target `-364.857367`, **held +31**, routing **-0.257260**. Those quantities are exactly
`a × held` for the ADR-0080 rate `a = 1 - e^(-30/3600) = 0.008298707`. The desk decided against these
positions and is taking an hour to shed 63% of each.

The cause is a composition bug, not a missing rule. `PositionBuffer.bufferedDelta` applies ADR-0132's
`onTargetSide` — which resolves a wrong-side destination to flat and whose ADR *claims*
`|held + delta'| = 0` — and then, on the next statement, applies ADR-0107's rating, whose firing
condition **is** the wrong-side case. ADR-0132 therefore delivers 0.83% of its own stated destination per
cycle. ADR-0145's `ConvictionHold` runs downstream and can only shrink an order, so it cannot restore it;
ADR-0118's full resolution runs only under a shut edge gate, and `fusion_targets` reports
`"edgeGate": null`.

**ADR-0147 (shipped):** the unwind of a wrong-side holding is not rated by the ADR-0080 *acquisition*
fraction when the opposing view clears `min-forecast-to-route` — the same strength that would have been
required to open the reverse position. Below the floor every path is byte-identical, so the ADR-0090
wobble-crossing ADR-0145 just removed stays removed. It is the mirror of ADR-0145 and completes one
Schmitt trigger: enter on conviction, exit on conviction, do nothing in between. No number is introduced.
Cutting this is the one cut CLAUDE.md licenses — dead exposure — and it *unblocks* deployment, since
ADR-0102 pins the aim to flat while the holding is on the wrong side; once flat the desk builds the
position it actually wants (MSFT `-47.677599`, WMT `-364.857367`).

## What I checked and did not act on

**No source has positive out-of-sample edge, and it is not close.** Not one reaches |t| = 1.5 at any
horizon: trend 3600s **-5.65 bps, t = -1.35** (n=87); xsreversion 3600s **-2.97 bps, t = -0.65**, cohort-
clustered **-2.21** — significantly *anti*-predictive; reversion 3600s **+3.99 bps, t = +0.86** (n=76);
every 225 s series inside ±0.28 bps on n > 1,200. The combiner has already floored the two negative
sources (xsreversion 0.25, trend 0.285) and lifted reversion to 1.98, so re-weighting cannot create edge
— that is must-fix #2 and it needs a new predictor through the ADR-0049 gate, not a dial. I also logged
but did not attack the fact that the desk holds **16.0%** of its intended book ($36,697 of $228,774
across the names shown): that is the ADR-0080 rate behaving as specified, and deploying more capital into
zero-edge sources scales the loss, not the PnL. Fixing a cost paid *regardless* of edge comes first.

**Verification next run:** `scripts/wrong-side-share.py` (committed with the change) — the convicted-
wrong-side share of held notional must fall below **0.1058** from a baseline of **0.2698**, on a pooled
held-notional gate of **6,035.85**, with ADR-0145's reversal rate not rising back above **0.080**.
`./gradlew -Pci test` green.

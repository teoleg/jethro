The book reopened on MSFT and NVDA after three flat runs, but ADR-0131's re-seed still has not fired once — I traced why: its retry cadence works out longer than the JVM stays up between loop redeploys, so the counter dies before it can elapse; the change is at 2/6 evaluation cycles, so I held and made no new change.

*(Every figure below is read from the live endpoints, `logs/report.md`, `logs/jethro-app.log`,
`application.properties` or `reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

**No code change this cycle** — the scorer reports `efccc6502 still accumulating evidence (2/6 cycles)`
and `reports/.pending-baseline.json` exists, so a second change would destroy the evidence.

## Situation

1. **Money.** Live `/api/risk` `.total` reads total PnL **$147.57045400**, up **+$20.70** on the run and
   **+$20.70** across the last three — the first move after three runs pinned at $126.87240898. Not
   bleeding.
2. **Risk.** Gross exposure **$3288.88500000**, net **$2108.47500000** — **0.2%** of the firm gross cap
   with **$1,496,711** of headroom. No flags. This is a dormant book coming back on with its budget
   almost entirely unused: the opportunity, not the danger.
3. **Cause.** Last cycle's change (ADR-0131, `efccc6502`) is unscored at 2/6 cycles. The book opening is
   **not** its doing — see below.
4. **Danger.** None. Nowhere near the exposure cap or the drawdown breaker.

## Order-level post-mortem and honest attribution

Today's orders are **only MSFT and NVDA** — MSFT BUY ×4 from 14:18Z building to **+6**, NVDA SELL ×3 from
14:28Z to **−3**. Both are positions the desk **opened this window**, so the PnL move is desk activity,
not a held position drifting. MSFT contributes **$77.06615500** total PnL, NVDA **$19.29237792**.

But the enabling cause was **not** ADR-0131. MSFT's σ armed from the **boot seed** at 10:07:11 (the
pre-existing ADR-0071 path, unchanged by ADR-0131) and NVDA's armed off the **live tick stream**. ADR-0131
changed only what happens *after* a failed boot seed, and that path never executed. **It takes no credit
for the $20.70.** The honest split: the desk was finally allowed to act by mechanisms that predate last
cycle's change; how much of the $20.70 is market drift on two intraday positions versus selection cannot
be separated from these numbers, and I am not going to guess a cause.

## What I verified, and the root cause I found

ADR-0131's two VERIFY-BY numbers both read green — σ warmed count **2** (was 1) and non-zero `deltaQty`
(NVDA **−4.127545**, MSFT **−0.040934**) — and both are **false positives** for the mechanism. The second
σ line is NQ at 10:19, a late-arriving instrument's *first* seed. Every boot-cold name still shows exactly
**one** `still cold` line after 24 minutes, and since the code logs on *every* attempt (INFO when warm,
WARN when still cold), that means **the retry has not executed once**.

The reason: `SensorReseed` counts sightings, and cadence × the owning lifecycle's interval gives retry
periods of **40.2 min** (reversion, xs-reversion) and **60.5 min** (σ) against a process that had been up
**23.5 min** and whose previous boot was only **~17 min** earlier — the improvement loop redeploys the JVM
every cycle, and the counters are in-heap. For three of four call sites the retry period **provably
exceeds the process lifetime**, so the counter dies before it can elapse. Only trend's **16.1 min** is
short enough to fire, which is exactly why trend is the one source that recovered. The warm-up length is
the right *seed span* and the wrong *retry period* — that is next cycle's one change, once the scorer
window closes.

Meanwhile item #2 has become the binding constraint on the rest of the book: `strategy_diag.edgeGated`
holds **13 of 20** names out with `no positive OOS edge`, against large real targets (WMT **+386.53347**,
BAC **+535.398702**). That is the ADR-0064 gate working as designed — the answer stays a new signal with
measured edge, never a looser gate.

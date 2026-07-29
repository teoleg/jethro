The desk was handing its biggest positions to the names nothing corroborated — a scaler that means "how much do the sources agree" returns 1 when there is only one source.

*(Every figure below is read from the live endpoints, the order book, or `reports/run-status.json`, or
computed from them by script; none is authored here — invariant 7 / ADR-0016.)*

## Situation

1. **Money.** Underwater, but up on the window. SITUATION header: total PnL **−$74.27**, **+$13.76** vs
   last run, **−$3.52** over the last 3. `pnl_growth_pct` **−136.6%** against the +1%/3-iteration target;
   `on_track=false`, `stale=true`, `underwater=true`.
2. **Risk.** Gross **$26,879 = 1.8%** of the $1.5M firm cap (headroom **$1,473,121**); net **−$2,933 =
   0.3%** of the $1M net cap. No cap flag, no breaker. Gross fell **$11,145** on the window.
3. **Cause.** `d9f8969cc` (the ADR-0123 deploy fix carrying ADR-0122 exploration mode) was scored
   **❌ BAD** — risk-adj −0.000627/cycle over 7 cycles, t=−1.83. It deployed and it is what woke the book
   (gross 0 → $26,879). **The scorer's auto-revert did not land**: the snapshot says `"revert": true` but
   no revert commit exists — it conflicted with later commits touching the same loop files. Left
   un-reverted deliberately, because reverting it would undo the deploy fix (no future change would reach
   the JVM) and exploration mode, returning the desk to DORMANT. Logged as must-fix #3.
4. **Danger.** No. Bleeding, but ~98% of the gross cap is unused and the breaker is nowhere near.

## Step 0 — the standing #1 must-fix is FALSIFIED, not fixed

Must-fix #1 was the delayed-price cohort: GOOGL/NQ/TSLA/ES supposedly quoted off a 15-minute Yahoo poll,
so the desk posts limits at a price the market has left. **This run's live telemetry says the mechanism
is not there.** `/api/marks`, read directly: GOOGL **1.0s**, NQ **0.9s**, TSLA **0.7s**, META **0.5s**,
NFLX **0.6s**, ORCL **0.5s** — all `source=alpaca`; `/api/risk` reports `markAgeMillis` **50** on every
open position. The only stale marks are **GBPUSD and ES at 1383s**, and both carry **zero** exposure and
are not traded. The cohort's loss share is still elevated — **26.7%** of turnover against **58%** of the
book's gross losses (GOOGL −$40.71, NQ −$35.82, TSLA −$2.91 of −$136.82) — but a price-age gate would
have been a fix for a defect that is not present. Item 1's own VERIFY-BY said to fall back if the feed
thesis did not hold; it does not, so it is closed as falsified and the target moves up.

## The real defect, and it explains the same names

`/api/fusion/targets`, live this cycle:

| instrument | sources | agreement | \|combined forecast\| |
|---|---|---|---|
| META | **1** | **1.000** | **15.41** |
| TSLA | **1** | **1.000** | 2.98 |
| NVDA | 3 | 0.812 | 8.44 |
| MSFT | 3 | 0.972 | 7.87 |
| JPM | 3 | 0.871 | 6.82 |
| JNJ | 3 | 0.674 | 6.50 |

ADR-0119's scaler is `|Σwᵢfᵢ| / Σwᵢ|fᵢ|` — the share of the sources' gross conviction that survives as a
net view. With **one** source there is nothing to cancel against, so it is **1 by construction**.
ADR-0119 recorded that as a safety property ("every single-source name byte-identical"); downstream it is
read as *confidence*. The result is an inversion: the uncorroborated names carry the **largest**
convictions in the book — META at 15.41 is 1.8× the best-corroborated name — and the ADR-0076
diversification multiplier is pushing the other way at only **1.000 vs 1.155**, far too small to offset
1.000 against 0.674. And the loss cohort I had been calling "delayed" is *exactly* the single-source
cohort: META and TSLA are `xsreversion`-only, which is the discovery-promoted set. The feed was the wrong
explanation for the right names.

The order book shows the cost: META targets **−78.3** shares against a holding of **0**, and TSLA's
oversized target produced **fourteen consecutive** `fusion re-plan — passive order superseded by a fresh
target` cancellations in `recent_orders` against a held position of **one** share.

## The change (ADR-0124, Proposed)

Size the conviction on the sources' **dispersion**, not on whether their signs cancel:

```
s²        = Σ ŵᵢ (fᵢ − μ̂)² / (1 − Σ ŵᵢ²)          // unbiased weighted variance (Bessel/Kish)
agreement = |μ̂| / √(μ̂² + s²) = 1/√(1 + (s/μ̂)²)
```

`1 − Σŵᵢ²` is the residual degrees of freedom of a weighted variance — at equal weights it is `(n−1)/n`,
reproducing `Σ(fᵢ−μ̂)²/(n−1)` exactly, and its reciprocal is Kish's effective number of sources. **At one
effective source it is zero**: the dispersion is *unestimable*, which is not the same as zero, so the
honest scalar is 0. Self-healing, not a ban — any second sensor warming sizes the name again. Worked by
hand and pinned in tests: +20@3 against −20@1 gives exactly **1/3** (the sign ratio said 0.5); +10 against
+20 gives **0.9045** (the sign ratio said 1.0, blind to magnitude); one source gives **0**. No number is
introduced, it is one-way (`s² ≥ 0` ⇒ scalar ≤ 1, so the book can only shrink), and the sign is never
flipped. Honest cost, in the ADR and in a deferred-register row: it narrows ADR-0111 — standing a
measured-losing source down can leave one surviving view, now sized at zero — and every multi-source name
shrinks somewhat, so the whole book sizes down first.

## Attribution — change vs market, honestly

No code change has been live for three cycles, so **none** of the window's +$13.76 / −$11,145 gross is
attributable to a change of mine; it is the standing exploration configuration trading against the
market, and the gross fall is mostly the ES and NQ round-trips closing to flat. Do not credit or blame a
change for it. What *is* attributable next cycle is ADR-0124, and its falsifier is written down: if the
`sources=1` ordering corrects but firm realized bps does not improve over the evaluation window, the
inversion was cosmetic and the target moves to the one-sided LIMIT-in/MARKET-out execution scheme
(must-fix #2), which needs an ADR superseding ADR-0084 rather than a dial.

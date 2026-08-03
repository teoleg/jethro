The desk is trading a 30-second cadence against sources whose own telemetry shows no measurable return until the 900–3600s horizon — so every view is round-tripped before it can pay, and ALPHA's loss is smaller than the fees it paid to produce it.

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/signals/telemetry`,
`/api/fusion/targets`, `recent_orders`, `turnover_cost_by_name`, `tca`, the scorer's output, or the repo
source. None is authored here — invariant 7 / ADR-0016.)*

## No code change this cycle — the pending change is still under measurement

`scripts/score-change.py score` prints **`74a47adee still accumulating evidence (5/6 cycles) — held, not
scored this run`**, and `reports/.pending-baseline.json` is present. Under ADR-0116 a new change stacked on
a pending one destroys its evidence. So this run verifies, falsifies, re-ranks and records; it ships no
code. ADR-0135 scores next cycle, and the change below is queued behind it.

## Situation — the four questions

1. **Money.** Total PnL **`-76.36276038`**, `-37.44` since the last run and `-81.37` across the last three.
   By book: `HEDGE` **`+133.36821883`**, `ALPHA` **`-152.93147385`**, `MACRO` **`-56.79950536`**.
   `UNDERWATER`, off the growth target (`pnl_growth_pct -219.71` vs `pnl_target_pct 1.0`).
2. **Risk.** Gross **`$91,407.48`** — **6.1%** of the firm cap, headroom **`$1,408,593`**; net **`-1543.36`**,
   **0.2%** of the net cap. Gross rose **`+5,654.59`** this run and **`+66,931.45`** over three. This is
   the mandate working, not a danger: the book is deploying with room to spare. Not DORMANT, not NEAR CAP.
3. **Cause.** ADR-0135 is at 5/6 and unscored. It is now **✅ VERIFIED on its own stated criterion** for the
   first time (below). The live loss is **not** direction — it is cost, and the mechanism is now named.
4. **Danger.** No. Not near the cap, breaker not tripped. The live problem is a **negative-expectancy
   trading loop**, which is a design defect, not a risk-limit event.

## Step 0 — ADR-0135 (`74a47adee`): ✅ VERIFIED — the guarded branch finally fired

Three windows running, this branch had no traffic and I refused to grade it (Rules 239/240). This window it
was exercised. `recent_orders` contains a **`sources=1`** cycle — `JNJ BUY 9 [forecast=-0.0, sources=1]` at
15:40:00Z — and across the whole window the string **`fusion exit — target decayed to flat` appears zero
times**. That trigger is precisely what ADR-0135 was shipped to stop: before it, one effective source zeroed
the combined forecast, ADR-0090 read flat as a decision and liquidated the entire position at full urgency.
The one order that did fire at one source was a **partial** `fusion reduce toward a smaller target`, i.e. a
downstream risk stage acting against the held target — exactly the escape hatch the ADR keeps open by design.
The PnL verdict remains the scorer's at 6/6; this is the defect-level verification.

## 🔴 Last cycle's item #1 is FALSIFIED — the desk is not blocked from opening

Last block concluded a veto conditioned on `currentQty == 0` from a clean split (4/4 flat names at
`deltaQty 0.0`, 5/5 held names non-zero) and made it #1. **Both halves fail this window.** Held names now
sit at exactly zero too — `PG` (`currentQty -106`), `JNJ` (`-76`), `NVDA` (`-44`) all at `deltaQty 0.0` —
and the order log shows flat names being **opened**: `PG SELL 52 · fusion entry — target increase` from
flat, plus CAT, XOM, CVX, JPM. The window carries **41** `fusion entry — target increase` orders. There is
no opening veto. The zero deltas are a snapshot artefact — `/api/fusion/targets` is stamped 15:59:43Z, after
the last order at 15:57:42Z, with ADR-0084 re-planning every 30s. Item struck; this is the third causal
story on this item to die on contact with data (Rules 234, 241), and the reason is always the same — a
mechanism inferred from an aggregate rather than measured.

## The real #1 — the execution cadence is 4–16× faster than any horizon with measurable edge

`/api/signals/telemetry`, `avgReturnBps` by source and horizon, is unambiguous:

| source | 225s | 900s | 3600s | fusion weight |
| --- | --- | --- | --- | --- |
| reversion | `0.047` | `0.752` | `3.1947226656494396` | `1.6383242369546946` |
| social | `0.490` | `1.225` | `4.6399547951434625` | `1.6087300321666014` |
| trend | `0.031` | `0.357` | `-0.674` | `0.8246239286767585` |
| momentum | `-0.666` | `2.129` | `-3.409` | `0.7275477047443887` |
| xsreversion | `-0.101` | `-1.144` | `-7.717` | `0.25` |

At **225s — the horizon the desk actually trades** — every source sits inside ±0.7 bps and hit rates run
`0.424`–`0.504`. Against that, one side of a round trip costs `fee_bps 1.00` on equities
(`turnover_cost_by_name`) plus `avgSlippageBps` of `0.59`–`0.73` on the liquid names (`tca`). **The cost of
trading exceeds the gross expectancy of the best source at that horizon.** The two heaviest weights,
`reversion` and `social`, are exactly the sources whose returns only become non-trivial at 900–3600s.

The order log shows this executing. JNJ: `SELL 11 @ forecast -18.13` (15:47:05Z), `SELL 22 @ -17.12`,
`SELL 17 @ -14.93` (15:49:06Z) — then **31 seconds later** `BUY 21 @ -0.32`, and BUY 4/7/1/7 after it. Sold
50, bought back 40, inside eight minutes, on a forecast that decayed to nothing between the entry and the
fill. XOM sold 92 at forecasts `-5.06` to `-10.33`, then bought back 60 at forecasts `+1.28` to `+3.75` —
a full sign flip. The cumulative form is `turnover_cost_by_name`: MSFT `189` fills and `193,554.74` turnover
while flat; PFE `95` fills, `193,675.36` turnover, flat; firm-wide `FILLED 4945` / `CANCELLED 1784`.

This closes the standing "work on EDGE, not the combiner" question with a real answer: **there is edge here,
but not at the horizon being traded.** `social` at 3600s (`hitRate 0.5734265734265734`, `avgReturnBps
4.6399547951434625`, `resolved 298`) and `reversion` at 3600s (`3.1947226656494396`, `resolved 485`) are the
only candidates worth capital, and the desk currently churns straight through both. Re-weighting cannot fix
this — the combiner is being asked to shape a view the execution layer destroys before it resolves.

## What ships next cycle

Once ADR-0135 scores, the one change targets this: hold a position for the horizon its own source measures
at, rather than re-planning it every 30s. The honest form is a minimum-holding / re-plan-suppression rule
keyed to the dominant contributing source's measured horizon, with the deterministic floor untouched — the
trailing cut, drawdown breaker and pre-trade guardrail must still be able to exit at any moment. It is
architecturally significant and will carry its ADR in the same commit. **VERIFY-BY:** per-name `fills` in
`turnover_cost_by_name` falls against a flat-or-higher `qty`, and no name shows an entry and its reversing
unwind inside one source-horizon in `recent_orders`.

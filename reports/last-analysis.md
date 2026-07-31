The hedge's beta-covered subset is now sign-INVERTED against the book — it would sell the proxy against a net-short desk — and the only thing preventing that trade is a second defect: the proxy has had no price for 42 minutes.

*(Every figure below is read from `/api/risk`, `/api/hedging`, `/api/marks`, `/api/fusion/targets`,
`logs/report.md` or the live Postgres. None is authored here — invariant 7 / ADR-0016.)*

## Why there is no code change this cycle

`scripts/score-change.py score` prints `e61c7f5aa still accumulating evidence (4/6 cycles) — held, not
scored this run`, and `reports/.pending-baseline.json` still exists. Stacking a second change onto one
still under measurement destroys the evidence, so this cycle is diagnosis only.

## Situation

**Money.** SITUATION header: total PnL **$575.87**, PnL **-85.28** since last run and **-162.78** over the
last three. Live `/api/risk` `.total` minutes later: `totalPnl` **570.29561460** = `realizedPnl`
**796.09988240** plus `unrealizedPnl` **-225.80426780**. Heartbeat `2026-07-31T15:10:13Z`:
`pnl_growth_pct` **-9.67** against `pnl_target_pct` **1.0**, `on_track` **false**, `stale` **true**,
`underwater` **false**. Off target, bleeding mildly.

**Risk.** Gross **$77,739.62** — **5.2%** of the $1,500,000 firm cap, headroom **$1,422,260**; net
**-$45,265.26** — **4.5%** of the $1,000,000 net cap. **Flags: none**, breaker untripped. Not the DANGER
state: exposure this far under the cap is the objective being met, not a concern.

**Cause — market vs mechanism, honestly split.** No code change was made this cycle, so nothing is
attributable to one. The desk is net **short** equity (**-$38,817.87** across 21 names) into a rising tape,
and the negative `unrealizedPnl` against a much larger positive `realizedPnl` is exactly that
mark-to-market — **market**. The one mechanism-attributable cost is turnover:
`turnover_cost_by_name` totals roughly **$1.6M** of traded notional against a **$77.7k** book, and `fees`
moved **105.584233 → 129.292241** between the baseline snapshot and the latest heartbeat.
`orders_by_status` reads FILLED **4495** / CANCELLED **1509**, every cancel reasoned *"fusion re-plan —
passive order superseded by a fresh target (ADR-0084)"* — the desk paying for its own 30 s re-plan cadence.
That is a standing cost, not this cycle's doing.

## Step 0 — grading last cycle's change (`e61c7f5aa`, ADR-0133, the band cap)

**Deployed: ✅** — `ops_jvm.uptimeSeconds` **1166** at report generation and **1216** on a later direct
read; the running process is the fix build. **Effect: ⚠️ still unscored, and being exercised** —
`/api/fusion/targets` publishes **22** instruments with non-zero `deltaQty` on MSFT **-1.712278**, CAT
**4.165898**, NEE **-60.416979**, and exactly 0.0 on NVDA and XOM despite enormous gaps (NVDA `targetQty`
**282.837575** vs `currentQty` **45.000000**). **Regression check: none attributable** — breaker untripped,
`/api/marks/quarantined` **[]**, no new WARN/ERROR classes.

## What the telemetry showed — item #1 escalates, and a new item #2 is masking it

Last cycle's #1 was that the structural hedge sizes on only the 8 equities carrying a `hedge_beta`. That is
still true — live `instrument_attributes` has exactly **8** `hedge_beta` rows (AAPL 1.25, AMZN 1.20, GOOG
1.05, JNJ 0.55, JPM 1.10, MSFT 1.10, NVDA 1.75, SAP 1.00) against **28** equities in the master. But
recomputing the coverage against *this* cycle's book makes it worse than a coverage gap:

- net equity **-38,817.87**; beta-covered net **-11,126.16**; **uncovered -27,691.71 = 71.3%**
- **Σ βᵢ·Eᵢ = +5,657.02** — **positive**, against a book whose net equity is **negative**

NVDA's **+10,371.04** at beta **1.75** contributes **+18,149** of systematic on its own and flips the
covered subset's sign. `HedgeMath.structuralBetaHedge` sets `hedgeNotional = systematic.negate()`, so on
these numbers the structural tier would size a **SELL** of the equity proxy against a desk that is already
net short equity — *adding* systematic exposure. Last cycle the same sum was **-18,856.99** against net
**-60,974**: under-sized but correctly signed. The covered subset is a biased sample and its sign carries no
guarantee. Under-hedging is a gap; wrong-signed hedging is an anti-hedge.

The only reason that trade is not happening is a **second, new defect**. The EQUITY axis read **WARMING**
six consecutive times over two minutes — `tier` **"—"**, `targetProxyQty` **null**,
`rawTargetNotionalUsd` **null** — on `netExposureUsd` between **-43051.55** and **-38282.08**. This is not
a warming transient (the trap Rule 183 was written for): ES's `providerTimestampMillis` is **frozen at
1785509694000** across three polls 30 s apart, age climbing **2477.5 s → 2507.5 s → 2537.7 s**, past 42
minutes, while AAPL ticks at ~1 s and `/api/marks/quarantined` is **[]**. Without a positive price
`HedgeAdvisor.candidate()` returns null and no tier can size (the multiplier is not the missing input — it
has a config fallback of **50**). Downstream, the HEDGE book's held proxy is priced at nothing: `quantity`
**0.022800**, `hasMark` **false**, `mark` **0.00000000**, `grossExposure` **0.00000000** — so the hedge
contributes **$0** to the firm total exposure the loop optimizes, which CLAUDE.md defines as the whole book
*including* the hedge.

## The decision

**Item #1 stays the beta coverage/sign defect, and item #2 (the dead proxy price) is ranked below it
deliberately** — fixing the price first would convert a passive gap into an actively wrong-signed hedge.
Next cycle, once ADR-0133 scores, the change makes the structural hedge derive each name's beta **in code**
from the durable mark history the platform already stores, and refuse to claim neutrality while coverage is
partial. I will **not** hand-author betas: CLAUDE.md names a `β=1.0` placeholder as a lesson already paid
for, and a self-chosen beta that sizes a hedge is precisely the invented risk number the house rules forbid.
Because it changes how a risk control is sized, it ships with an ADR in the same commit.

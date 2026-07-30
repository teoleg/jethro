ADR-0131's re-seed is at 4/6 evaluation cycles so I made no change; I re-ran its pre-registered test on a fresh JVM and it failed again — four more sensors moved backwards — but the same tape shows the retry warming PG, which pins the defect to non-monotonicity and gives a one-condition fix for next cycle.

*(Every figure below is read from the live endpoints, `logs/report.md`, `logs/jethro-app.log` or
`reports/run-status.json`. None is authored here — invariant 7 / ADR-0016.)*

**No code change this cycle.** `reports/.pending-baseline.json` still holds `efccc650` and no new ledger
row has appeared; counting heartbeats since the baseline gives **4 of 6** cycles. Piling a second change
on top would destroy the measurement.

## Situation

1. **Money.** Live `/api/risk` `.total` reads total PnL **$128.22843661** — realized **$177.46496415**,
   unrealized **−$49.23652754**. The report's SITUATION header computes **−$57.35** on the run and
   **+$1.36** across the last three. So PnL *is* lower run-over-run, and the split says exactly why:
   **realized PnL rose** (from **$159.63438842** read live last cycle to **$177.46496415**) while
   **unrealized swung from +$18.39164203 to −$49.23652754**. Nothing was lost in a closed trade; the
   decline is marks on positions opened this window. `run-status` last heartbeat still reads
   `pnl_growth_pct` **46.27** vs target **1.0**, `on_track=true`, `stale=false`, `underwater=false`.
2. **Risk.** Gross **$40994.69587500**, net **$49.80412500** — **2.7%** of the firm gross cap with
   **$1,459,005** of headroom, **0.0%** of the net cap. Flags: **none**. And note the *shape*: **19**
   equity positions carrying **$13298.56500000** net long against one ES short at **−$13248.76087500**,
   so the book is running gross with almost no directional net. Gross rising into 97% unused budget is
   the goal, not a risk event.
3. **Cause.** Last cycle's change (ADR-0131, `efccc650`) is unscored at 4/6. It gets **no credit and no
   blame** for the PnL move — see the attribution below.
4. **Danger.** None. Nowhere near the exposure cap or the drawdown breaker, and the loss is unrealized on
   fresh intraday positions, not a realized bleed.

## Order-level post-mortem

The window traded AAPL, NEE, GOOG, MSFT, BAC, JPM, WMT, NVDA, JNJ, PFE plus the HEDGE ES leg. Every
`CANCELLED` row carries the same `reason` — *"fusion re-plan — passive order superseded by a fresh target
(ADR-0084)"* — ordinary passive re-planning, but the churn is heavy enough to watch against `totalFees`
**$41.996983** once the sensor work closes. The unrealized drag is concentrated in **two** names:
NVDA **−$27.85750000** (20 long, realized **+$37.23710000**) and UNH **−$24.72000000** (11 short) — those
two exceed the whole firm unrealized figure, and the rest of the 19-name book is within a few dollars
either side of flat. On realized PnL the standouts are MSFT **+$113.82670000** and the hedge
**+$135.95482734** against GOOGL **−$40.71220000**, JPM **−$48.29160000**, AMZN **−$31.72520000** and
MACRO's flat NQ **−$35.82347655**.

## Step 0 — verifying ADR-0131 against its own pre-registered test

Last cycle I wrote a two-leg VERIFY-BY. This JVM is a **different process** (booted 11:06:51,
`uptimeSeconds` **1392**), so this is an independent reproduction rather than a re-reading:

- **Leg 1 — "no negative wave-over-wave seeded-count delta anywhere" → FAILED.** Four names went
  backwards between the 11:07:0x wave and the 11:23:1x wave (**16 min**, the predicted 193 × 5 s cadence):
  **XOM 179→151**, **CVX 165→146**, **JNJ 160→149**, **JPM 150→148**. Different process, different names
  than last cycle's six, identical defect. **⚠️ STILL-BROKEN.**
- **Leg 2 — "distinct trend-cold names below 27 / 25 repeating" → 26 distinct / 24 repeating.** Below, by
  one name, and that one name is the win below — not a meaningful pass.
- **But the retry produced its first genuine success, and it is the most useful line on the tape.** PG:
  11:07:04 `still cold ... 190 of 193`, then 11:23:14 `trend sensor warmed PG from 193 stored prices
  (needs 193) — warm`. Nothing but the ADR-0131 retry can emit that. So the mechanism is right; its
  **non-monotonicity** is the entire defect. Eight names advanced (CAT 136→180, HD, PFE, TSLA, GOOGL,
  EURUSD, META, NFLX), four regressed, twelve stood still.
- **Class A reconfirmed:** the same **12** rate/swap names seeded **193 of 193** in *both* waves and are
  still cold — the sample count is not their binding predicate, so re-seeding them is pure waste.

I read the code to pin the mechanism to a line: `warmWhileCold` calls `forecaster.forget(...)`
**unconditionally** before it knows what the replay will yield, and `SensorWarmup` reads
`step × samples × LOOKBACK_MULTIPLE` back from the *current* mark's provider timestamp while re-deriving
`step` from whatever that read returned — so both ends of the window and the thinning stride move between
waves, and the count is free to fall onto a sensor that has just been wiped.

**Next cycle's fix, and why it is one condition rather than two:** `SensorWarmup.seedPrices` is already a
pure function returning the list before anything mutates. Compute it first, and only `forget()` + replay
when it is **strictly larger** than the best seed this name has achieved. Class B keeps its prints; Class A
sits at the maximum so it retires itself from retrying with no separate rule; PG's 190→193 is strictly
larger so the one real win survives. It is a sample-count bug fix — no money, risk or exposure number
changes — so no ADR is needed.

## Attribution this window (honest split)

**Market, not the change — and the enabling event again was not ADR-0131.** What unlocked the full book
was the **boot seed**: **19** σ sensors warmed at 11:07:29 (JPM, MCD, MSFT, BAC, KO, NVDA, PFE, NEE, CAT,
JNJ, WMT, XOM, AMZN, GOOG, CVX, PG, AAPL, HD, UNH), against 7 last cycle and 1 the cycle before — the
pre-existing ADR-0071 path, untouched by ADR-0131 — which lifted the ADR-0126 σ veto across the desk. The
PnL move itself is **mark-to-market on positions the desk opened this window**, and it is dominated by two
names (NVDA, UNH) whose marks moved against freshly-established entries. That is market drift on new
positions; whether the *selection* of those two was good cannot be separated from 30 minutes of marks, and
I am not going to guess a cause. ADR-0131 takes neither credit nor blame: its only attributable effect on
this tape is warming PG's trend sensor and regressing four others' warm-up progress, none of which is a
position.

## What remains blocking the rest of the book

`strategy_diag` reads `measured` **29**, `tradable` **16**, `edgeGated` **13 names**, each annotated
`no positive OOS edge` — PFE (momentum **−137.76114263**), PG (**−82.99627912**), GOOGL
(**−58.18505489** / **−41.67661313**) and ten more. That is the ADR-0064 gate working as designed. Per
the standing priority the answer stays **a new signal with genuinely measured edge**, never a looser gate —
and that is item #2, queued behind the sensor fix.

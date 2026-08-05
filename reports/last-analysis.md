Held the ADR-0116 freeze for a fourth cycle (ADR-0139 at 4/6) and re-verified the buffer deadlock — but this cycle found one row the deadlock arithmetic does not predict, and recorded it rather than papering over it.

*(Every figure below is read from `/api/risk`, `/api/orders`, `/api/fusion/targets`, `/api/signals/telemetry`,
the app's own WARN stream, the named source files and the scored ledger. None is authored here — invariant 7
/ ADR-0016.)*

**Money.** Total PnL **-$603.08**, flat to the cent across four heartbeats now (**+0.00** since last run,
**+0.00** over the last three). Gross **$0.00**, net **$0.00** — **0.0%** of the firm gross cap
$1,500,000, so **$1,500,000** of headroom sits unused. UNDERWATER and DORMANT; growth **0.0%** against the
**1.0%**/3-iteration target, `on_track=False`, `stale=True`.

**Risk.** No danger state: VaR **0.00** (`"note": "no positions"`), breaker not halted, regime CALM. The
problem is the inverse of danger — the book is not on. `orders_day.total` is **0** and the newest order in
the book is still **2026-08-04 21:00:47Z**, so the desk has now sat out four boots and a full session open.

**Cause.** Unchanged and still upstream of the money: the no-trade band. Re-read from source this cycle,
`PositionBuffer.band` = `|target| × Forecast.TARGET_ABS / |forecast| × width`, with `TARGET_ABS` **10.0**
(`Forecast.java:20`) and `width ≥ bufferFraction = 0.5` (`widthFor`, `application.properties:313`), while
`nextAim`'s `withinTarget` clamps the aim to `|aim| ≤ |target|` on the target's own sign. Held is **0**
everywhere, so `|gap| ≤ |target|` and `band ≥ |target| × 5.0 / |forecast|` — the delta is identically zero
for any name with `|combinedForecast| ≤ 5.0`, and the strongest in the planned book is **3.146893**.

**What I got wrong last cycle, and am not hiding.** I called this deadlock absolute — "identically zero,
forever", `insideBuffer` **22 of 22**. This cycle it reads **21 of 22**: NQ shows `deltaQty` **0.001584**
against `targetQty` **0.136033** at `combinedForecast` **3.146893**, which by the arithmetic above should be
inside its band. The escape is immaterial to the money (a fraction of one contract; `orders_day.total` is
still **0**), but it means my mechanism mis-predicts one of 22 observed rows, and a fix aimed at a mechanism
that mis-predicts a row may be aimed at the wrong line. I have not resolved it from the source this cycle
and I am not guessing at it — it is recorded in `reports/must-fix.md` as a required unit test for the fix:
reproduce NQ's exact inputs and explain the non-zero delta before changing the band.

**Sensors are warm, and the book is still flat.** `streamVolMeasuredNames` **20**, `volBudgetNames` **20**,
`covarianceCoveredNames` **20**, `routing: true`, **22** planned targets (PG **232.454026**, AMZN
**144.748853**) — and zero orders. That re-confirms the demotion of the warm-up item: it self-heals within a
session, so fixing it would put no risk on.

**Decision: no code change.** `scripts/score-change.py score` reports `d51f179a2` (ADR-0139) **still
accumulating evidence (4/6 cycles)** and `reports/.pending-baseline.json` still names it, so under ADR-0116
a new change would destroy the evidence. ADR-0139's mechanism ✅ verified again on this fourth boot (**17**
corroborations in **1362 s** of uptime versus **18 in 64,010 s** pre-fix), and for the first time its second
VERIFY-BY reads positive: the app logged `1 corroborated social subject(s) — advisory context only
(ADR-0050), no order` — corroboration is reaching a named subject, and what stops it sizing is the ADR-0050
advisory gate, a separate deliberate policy. Separately, `social`'s expectancy swung a fourth time —
**8.855736 → 4.837178 → 7.810160 → 8.351863** bps on seven additional resolved observations — so the
standing decision request to Oleg on `jethro.fusion.social.per-channel` stays open and explicitly
**NOT-YET-SUPPORTED**; that dial is the owner's and the loop does not touch it.

**Attribution.** No code change landed this window and no order was placed, so neither market nor change
claims the move — PnL is flat to the cent because the book is empty. This window is baseline.

The loop has been restarting the trading app on every cycle — including the no-change cycles meant to leave a pending change alone — because the finding-file write the prompt mandates each run sits outside `reports/`; fixed so only a commit that can reach the app binary deploys (ADR-0142).

*(Every figure below is read from `logs/report.md`, `reports/run-status.json`, `git diff` and the
scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

**Money.** Total PnL **-715.25675611**, down from **-628.06833967** at the 13:44Z baseline; over the last
3 runs **-87.19**. The book is underwater and off the +1%/3-iteration target.

**Risk.** Gross **$7,852.68** — **0.5%** of the firm gross cap $1,500,000, headroom **$1,492,147**; net
**-$3,751.44**, **0.4%** of the $1,000,000 net cap. `breaker.halted` **false**, `var95` **100.57**,
`es95` **152.04** over **155** observations, `skippedExposure` **0.00**. Nothing near a cap or the
breaker — UNDERWATER is a statement about cumulative PnL, not a live danger state.

**Cause.** ADR-0141 (`a21177cea`, held by the scorer at 2/6 cycles) is still ✅ verified at the defect
level and got stronger: `orders_day.total` **0 → 25**, and a second name entered on the repriced band —
**MSFT SELL** filled 14:28:03Z / 14:29:04Z / 14:29:34Z on `combinedForecast` **-6.0663342533450155**,
with live `aims` MSFT **-8.668719** against `targetQty` **-150.842073**. The window's loss is not that.
The baseline book was empty, so market drift on untouched positions is ~0 and all of the move is the
change's own trades. NQ opened 13:58:29Z on `combinedForecast` **-7.858987731814552**, the app
**restarted at 14:06:35Z**, and from 14:14:51Z eight `fusion reduce toward a smaller target` orders
bought back **0.025280** of a **0.030886** short as the forecast fell to **-1.314462997304728E-4**,
into a mark moving **29435.50000000 → 29600.50000000** — NQ `realizedPnl` **-124.25140691**,
`unrealizedPnl` **-18.49980000**. NQ is *not* in the cold-sensor list, so I do not claim the restart
caused that particular decay; the two cannot be separated from these numbers alone.

**What I found, and changed.** The restart was self-inflicted. `git diff --name-only 493ab5d 955d41a |
grep -v '^reports/'` returns exactly `docs/loop-findings.md` — the entire non-`reports/` diff of a
deliberate **no-change** cycle — and `uptimeSeconds` **1406** puts the process start 23 seconds after
that cycle's status commit. Every forecast/risk sensor is a warm-up-gated estimator whose seed walk
terminates at the first gap in stored marks, and that gap *is* the previous restart: **19** `risk-cut σ
sensor still cold` lines, **0** warmed, each stopping on `GAP_BREAK`/`HISTORY_EXHAUSTED` covering
**~2120–2170s**, against a σ seed needing **121** prices at a **30000ms** step (≈3630s) and a reversion
seed needing **241** at **10000ms** (≈2410s). So the desk warms a couple of names at the tail of each
cycle and is then reset — `instruments` **24**, `insideBuffer` **23**, `streamVolMeasuredNames` **2**,
with `aims` non-zero for exactly those two names and **0.0** for the other twenty-two (WMT `targetQty`
**-566.406006**, AAPL **226.814437**, both `deltaQty` **0**), and MSFT — deepest σ seed at **84** of
**121** — the one name that traded. It also corrupts the loop's only instrument: ADR-0116 judges a
change over ~6 cycles, and a book force-flattened partway through each of them measures the restart,
not the change. That is a sufficient explanation for a ledger of INCONCLUSIVE verdicts and for three
consecutive scored rows ending at firm gross **$0.00**. The fix is one line of the loop wrapper: deploy
only on a path that can affect the app binary (`NON_BINARY_PATHS='^(reports|docs|ops)/'`). No dial,
gate, signal, sizing control or risk number is touched; nothing inside the app is touched.

**On the freeze.** `reports/.pending-baseline.json` exists for `a21177cea` at 2/6 cycles, so the
ADR-0116 rule would normally hold me to no change. I shipped anyway for a reason internal to that rule:
**there was no no-op available** — writing only the mandated finding would itself have deployed and
restarted the app, exactly as it did last cycle. The freeze exists to protect the pending change's
evidence, and under the old filter honouring it destroyed that evidence every 30 minutes. This removes
an exogenous confound from the window rather than adding one. The residual is stated honestly:
ADR-0141's window now runs partly with, and partly without, forced mid-cycle liquidation, so its
eventual verdict is weaker evidence than a clean window would give. `-Pci test` green (no Java changed —
every module task UP-TO-DATE); the shell change is covered by `bash -n` plus the deploy/no-deploy path
table in ADR-0142.

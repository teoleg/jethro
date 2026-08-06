No code change — the ADR-0116 freeze holds at 3 of 6 — and a third consecutive clean window finally dates the defect: entry is gated, exit is not, so the desk needs ~1.1 h to build a position and ~7 min to dismantle it.

*(Every figure below is read from `logs/report.md`, `reports/run-status.json`, `reports/.pending-baseline.json`,
`git diff` and the scorer's own output, or is arithmetic on those read fields. None is authored here —
invariant 7 / ADR-0016.)*

**Money.** Total PnL **$-789.39**, gross **$9,895.16**, net **-$1,266.10**. Since last run PnL
**-22.13**, gross **-9352.56**; over the last 3 runs PnL **-70.25**, gross **-1529.75**.

**Risk — not a danger state.** Gross is **0.7%** of the firm gross cap $1,500,000 (headroom
**$1,490,105**); net is **0.1%** of the $1,000,000 net cap. `breaker.halted` **false**, `regime`
**CALM**, `riskCuts` **[]**, `portfolioRiskMultiplier` **0.702264471357967**. The book is deploying with
room to spare; UNDERWATER is the cumulative figure, not a live threat.

**Why no change.** `scripts/score-change.py score` prints `39451ce71 still accumulating evidence (3/6
cycles) — held, not scored this run`, and `reports/.pending-baseline.json` still names that commit. Per
ADR-0116 a new change now would destroy the evidence under measurement. I also deliberately did **not**
run `score-change.py baseline` — it overwrites the pending record unconditionally and would delete
ADR-0142's own evaluation window (Rule 396).

**Step 0 — ADR-0142 ✅ VERIFIED, third independent confirmation.** `ops_jvm.uptimeSeconds` **4721** at
report `timestampMillis` **1786032002760** implies a boot instant of **1786027281760**, against
**1786027281702** and **1786027281035** the two prior cycles (sub-second read skew) — the same 14:41:21Z
process, three cycles and two reports-only commits later. `git diff --name-only d8a9437..HEAD` lists
`reports/run-status.json` and nothing else. The item stays struck.

**Attribution — 100% market.** Zero Java, zero dials, zero gates in the window's diff, third cycle
running. The **-22.13** PnL and **-9352.56** gross moves are the market plus code already live; nothing I
did earns credit or blame for either.

**What the clean window bought.** Three uncontaminated cycles let me measure the desk's own timescales
off the order tape rather than compare horizons in the abstract — and the mechanism inverted. Expectancy
is still monotonic in horizon and still negative for **every** source at 225 s (trend **-0.016** →
**+0.017** → **+1.829**; social **-0.349** → **+0.700** → **+4.257**). The new readings are the damning
ones. Median same-name reversal is **425 s**, with MSFT covering 40% of a short **61 seconds** after
opening it — at a forecast that decayed from **-5.047** to **-0.014** rather than flipping sign. Median
time-to-target is **131** plans ≈ **3931 s** at the tape's ~30.4 s cadence: a **~9×** mismatch against
that 425 s reversal, so the target moves away roughly nine times before the desk could reach it. And
grouping the window's fills by the trigger that fired them, `entry — target increase` fires at mean
`|forecast|` **6.288** (n=7) while `reduce toward a smaller target` fires at **2.062** (n=39). **There is
a conviction floor on the way in and none on the way out, and no minimum holding time on either** — so
the desk cannot build the position its sizing logic underwrites, but can dismantle it in a minute. The
receipt: ALPHA `feesPaid` **395.873993** against ALPHA `realizedPnl` **-646.46581774**, and MSFT trading
**$251,134.50** across **228** fills to hold **-4** shares.

**Next unfrozen cycle.** One coherent change: an **exit gate with hysteresis in time** on the fusion
reduce path — the ungated side, where 39 of 47 orders fire — above the deterministic floor, touching
neither the pre-trade guardrail nor the breaker, with its ADR in the same commit. It will be graded on
the flip median rising materially above **425 s** and the reduce-path mean `|forecast|` rising toward the
entry trigger's. Not another fusion weight: ADR-0137/0140/0141 all landed on the INCONCLUSIVE wall
precisely because none of them ever touched the ungated side.

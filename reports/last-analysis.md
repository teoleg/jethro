No code change — the ADR-0116 freeze holds at 4 of 6 — and a fourth clean window finally asks what the desk's aim is MADE OF: 51% of it is mean-reversion, 23.1% from the one source measured negative at every horizon, and 0% from the only source that clears the round-trip cost.

*(Every figure below is read from `logs/report.md`, `reports/run-status.json`, `reports/.pending-baseline.json`,
`git diff` and the scorer's own output, or is arithmetic on those read fields. None is authored here —
invariant 7 / ADR-0016.)*

**Money.** Total PnL **$-793.28**, gross **$10,491.89**, net **$513.02**. Since last run PnL **-0.17**,
gross **+3460.97**; over the last 3 runs PnL **-15.45**, gross **-17514.39**.

**Risk — not a danger state.** Gross is **0.7%** of the firm gross cap $1,500,000 (headroom
**$1,489,508**); net is **0.1%** of the $1,000,000 net cap. `breaker.halted` **false**, `regime` **CALM**,
`riskCuts` **[]**, `bookVolBrake` **1.0**, `portfolioRiskMultiplier` **0.8388330999135437**. The book is
deploying with room to spare; UNDERWATER is the cumulative figure, not a live threat.

**Why no change.** `scripts/score-change.py score` prints `39451ce71 still accumulating evidence (4/6
cycles) — held, not scored this run`, and `reports/.pending-baseline.json` still names that commit. Under
ADR-0116 a new change now would destroy the evidence under measurement. I again deliberately did **not**
run `score-change.py baseline` — it overwrites the pending record unconditionally and would delete
ADR-0142's own evaluation window (Rule 396).

**Step 0 — ADR-0142 ✅ VERIFIED, fourth independent confirmation.** `ops_jvm.uptimeSeconds` **6520** at
`traffic.timestampMillis` **1786033801905** implies a boot instant of **1786027281905**, against
**1786027281760**, **1786027281702** and **1786027281035** on the three prior cycles (sub-second skew
between the two endpoints). Same 14:41:21Z process, four cycles and three reports-only commits later.
`git diff --name-only 24b5183..HEAD` lists `reports/run-status.json` and nothing else. Item stays struck.

**Attribution — 100% market.** Zero Java, zero dials, zero gates in the window's diff, fourth cycle
running. The **-0.17** PnL and **+3460.97** gross moves are the market plus code already live; nothing I
did earns credit or blame for either.

**What this clean window bought — the aim's composition, and it inverts item #1 again.** The last three
cycles measured *how long* the desk holds. This one decomposes each target's `contributions` into
`forecast × weight` and asks what it holds an opinion *about*. Across the six reported names: `trend`
**42.6%** of total |weighted contribution|, `reversion` **27.9%**, `xsreversion` **23.1%**, `momentum`
**6.5%**, `social` **0.0%** (present in **0 of 6**). So **51.0%** of the aim is the mean-reverting pair —
which outvotes trend+social in **4 of the 6** names, and in **2 of 6** `trend` points *against* the
combined aim outright: NEE's **+3.0590** is `xsreversion` **+19.396** plus `reversion` **+11.959** against
`trend` **-2.065**. The per-source weights are already directionally right (trend **1.454**, social
**1.282**, reversion **0.710**, xsreversion **0.507**); they are swamped by raw claim magnitude, because
`forecastScalars` equalises each source's *mean* absolute claim and not its dispersion.

**And 23.1% of the aim is the worst-measured source on the desk.** On cohort-clustered standard errors
(`stdCohortMeanBps`/√`cohorts`), `xsreversion` is negative at **all three** horizons — **-0.171** /
**-2.080** / **-1.816** bps — with hit rate below a coin flip at all three (**0.491** / **0.490** /
**0.477**), and its 900s reading **t = -2.29** on 152 cohorts is the only |t| > 2 among the table's 15
rows. Stated honestly: at 15 tests that does *not* survive Bonferroni (|t| > 2.94). The evidence is the
consistency, not the t-stat — last cycle's independent read was **-0.205** / **-2.123** / **-4.363**, same
sign, same shape. Meanwhile `fee_bps` is **1.00** on every equity, so a round trip costs **2.00 bps**:
`trend` **+1.746** gross is net *negative*, `reversion` **-0.330** is **-2.3** net, `xsreversion` is
**-3.8** net. The only source clearing the round trip is `social` at **+3.595** gross → **+1.6** net — and
it is corroborated on **12** of **1600** kept items, so it contributes **0.0%** to every aim.

**Why this outranks the exit-gate I planned last cycle.** A mean-reversion signal flips on short-horizon
noise by construction, so a majority-mean-reverting aim mechanically *produces* the tape the last three
cycles measured. That tape re-confirmed sharper this window — entry mean `|forecast|` **7.309** (n=4) vs
reduce **1.300** (n=36), 36 of 43 fills on the ungated reduce path; PFE entered at **-11.5747** and began
unwinding **122 s** later at **-0.0817**, a **99.3%** same-sign decay with no flip. Time-gating the exit
would make the desk hold that opinion *longer* while paying 2 bps a round trip. Fix what the aim is made
of first; the churn is downstream. That is not another lap of the INCONCLUSIVE wall — ADR-0137/0140/0141
each tuned mechanics around an aim nobody had opened, and this is removing a measured-loss-making source
from the sizing path, not re-weighting hopefully.

**One ambiguity I am recording rather than acting on.** `insideBuffer` is **19** of **24**, and four of six
reported targets plan `deltaQty` **0.0000** against large gaps (BAC `targetQty` **652.635** vs `currentQty`
**0.00**) — yet the `aims` map reads BAC **10.669227**, ~60× smaller. Two different things are called the
target in one payload, and telemetry alone does not say which the router consumes. The previous cycles'
time-to-target arithmetic assumed `targetQty`; if `aims` is the routed intent, that overstated the
mismatch. Logged as register item #4 with a source-read VERIFY-BY, so it cannot silently propagate.

**Next unfrozen cycle.** One coherent change targeting the new #1: cut `xsreversion`'s grip on the aim so
the composition reflects measured expectancy net of the 2.00 bps round trip — with its ADR in the same
commit, touching neither the pre-trade guardrail nor the breaker. Graded on the same decomposition run
here: `xsreversion`'s contribution share falling materially below **23.1%**, mean-reversion outvoting
trend+social in fewer than **4 of 6** names, and the aim-weighted 3600s expectancy turning positive
against cost.

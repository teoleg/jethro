# Loop analysis — 2026-08-06 19:30Z

**No change: the ADR-0116 freeze holds at 4/6 — and the clean window found the exit trigger. Every full exit since boot fired at `sources=1`; every entry required `sources≥2`. The desk's exit is governed by which sources are AVAILABLE, not by what they SAY. It also invalidates my own VERIFY-BY from last cycle: the since-boot holding period rose 1,141.3 s → 1,610.5 s (×1.411) while uptime rose ×1.386 — the estimator tracks the clock, not the behaviour.**

## Situation triage

**1 — Money.** Total PnL **-$886.54**. Since last run **-13.08**; over the last three runs **-35.14**.
Bleeding slowly and off the owner's +1%/3-iteration target (`run-status.json` reads **-4.06%** against
**1.0%**; `on_track=false`, `stale=true`, `underwater=true`).

**2 — Risk.** Gross **$12,107.12** = **0.8%** of the $1,500,000 firm cap, headroom **$1,487,893**; net
**$2,360.69** = **0.2%** of the $1,000,000 net cap. `breaker.halted` **false**, `regime` **CALM**
(`trend` **CHOP**, `volRatio` **1.00**), `var95` **104.16** / `es95` **152.39** on `coveredExposure`
**12,107.12**, `skippedExposure` **0.00**. Gross **fell -10,711.12** this window — the book gave back
most of last window's deployment, which is the same churn item #1 describes, not a de-risking decision.

**3 — Cause.** Nothing I committed reached the app. `27564bb15` (ADR-0143) is at **4/6** and still not
gradable — no ❌ BAD verdict has occurred against which its claim could be read. `git diff --name-only
c2344ec..HEAD` reaches only `docs/loop-findings.md` and `reports/`, and `ops_jvm.uptimeSeconds` **6463**
against `traffic.timestampMillis` **1786044601386** puts boot at **17:42:18.386Z** — the same process as
last cycle's **17:42:18.047Z**. Fourth consecutive clean window.

**4 — Danger.** No. Underwater, but at 0.8% of the gross cap with the breaker untripped.

**5 — Order post-mortem.** This is the cycle's finding. Censusing every FILLED LIVE order since boot by
origination trigger and source count:

| trigger | sources=1 | sources=2 | sources=3 | sources=4 |
|---|---|---|---|---|
| `fusion entry — target increase` | **0** | 5 | 15 | 5 |
| `fusion reduce toward a smaller target` | **0** | 1 | 56 | 7 |
| `fusion exit — target decayed to flat` | **6** | 0 | 0 | 0 |

**Every full exit fired at `sources=1`. No exit fired with the ensemble intact.** Entry demands
corroboration; exit does not. The clearest instance is BAC: opened **19:08:52Z** by `fusion entry` at
`forecast=-5.9863021173751845, sources=3`, closed **19:10:12Z** by `fusion exit — target decayed to flat`
at `forecast=-0.0, sources=1` — **79 s**, and the most expensive of the window's six completed round trips.

**6 — Memory.** Applied Rules 412, 426–435. Rule 433 bit again this cycle, in a new disguise — see below.

**7 — Change vs market.** **100% market and desk-autonomous.** No committed path can reach the app binary
and the process never bounced, so neither the **-13.08** PnL nor the **-10,711.12** gross belongs to my
work.

## Rule 433 caught my own VERIFY-BY one cycle after I wrote it

Last cycle I set item #1's VERIFY-BY as "the realized holding period must rise toward 3600 s", computed as
time-weighted ALPHA LIVE inventory ÷ half one-way traded notional **since boot**. Recomputed this cycle:
**$87,244,670.86** USD·s ÷ (**$108,347.01** / 2) = **1,610.5 s**, up from **1,141.3 s** — a **×1.411**
improvement, in a window where I changed nothing and the process never restarted. Uptime over the same
interval went **4664 → 6463** s, **×1.386**. The estimator is not stationary: the inventory-time integral
accrues against a growing clock while turnover accrues linearly, and open positions accrue time-to-now
(MCD reads **12,338.1 s** on 4 fills, WMT **10,829.4 s** on 1 — both simply "never traded again"). My own
VERIFY-BY would have graded doing nothing as a 41% win.

The stationary replacement is the same quantity over **fixed-width trailing windows**
(2 × time-average |inventory| ÷ one-way turnover rate), which needs no boot anchor and cannot drift:
**18:00–18:30 → 1,745.2 s**, **18:30–19:00 → 1,332.8 s**, **19:00–19:30 → 2,313.2 s**. So the desk really
does hold ~22–39 minutes, straddling the 900 s and 3600 s telemetry rows — but the window-to-window swing
is **±35%**, wider than any single change would move it, so it needs several windows before it can grade
anything (Rule 412).

## The term structure reproduced, and the trades now agree with it

Weighting `/api/signals/telemetry` by the live `fusion_targets.weights` (sum **5.0**), on a fresh sample
one cycle later: **225 s → -0.2473 bps**, **900 s → -0.4562 bps**, **3600 s → +1.0442 bps**. Same
monotone shape, same signs, same crossing between 900 s and 3600 s. Significance is unchanged and still
nil — computing t on the **cohort means** (the clustered form, `stdCohortMeanBps`/√`cohorts`) the largest
|t| across all 15 cells is `xsreversion` at 900 s, **t = -2.52**, against the Bonferroni hurdle **2.94**.

What is new is that the **realized trades** now say the same thing independently. Six position episodes
have gone open→flat since boot; sorted by lifetime, with cash and fee straight from `fills`:

| lifetime | name | cash | fee | net |
|---|---|---|---|---|
| 3955 s | NVDA | +5.45 | 1.3625 | **+4.0875** |
| 1978 s | MSFT | +1.74 | 0.2979 | **+1.4421** |
| 1493 s | BAC | -0.22 | 0.1259 | **-0.3459** |
| 754 s | AAPL | -1.74 | 0.8738 | **-2.6138** |
| 538 s | MSFT | -0.79 | 0.0994 | **-0.8894** |
| 79 s | BAC | -3.03 | 1.0321 | **-4.0621** |

Spearman **ρ = +0.943** (n=6; two-tailed 5% critical |ρ| = 0.886). Six trades is far too few to carry on
its own — but it is a *second, independent* measurement agreeing with the signal-level term structure, and
that is worth more than either alone. Both say the desk's money is in the long tail of its holding-period
distribution and its losses are in the short one.

## Decision

No code change — `27564bb15` is at 4/6 and a new change would destroy its evidence. The register is
re-ranked: **#1** becomes the **exit/entry corroboration asymmetry** (6/6 exits at `sources=1`, 0/25
entries) — the concrete, fixable mechanism that *produces* the horizon mismatch, with a stationary
trailing-window VERIFY-BY replacing the boot-anchored one I struck. Note ADR-0140 already ships an
"absence ages the intent over one evidence horizon" rule and scored INCONCLUSIVE; the first job next
cycle is to establish whether that aging is actually reached on this path before adding anything to it.
**#2** the venue asymmetry (**82.70%** of **$5,014,071.33** cumulative turnover in the 2.00 bps equity
round trip; `totalFees` **432.017216** = **48.73%** of `firmTotal` **-886.53719294**). **#3** the
`scripts/` restart-gate omission, NOT EXERCISED a third cycle. **#4** the deployment gap, still no action.

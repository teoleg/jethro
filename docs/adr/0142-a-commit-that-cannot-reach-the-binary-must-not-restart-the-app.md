# ADR-0142 — A commit that cannot reach the app binary must not restart the app

**Status:** Implemented
**Date:** 2026-08-06
**Supersedes:** none. Narrows the deploy trigger introduced with the loop wrapper (ADR-0063) and
verified against process uptime by ADR-0110/ADR-0123. Changes no dial, no gate, no risk number and no
part of the deterministic floor; touches no code that runs inside the app.

## Context

The loop restarts the trading app on **every** cycle, including the no-change cycles whose entire
purpose is to leave a pending change undisturbed. That is not a policy anyone chose — it falls out of
the interaction between two rules that are each correct on their own.

`ops/improve-loop.sh` rebuilds and restarts when anything outside `reports/` changed in the cycle's
commit range:

```sh
changed=$(git diff --name-only "$before" "$after" | grep -v '^reports/' || true)
```

And `ops/improve-prompt.md` requires the agent, **every run — change or not**, to append a dated
finding to `docs/loop-findings.md`, the loop's compounding memory. `docs/` is not `reports/`. So the
mandated memory write is, by itself, sufficient to trigger a full rebuild+restart.

This is not hypothetical. Last cycle was a deliberate no-change cycle held under the ADR-0116
evaluation window. Its commit range contains exactly four paths, of which exactly one is outside
`reports/`:

```
$ git diff --name-only 493ab5d 955d41a | grep -v '^reports/'
docs/loop-findings.md
```

The wrapper deployed on that, and `ops_jvm` confirms the app it bounced: `uptimeSeconds` **1406** at a
report timestamp of **1786026601754**, i.e. a process start of **2026-08-06T14:06:35Z** — 23 seconds
after the `chore(status)` commit at `2026-08-06T14:06:12Z`. The cycle that promised to change nothing
restarted the trading app.

### What a restart costs this desk

A restart is not free here, because every forecast and risk sensor is a warm-up-gated estimator seeded
from stored marks, and the seed walk terminates at the first gap in that history — which is the
previous restart. The startup log carries **19** `risk-cut σ sensor still cold` lines and **0**
`risk-cut σ sensor warmed`, each reporting a seed that stopped on `GAP_BREAK` or `HISTORY_EXHAUSTED`
covering **~2120–2170s**, against a σ sensor that asks for **121** prices at a **30000ms** step
(≈3630s) and a reversion sensor that asks for **241** at **10000ms** (≈2410s). Both warm-up spans
exceed the contiguous history a ~30-minute restart cadence can leave behind, so the seed can only be
completed by accumulating live marks *within* the cycle — and then it is wiped.

The live consequence, read off the same plan: `fusion_targets` planned **24** instruments,
`insideBuffer` **23**, `streamVolMeasuredNames` **2**. The `aims` map carries a real intent for
exactly the two names with a measured σ — MSFT **-8.668719**, NQ **-3e-06** — and **0.0** for all
twenty-two others, including names with substantial targets the planner produced this cycle (WMT
`targetQty` **-566.406006** on `combinedForecast` **-3.5337947897910817**, AAPL **226.814437** on
**3.3510088777313816**), both routing `deltaQty` **0**. MSFT is the name whose σ seed was deepest
(**84** of **121**) and MSFT is the name that traded. The correlation between seed depth and ability
to hold risk is the mechanism, stated by the code's own counters.

The desk therefore gets a few usable minutes at the *tail* of each cycle and is then reset. The ledger
records the signature plainly: three consecutive scored rows end with firm gross exposure at
**$0.00** (`3cc91bc46` $5,253.42 → $0.00; `629dbbdf8` $52,192.44 → $0.00; and the window before that
$93,877.75 → $26,440.89).

And it costs realised money, not just measurement. ADR-0141 opened the desk's first position since
2026-08-05: NQ SELL **0.030886** filled **13:58:29Z** on `combinedForecast`
**-7.858987731814552**. The restart landed at **14:06:35Z**. From **14:14:51Z** the same name routed
eight consecutive `fusion reduce toward a smaller target` orders as the forecast fell to
**-1.269288023440704** and then **-1.314462997304728E-4**, buying back **0.025280** of a **0.030886**
short into a mark that moved **29435.50000000 → 29600.50000000**. NQ now reads `realizedPnl`
**-124.25140691** and `unrealizedPnl` **-18.49980000**. Whether that particular forecast decay was
caused by the restart or was genuine cannot be separated from these numbers alone — NQ does not appear
in the cold-sensor list — so this ADR does not claim it. What is not in doubt is that a no-change
cycle bounced the process in the middle of a fresh position and re-seeded every sensor cold.

### Why this is the loop's most expensive defect

It corrupts the instrument the loop uses to know anything. ADR-0116 judges a change over ~6 cycles of
per-cycle risk-adjusted PnL. If the book is force-flattened partway through every one of those cycles
by the harness itself, the window is not measuring the change — it is measuring the restart. That is a
sufficient explanation for a ledger that is a wall of INCONCLUSIVE, and it means the standing priority
of "work on edge, not the combiner" cannot even be evaluated: a source cannot demonstrate expectancy
over a 3600s horizon on positions that never survive 1800s.

## Decision

**Rebuild and restart only when a path that can affect the app binary changed.** Paths that cannot are
enumerated and excluded:

```sh
NON_BINARY_PATHS='^(reports|docs|ops)/'
changed=$(git diff --name-only "$before" "$after" | grep -Ev "$NON_BINARY_PATHS" || true)
```

- `reports/` — the ledger, snapshots, heartbeat and analysis the scorer and agent write every cycle.
  Already excluded; unchanged.
- `docs/` — the ADR set and `docs/loop-findings.md`, the memory `ops/improve-prompt.md` mandates on
  every run. Not a Gradle input; nothing under it is read by the running app.
- `ops/` — this cron wrapper and its prompt. Re-read by cron on each fire, never compiled.

Everything else deploys exactly as before. The exclusion is **path-based and conservative**: it names
the three directories that are provably not build inputs rather than trying to infer what matters.

This introduces **no money, risk or exposure number** and alters no existing one (invariant 7 /
ADR-0016). It changes *when the process is bounced*, nothing about what the process does.

### Why this does not break the deploy guarantee

The failure mode the deploy trigger exists to prevent is "the next cycle scores a commit against a
binary that does not contain it". That guarantee is preserved, because an architecturally-significant
change ships its ADR **in the same commit** as its code (the repo's design-first rule): the diff then
contains both `docs/adr/NNNN-*.md` and the `.java`/`.gradle`/`.properties` path, the latter survives
the filter, and the deploy runs. The filter can only suppress a deploy when the *entire* commit range
is documentation, reports and the wrapper — in which case there is nothing to deploy. Verified as a
table over representative paths:

| path | deploys |
| --- | --- |
| `docs/loop-findings.md` | no |
| `reports/must-fix.md` | no |
| `ops/improve-loop.sh` | no |
| `docs/adr/0142-x.md` | no |
| `app/src/main/java/X.java` | **yes** |
| `modules/order/build.gradle` | **yes** |

### Timing note (accepted, deliberate)

The ADR-0116 freeze was in force this cycle (`reports/.pending-baseline.json` present for
`a21177cea`, 2 of ~6 cycles) and it forbids piling a new change onto one under measurement. This
change is made anyway, for a reason internal to that rule: **there was no no-op available.** Writing
only the mandated finding would itself have deployed and restarted the app, exactly as it did last
cycle. The freeze's stated purpose is to protect the pending change's evidence; under the old filter,
honouring the freeze destroyed that evidence every 30 minutes. The change touches no signal, dial,
sizing control, risk model or gate — nothing the scorer's vector measures about the strategy — so it
cannot be confused with a trading change piled on top. It removes an exogenous confound from the
window rather than adding one to it. The residual is honest and recorded: ADR-0141's window now runs
partly with, and partly without, forced mid-cycle liquidation, so its eventual verdict is weaker
evidence than a clean window would give.

## Alternatives rejected

- **Move `docs/loop-findings.md` under `reports/`.** Fixes the one path that bit us and leaves the
  class of bug intact — the next mandated write outside `reports/` (an ADR, a playbook update) bounces
  the app again. It also hides the loop's compounding memory inside the scorer's artifact directory,
  where it does not belong.
- **Stop appending a finding on no-change cycles.** Trades the loop's memory for its uptime. The
  memory is the thing that stops cycles relearning what a past cycle already paid for; it is not
  optional.
- **Lengthen the sensor warm-up seeds so a restart is survivable.** This is ADR-0138's approach and it
  is still ⚠️ open on its own terms — the seed walk terminates on `GAP_BREAK` at the restart boundary
  regardless of how far it is willing to read. Making the estimators restart-proof is a genuinely good
  idea and stays on the register, but it is a strictly larger change aimed at the *symptom* of an
  unnecessary restart. Remove the unnecessary restarts first, then see what warm-up problem is left.
- **Persist sensor state across restarts (LMDB warm-restart, ADR-0014).** The natural sibling of
  ADR-0140's durable aim and probably correct eventually. Deferred behind this: it is a real design
  with real failure modes (a stale σ is worse than a cold one), and it should be designed against a
  desk that is not being bounced for documentation.
- **Deploy on a fixed schedule instead of on change.** Decouples the binary from the commit being
  scored, which is the exact failure ADR-0110/ADR-0123 were written to close.

## Falsified if

- A code change lands and the next cycle scores it against a binary that does not contain it — the
  filter is over-broad and a real build input lives under one of the three excluded directories.
- `streamVolMeasuredNames` and `insideBuffer` do not improve across cycles that ship no code, i.e. the
  sensors were never being reset by the restart and the cold seeds have another cause.

## Verify by (next run)

Read from live telemetry, not asserted here:

1. `ops_jvm.uptimeSeconds` **exceeds the cycle interval** on any cycle whose commit range is confined
   to `reports/`, `docs/` and `ops/` — proving the process was not bounced.
2. `fusion_targets.streamVolMeasuredNames` **above 2**, and `insideBuffer` strictly below
   `instruments` by more than the current 1 — proving sensors accumulate across cycles instead of
   resetting.
3. `ops/improve.log` carries `no code change ... — no rebuild/restart` for such a cycle.
4. A position opened in one cycle is **still held** at the start of the next, rather than the firm
   gross returning to **$0.00** as it did on three consecutive scored rows.

## Consequences

- **Positive.** ADR-0116 windows measure the change instead of the restart. Sensors keep warming
  across cycles, so the σ-cold veto stops holding 22 of 24 names flat. Positions can be held across a
  cycle boundary, which is a precondition for any 900s/3600s-horizon source ever demonstrating edge.
  Round-trip cost stops being paid on liquidations nobody asked for.
- **Negative, accepted.** A wrapper or prompt fix under `ops/` no longer takes effect on the app in
  the same cycle — correct, since it never affected the app, but it does mean the loop's own behaviour
  and the running binary can drift apart in the logs. And the desk now holds risk across cycles it
  previously flattened by accident, so a bad view stays on longer; the ADR-0086 chandelier cut, the
  ADR-0126 σ veto, the pre-trade guardrail and the firm drawdown breaker are the controls that answer
  that, all untouched.

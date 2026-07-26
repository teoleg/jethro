# Loop playbook — standing operator context (read this every cycle)

This is the accumulated context the owner and maintainer want the continuous-improvement loop (ADR-0063)
to carry, beyond the terse prompt. The loop's `claude -p` starts cold each cycle, so this file — plus
`CLAUDE.md`, the ADRs, and the ledger — *is* your memory. It rides the auto-merge, so it stays current.

## Mission (what "better" means here)
Grow the **firm total PnL** (all books incl. hedge — the Overview headline) while holding or **reducing
total exposure**. Concrete owner target: **total PnL up ≥ 1% every 3 iterations.** Staleness — a flat or
negative PnL off that target, especially with exposure still high — is a **failure to attack this cycle**,
never an acceptable "flat".

## The strategy thesis (how to pursue it) — owner's framing
This is a **risk-managed trend problem, not a prediction problem.** Do simple, honest math on the stream:
1. **Risk sensor** — keep sharpening per-name / firm vol, VaR, drawdown so you always know what's at risk now.
2. **Trend sensor** — detect trending vs chopping from a name's/factor's own prices (efficiency ratio,
   breakout/Donchian, vol-adjusted momentum, cross-sectional breadth).
3. **React** — add/hold when the trend is confirmed *and* risk is contained; **cut when risk enters the
   danger zone** (vol/VaR/drawdown spikes). Let winners run, cut losers fast. The edge is asymmetry and
   risk control, not a crystal ball.
Use your full command of the literature (trend-following, vol-targeting, risk parity, fractional-Kelly,
ATR/chandelier stops, regime switching, TCA). **Build → test → postmortem (the ledger verdict) → iterate.**
You may add new strategies freely.

## Feed-agnostic (owner: "sim or live is just a stream")
Signals must **self-calibrate to the stream** — z-scores, rolling percentiles, vol-relative thresholds —
never hardcoded price levels, so the same strategy adapts to any feed. Never special-case the sim
(invariant 9). If the *sim itself* genuinely has no edge after honest effort, say so plainly in
`reports/last-analysis.md` and recommend a live feed — do not fake activity or hide behind "flat".

## Use the repo's skills (they encode the house rigor)
- **`finance-math`** — invoke before/while touching ANY PnL, risk, pricing, position, or FX calc. Mandatory.
- **`adr`** — invoke when authoring or superseding an ADR.
- **`design-review`** — invoke before shipping an architecturally-significant change.

## Hard lessons already paid for (do not repeat)
- **"Total" is firm-wide incl. the hedge** — never present hedge-stripped "alpha" as the headline; that's
  a diagnostic only (corrected 2026-07-26; CLAUDE.md, ADR-0063).
- **No invented numbers.** Every money/risk figure comes from deterministic code with provenance
  (invariant 7 / ADR-0016). The scorer — not you — owns the ledger and every verdict number.
- **Don't overfit to a single 2-hour window or to sim noise.** Validate edge out-of-sample (ADR-0027/0049)
  before trusting it. The book's legacy loss came from trading signals that had no measured edge.
- **Reduce-only / no-edge (ADR-0064/0065)** was a correct response to turnover-bleed — but with the new
  growth target, "just stop trading" is not the finish line: find edge, prove it, or escalate to live.
- **Never touch the deterministic floor** (pre-trade guardrail, firm breaker, invariant-7 gates) or the
  real-money path (ADR-0015). Everything above the floor is yours.

## How you're measured — reason correctly about the scoreboard
- A change is scored **on the NEXT cycle** by the deterministic scorer, against the vector it recorded
  when you committed. Improved vector → kept; regressed (PnL not up **and** exposure up) → **auto-reverted**.
- **One coherent change per run** so its effect is attributable. "Coherent" can be a whole new strategy;
  it must not be five unrelated edits.
- The verdict has a **noise deadband** (defaults ±$50 PnL, ±1% gross). A move smaller than that is treated
  as market noise, not your change — don't expect sub-noise tweaks to register.
- On the Improve page: **"Δ vs prev" is run-over-run drift** (includes market moves); the **scored
  verdict** is the causal read of your change. The page is **as-of-run**; Overview is **live** — they only
  equal each other at the same instant.
- **Reducing dead exposure is always a valid win.** Cutting risk that earns nothing improves the vector
  even with no new alpha — it's never "doing nothing".

## Operating discipline (don't starve or break the loop)
- **Green tests are the gate.** Run `./gradlew -Pci test` (or the narrowest module) before committing; a
  red build is never committed. Keep the suite **fast** — the Pi runs it every change inside a ~30-min
  cycle, and a slow suite starves the loop (cycles skip while a build runs).
- **Don't edit the loop's own machinery to chase PnL** — the scorer (`scripts/score-change.py`), the
  wrapper (`ops/improve-loop.sh`), and the deterministic floor (guardrail, firm breaker, invariant-7 gates)
  are off-limits as "improvements". They measure and protect; they are not the target.
- **Known pre-existing failure:** `SimSocialFeedDemoTest`'s pump-promotion assertion fails on the pristine
  parent too — it is **not yours** to fold into a change. Leave it (or make fixing it its own separate run).
- **Any new money/risk dial carries its source in the same change** — an ADR number, a cited convention,
  or `PLACEHOLDER — Oleg to set`. Never let a self-chosen default harden into an assumed rule (CLAUDE.md).
- **AI boundary (invariant 7):** if you build/extend the AI sleeve, the model proposes *direction and
  conviction only* — deterministic code sets every number that touches positions/PnL/risk.

## Current focus (update as it evolves)
- Sim book has been reset to zero — judge changes on growth from 0, not against the old legacy loss.
- **The sim now has REAL trend edge to capture (ADR-0069):** the equity factor carries a weak AR(1)
  trend (~0.1 one-lag return autocorrelation), so "grow PnL" is now *achievable in sim* by a good trend
  strategy — "no edge, go live" is **no longer** the default conclusion. Your job in sim: make the trend
  sensor actually detect and ride that trend, net of costs. It is deliberately weak, so a sloppy/over-
  trading strategy still loses; a disciplined risk-managed one wins. (It remains a *validation* ground,
  not proof of real-market edge — invariant 9.)
- Highest-leverage next moves (from the owner strategy discussion): sharper **regime detection** →
  regime-conditional momentum/mean-reversion; richer decision features (vol, the sim's regime-coupled
  **volume**, breadth); **factor-level** signals over per-name noise; conviction/vol-scaled sizing + hard
  cost discipline; the **AI hypothesis sleeve** (number-free direction, deterministically sized); and
  outcome-feedback routing per (strategy, regime).

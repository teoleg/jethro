# ADR-0060: Dynamic discovery-driven universe — daily promotion, provisional refdata, monitor-only probation

- **Status:** Proposed
- **Date:** 2026-07-24
- **Deciders:** Oleg
- **Tags:** refdata, discovery, universe, data, risk

## Context

The tracked universe is **static**: a hardcoded `jethro.trading.sim-instruments` list (17 names) plus refdata
seeded by Flyway migrations, under the rule (CLAUDE.md) that adding an instrument REQUIRES its attribute
rows — `display_name`, `adv_usd`, `spread_bps`, feed symbology, identifiers — in the same change. It is
narrow and misses names the market is actively discussing. The discovery layer (ADR-0045/0050) already
ranks UNTRACKED names by corroborated news + social evidence (`UniverseCandidate`: score, mentions,
`sources`, first/last-seen span), but it is **advisory-only** — nothing promotes a candidate into the
monitored set. Forces: growth must be **conservative** (a bad auto-add shows blank / phantom-flat — the
rates/SAP bug — or worse, trades an unvetted name); a NEW name has **no migration**, so attributes must be
sourced at runtime; `adv`/`spread` gate cost and impact, so they cannot be silently defaulted (money-dial
rule); and the set must stay **bounded** (a Pi + the Yahoo poll budget cannot monitor thousands). Doing
nothing keeps the platform blind to emerging names.

## Decision

A **daily universe controller** promotes discovery candidates into a **bounded, monitor-only** tracked set,
backed by a **runtime refdata write path**:

1. **Conservative promotion gate.** Once per session-day, promote a candidate only if it clears ALL of:
   score ≥ threshold; **sustained** (mentions span ≥ N distinct days); **corroborated** (≥ M distinct
   credible `sources`); and **feed-coverage confirmed** — a configured provider can actually mark it (never
   admit an unmarkable name). Rate-limited to **K promotions/day**. All thresholds start conservative
   (`PLACEHOLDER — Oleg`).
2. **Runtime refdata path.** On promotion, fetch `display_name` + a confirmed feed symbol from the provider
   (Alpaca assets / Finnhub profile); write **provisional** `adv`/`spread` flagged
   `PROVISIONAL — liquidity-tier default, not measured`; stamp `source=discovered` + the evidence. The
   migration-seeded **core** is pinned and never overwritten or evicted.
3. **Monitor-only probation.** A discovered name flows into marks / indicators / signals but **cannot
   trade** until (a) its ADV is **measured from our own tape** and (b) it clears the OOS gate
   (ADR-0049/0059). Growth never bleeds risk.
4. **Bounded + accumulative.** A hard cap `max-monitored`; when full, evict the **stalest** discovered name
   (least-recent mention, no open position) to admit a stronger one — accumulates, can't balloon.
5. **Reversible + audited.** Every promotion/eviction is a row (who/when/why/evidence); a **pin-list**
   protects names, a **blacklist** bans them.

## Alternatives considered

- **Auto-promote top-N by score, trade immediately.** Rejected: trades unvetted names on thin evidence —
  exactly the phantom-flat / cost-churn bug class we just fixed. Conservatism (probation) is the point.
- **Stay advisory-only (a human promotes).** Rejected as the goal: the ask is *programmatic daily*
  re-evaluation; manual promotion doesn't scale and isn't "dynamic".
- **Unbounded accumulation.** Rejected: the box + mark/poll budget can't monitor thousands; a cap +
  eviction keeps it cheap and conservative. Trigger to raise the cap: a bigger box / paid feed.
- **Buy full attributes (ADV/identifiers) from a paid reference vendor.** Deferred: free provider endpoints
  give name + symbol + tradability; measured ADV from our own tape is the honest source for the money dials.
  Trigger: a paid refdata feed lands.

## Consequences

- **Positive:** the platform tracks what the market is actually discussing, not a frozen list; growth is
  bounded, conservative, audited, and can neither trade nor even admit an unvetted / unmarkable name;
  refdata stops being migration-only.
- **Negative:** the runtime refdata write path is new surface — every refdata *reader* must honour the
  `provisional` flag / `discovered` provenance or a discovered name reads as authoritative when it isn't;
  provisional `adv`/`spread` mean a discovered name's cost/impact is a guess until measured (mitigated: no
  trading until measured); the gate thresholds are money-adjacent tuning; eviction can thrash if the cap is
  too tight or the discovery signal noisy.
- **Follow-ups:** the measured-ADV → tradable promotion step; a UI to review / pin / blacklist candidates;
  per-mode universe if sim and live should track different sets (ADR-0029). Supersedes nothing; extends the
  ADR-0050 §7 discovery register from advisory to actor (bounded).

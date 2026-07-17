# Deferred-work register

Every deliberately deferred item lives here with its trigger, so nothing depends on a
code comment or somebody's memory to resurface (deferred items must never get silently
lost). Decision-level deferrals made inside ADRs (managed Kafka, per-module extraction,
C++ hot paths, licensed feeds, …) stay governed by their ADRs' explicit triggers and are
not duplicated here — this register is for **implementation-level** items noted in code
or docs.

Rules:

- Adding a "deferred / tracked / not faked / revisit" note in code REQUIRES a row here in
  the same PR.
- When an item ships, delete its row in the shipping PR (git history is the archive).
- Rows carry the source reference so the note and the register can't drift apart.

| Item | Why deferred / trigger to build | Source reference |
|---|---|---|
| Real corporate-action data source (splits, dividends, symbol changes) | The MarkCache jump guard quarantines large jumps but cannot detect ordinary dividends (~0.5–2%) or distinguish a split from a crash without an authoritative feed; needs a data-source decision (likely its own ADR) | `MarkCache` javadoc; ADR-0024 impl note |
| Configurable per-book base currency (non-USD reporting) | Rollups convert to USD at the live FX mark today; a non-USD-based book needs a base-currency attribute and report-currency plumbing. Trigger: first book that reports in EUR/GBP | `RiskProjection` javadoc |
| FX cross-pair conversion (non-USD crosses, e.g. EURGBP) | `FxConversion` builds its matrix from spot-vs-USD marks only; crosses would triangulate through USD. Trigger: first non-USD-quoted instrument or cross pair in refdata | `FxConversion.java` comment |
| Ring-buffer overwrite-oldest drop policy | Drop-newest + MarkCache conflation preserves freshness today; overwrite-oldest only pays off if consumer stalls appear. Trigger: measured drops with a healthy consumer (perf guardrails + `droppedCount` metric make this observable) | `TickRingBuffer` javadoc |
| Historical replay of hypothesis theses | Theses are validated by the sim-tape OOS backtest (advisory) + live track record (the gate); replaying a thesis against real daily history (walk-forward style) needs thesis→rule translation. Trigger: enough `hypothesis_record` history to make replay meaningful | `HypothesisEvaluator` javadoc |
| Full CTD basket / conversion-factor model from a real deliverable universe | Bond-future DV01 uses CME deliverable-window duration + 6% CF pivot today; the full model needs a real bond universe (issues, coupons, CFs). Trigger: rates PnL attribution noticeably off vs the window model, or a real rates feed | ADR-0020 impl notes |
| Pi soak test for Postgres/Redpanda round-trip budgets | Unit-scale perf guardrails deliberately exclude environment-bound round trips. Trigger: a measured latency complaint on the Pi, or before any real-money broker work | `docs/architecture/perf-budgets.md` |
| CI schema-registration + BACKWARD-compat gate | ADR-0030 registers schemas at runtime (register-on-encode) and sets BACKWARD best-effort; a CI step should register every `common-messaging` schema against a throwaway registry and FAIL the build on an incompatible edit, so a breaking change is caught pre-merge, not at first publish. Trigger: next schema edit, or CI hardening pass | ADR-0030 follow-ups |

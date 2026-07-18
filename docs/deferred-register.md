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
| Hedging: DV01-neutral rates axis + FX axis | The advisor is EQUITY-axis only (ADR-0038 §2/§3 unbuilt): DV01-neutral rates hedge onto ZT/ZF/ZN/ZB via the CTD contract DV01, and a direct FX-pair hedge on the book's net foreign value. Trigger: a book carries rates (bucket DV01) or non-USD exposure worth hedging | ADR-0038; `HedgeAdvisor` (EQUITY only) |
| Hedging: AUTO order submission + firm-breaker one-shot de-risk | Mode AUTO is modelled but does not yet submit hedge orders; the ADR-0039 breaker de-risk (flatten-all-axes-then-freeze, skipping stale/quarantined proxies) is unbuilt. Needs idempotent hedge keys through the order path + the sim gate/envelope. Trigger: sign-off to let the advisor act, not just advise | ADR-0039 §3/§4; `HedgeAdvisor.Mode.AUTO` note |
| Hedging: per-book (vs firm-level) exposure + caps | v1 aggregates equity exposure firm-wide; ADR-0039 targets per-book modes/caps so each desk hedges its own book. Trigger: two books with materially different hedging needs | ADR-0039; `HedgeAdvisor` javadoc |
| Real corporate-action data source (splits, dividends, symbol changes) | The MarkCache jump guard quarantines large jumps but cannot detect ordinary dividends (~0.5–2%) or distinguish a split from a crash without an authoritative feed; needs a data-source decision (likely its own ADR) | `MarkCache` javadoc; ADR-0024 impl note |
| Configurable per-book base currency (non-USD reporting) | Rollups convert to USD at the live FX mark today; a non-USD-based book needs a base-currency attribute and report-currency plumbing. Trigger: first book that reports in EUR/GBP | `RiskProjection` javadoc |
| FX cross-pair conversion (non-USD crosses, e.g. EURGBP) | `FxConversion` builds its matrix from spot-vs-USD marks only; crosses would triangulate through USD. Trigger: first non-USD-quoted instrument or cross pair in refdata | `FxConversion.java` comment |
| Ring-buffer overwrite-oldest drop policy | Drop-newest + MarkCache conflation preserves freshness today; overwrite-oldest only pays off if consumer stalls appear. Trigger: measured drops with a healthy consumer (perf guardrails + `droppedCount` metric make this observable) | `TickRingBuffer` javadoc |
| Historical replay of hypothesis theses | Theses are validated by the sim-tape OOS backtest (advisory) + live track record (the gate); replaying a thesis against real daily history (walk-forward style) needs thesis→rule translation. Trigger: enough `hypothesis_record` history to make replay meaningful | `HypothesisEvaluator` javadoc |
| Full CTD basket / conversion-factor model from a real deliverable universe | Bond-future DV01 uses CME deliverable-window duration + 6% CF pivot today; the full model needs a real bond universe (issues, coupons, CFs). Trigger: rates PnL attribution noticeably off vs the window model, or a real rates feed | ADR-0020 impl notes |
| Pi soak test for Postgres/Redpanda round-trip budgets | Unit-scale perf guardrails deliberately exclude environment-bound round trips. Trigger: a measured latency complaint on the Pi, or before any real-money broker work | `docs/architecture/perf-budgets.md` |
| CI schema-registration + BACKWARD-compat gate | ADR-0030 registers schemas at runtime (register-on-encode) and sets BACKWARD best-effort; a CI step should register every `common-messaging` schema against a throwaway registry and FAIL the build on an incompatible edit, so a breaking change is caught pre-merge, not at first publish. Trigger: next schema edit, or CI hardening pass | ADR-0030 follow-ups |
| Sim-panel: auth gate + panel-driven flag on events | ADR-0031 `/api/sim/*` is sim-gated (`feedMode==SIM`) but unauthenticated like the rest of the `:8080` edge; and a panel-driven session is labelled UI-only (`SimControl.anyDialActive()`), not stamped onto emitted events. Trigger: auth on `/api/admin/*` lands (share it), or a need to tell panel-driven marks from organic sim in the durable log | ADR-0031 follow-ups; `SimControlController` |
| Unrealized FX decomposition (price vs FX attribution on open foreign positions) | ADR-0037 locks *realized* FX at booking and breaks out realized translation, but *unrealized* on an open foreign position still moves with both the local price and spot, undecomposed. Trigger: a P&L-explain / attribution need on open foreign risk | ADR-0037 follow-ups; `RiskProjection.Acc` |
| `comprehensivePnl` / `fxTranslationPnl` on the risk-snapshot Avro event | ADR-0037 splits clean vs comprehensive in-process; the published `book-risk` event still carries only realized/unrealized. Additive fields (invariant 4) would let downstream consumers see the split. Trigger: first consumer that needs comprehensive P&L off the topic | ADR-0037 follow-ups; `RiskSnapshotPublisher` |
| Real per-currency cash ledger (foreign cash as a revaluing position) | ADR-0037's locked-realized + translation line is the reporting equivalent of a real FX/treasury cash account; a true ledger (cash accounts, sweeps, funding) is the full-fat answer. Trigger: real multi-currency cash management / funding | ADR-0037 alternatives |

# ADR-0011: Data quality, coverage metrics, and quarantine

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** data-quality, coverage, validation, quarantine

## Context

For a data-first phase (ADR-0002), success is measured in **coverage and cleanliness**, so those must be
observable, and bad data must be contained rather than allowed to pollute the canonical store. This is the
muni-world analogue of jethro's mark-quarantine + "never silently drop" discipline.

## Decision

- **Coverage metrics, per state / county / tier.** Track issuers, securities, documents and financials
  captured **vs an expected denominator** — the Census Government Units master gives the count of
  governments per jurisdiction, so we can report "NY townships: 900 of ~933 with a financial record" rather
  than a bare row count. Coverage is the headline KPI of the phase.
- **Validation rules on ingest/normalise.** CUSIP checksum; FIPS validity; date sanity (maturity > dated
  date; fiscal year in range); `NUMERIC` bounds; required-field presence. A row failing validation is
  **quarantined**, counted, and surfaced — never dropped, never force-fit.
- **Cross-source reconciliation.** When sources disagree on an attribute, the higher **tier** (ADR-0003)
  wins for the canonical value, but **all values are retained** with provenance (ADR-0005). A large
  disagreement raises a **discrepancy flag** for review rather than a silent overwrite.
- **Quarantine + dead-letter are first-class.** Parse failures (ADR-0004), low-confidence extractions
  (ADR-0010) and validation rejects land in quarantine tables with their raw-artifact link and reason;
  persistent fetch/parse failures dead-letter (ADR-0009). Nothing is lost; everything is countable.
- **Metrics surface.** Counts (fetched / parsed / normalised / quarantined / dead-lettered), coverage by
  jurisdiction, and freshness (age since last successful refresh per source) are exposed via
  `/api/muni/*` for the muni-world UI and for later alerting — the same "count, log, expose a metric"
  rule jethro uses for dropped ticks.

## Consequences

- The build has an honest, queryable scorecard: what we have, against what exists, and what's stuck.
- Bad data is visible and recoverable, not silently absorbed or discarded — quality can only improve.
- Coverage-vs-denominator framing keeps the NY/NJ/PA rollout goal concrete and prevents "we scraped a lot"
  from masquerading as "we have the universe."

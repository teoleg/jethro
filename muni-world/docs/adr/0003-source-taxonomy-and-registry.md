# ADR-0003: Source taxonomy and the source registry

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** data-sources, ingestion, registry

## Context

"All possible data about munis" spans wildly different publishers, formats, cadences and access terms. To
collect it systematically (and legally — ADR-0008) we need a **catalogue of sources** and a shared
vocabulary, not ad-hoc scrapers. The catalogue must drive scheduling, provenance and coverage metrics.

## Decision

Adopt a **source taxonomy** and persist every source as a row in a **source registry** (`muni.source`)
that the ingestion layer (ADR-0004) reads. Nothing is scraped that isn't registered first.

### Taxonomy (tiers, by authority)

1. **Federal / market-wide (primary).**
   - **MSRB EMMA** (`emma.msrb.org`) — the official repository: official statements, continuing-disclosure
     and financial/operating filings, advance-refunding docs, and **RTRS trade data**. The single most
     important source.
   - **U.S. Census Bureau** — Government Units master, **FIPS** geography codes, Annual Survey of State &
     Local Government Finances (cross-source reference + normalisation spine — ADR-0007).
   - **IRS / SEC / Treasury (SLGS)** — tax-exempt status, enforcement, subsidy programs.
2. **State disclosure hubs (Phase-1 backbone).**
   - **NY** — Office of the State Comptroller (Open Book NY, Local Government financial data); Authorities
     Budget Office (public authorities).
   - **NJ** — Dept. of Community Affairs, Division of Local Government Services (Annual Financial Statement
     / AUD, User-Friendly Budgets).
   - **PA** — Dept. of Community & Economic Development, Municipal Statistics (Annual Financial Report).
   - State open-data portals (`data.ny.gov`, `data.nj.gov`, PA equivalents) and treasurer/bond pages.
3. **Local governments.** Counties, cities, **townships**, school districts and **authorities** (water,
   sewer, housing, turnpike, dormitory/education) — their own sites for budgets, ACFR/CAFR PDFs, official
   statements, board minutes and bond resolutions.
4. **Institutions & intermediaries.** Rating agencies (Moody's, S&P, Fitch, KBRA — public rating actions
   only; full data is licensed), municipal advisors, underwriters, bond counsel, and civic/academic
   datasets. Treated as **corroboration**, licensing-gated (ADR-0008).

### Registry shape (per source)

`name`, `publisher`, `tier`, `jurisdiction` (FIPS/state), `base_url`, `access_method`
(`api` | `bulk_file` | `scrape` | `manual`), `format` (`json`|`csv`|`html`|`pdf`|`xbrl`), `cadence`,
`robots_ok`/`terms_url`/`license`, `enabled`, `last_fetched_at`, `notes`. Sources are versioned — a moved
URL supersedes, it does not overwrite (provenance must stay truthful).

## Consequences

- Ingestion, scheduling (ADR-0009) and coverage metrics (ADR-0011) all key off one catalogue, so adding a
  county is a data row, not new code.
- The tiering makes the **primary vs corroborating** distinction explicit, which matters for entity
  resolution (trust EMMA/Census over a scraped township page) and for licensing (tier-4 is gated).
- The registry is the audit trail of *where every datum could have come from* — the backbone of provenance.

# ADR-0007: Identifiers and entity resolution

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** identity, entity-resolution, keys

## Context

The same issuer, obligor and security appear across many sources under different names and shapes ("Town of
Hempstead" vs "HEMPSTEAD (TWN OF) NY"). Without disciplined identity, the warehouse fills with duplicates
and analytics joins break. We need canonical keys and a resolution policy.

## Decision

**Deterministic identifiers first, fuzzy matching only as a flagged fallback.**

- **Security = CUSIP-9.** The market's key; its embedded checksum is validated on ingest (ADR-0011).
- **Issuer = CUSIP-6** (the base) as the anchor, cross-referenced to sources. Where a scraped local entity
  has no CUSIP yet, it gets a provisional internal id and is reconciled to a CUSIP-6 when a security links
  it.
- **Geography = FIPS.** State (2) / county (3) / place (5) / school-district codes from the Census
  Government Units master are the normalisation spine; issuers link to geography by FIPS, not by name.
- **Obligor** is resolved separately from issuer (conduit deals) and linked per issue.
- **Crosswalk table** (`muni.entity_xref`): every canonical entity ↔ its identifiers on each source
  (source_id + source_native_id + name-as-seen). This is how "OSC entity 12345" and "EMMA issuer ABC123"
  become one issuer, with the evidence retained.

**Resolution pipeline:** (1) exact key match (CUSIP/FIPS) → canonical; (2) deterministic normalised-name +
geography match within a source's jurisdiction; (3) fuzzy match → **confidence score + human-review queue**,
never auto-merged above the store silently. Merges are reversible and audited (they carry provenance like
any other fact). When sources conflict on an attribute, the higher-tier source (ADR-0003) wins for the
canonical value while both are retained (ADR-0005/0011).

**CUSIP is licensed IP.** CUSIP numbers are used as internal keys but are **not redistributed** as a
masterfile; see ADR-0008 for the licensing boundary.

## Consequences

- Deterministic keys keep the vast majority of resolution exact and cheap; fuzzy matching is contained and
  auditable, never a silent guess.
- The crosswalk makes multi-source corroboration and coverage metrics (ADR-0011) possible and keeps every
  merge explainable and reversible.
- Building the identity spine early prevents the duplicate explosion that kills muni datasets late.

# muni-world — Architecture Decision Records

muni-world keeps its **own** ADR flow, separate from jethro's `docs/adr/`. Same rules: any
architecturally significant choice (hard to reverse, cross-service, cost/latency, constrains future work)
gets an ADR here **before** implementation; accepted ADRs are settled and superseded, never edited in place.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-muni-world-independent-jar.md) | muni-world is an independent jar reusing jethro's shared libraries | Accepted |
| [0002](0002-mission-scope-and-phasing.md) | Mission, scope, and phase order — a muni analytics platform, data first | Accepted |
| [0003](0003-source-taxonomy-and-registry.md) | Source taxonomy and the source registry | Accepted |
| [0004](0004-ingestion-architecture.md) | Ingestion architecture — connectors, polite scraping, landing→parse→normalise | Accepted |
| [0005](0005-raw-landing-and-provenance.md) | Immutable raw landing zone and end-to-end provenance | Accepted |
| [0006](0006-canonical-postgres-schema.md) | Canonical Postgres schema for the municipal domain | Accepted |
| [0007](0007-identifiers-and-entity-resolution.md) | Identifiers and entity resolution | Accepted |
| [0008](0008-legal-compliance-and-polite-crawling.md) | Legal, compliance, and polite-crawling policy | Accepted |
| [0009](0009-scheduling-change-detection-refresh.md) | Scheduling, change detection, and incremental refresh | Accepted |
| [0010](0010-document-extraction-pipeline.md) | Document extraction pipeline (official statements, ACFRs, disclosures) | Accepted |
| [0011](0011-data-quality-coverage-and-quarantine.md) | Data quality, coverage metrics, and quarantine | Accepted |
| [0012](0012-claude-assisted-analysis-and-prompt-library.md) | Claude-assisted analysis and the muni prompt library | Accepted |

## Reading order

ADR-0002 sets the **mission** (Kalotay-inspired muni analytics; **data first**; NY → NJ → PA). The data
platform then reads as one arc: **what** to collect (0003 sources) → **how** (0004 ingestion, 0008 legal
/polite crawling, 0009 scheduling) → **where it lands** (0005 raw+provenance, 0006 canonical schema) →
**making it coherent** (0007 identity, 0010 document extraction, 0011 quality/coverage). **0012** adds the
**Claude-assisted analysis layer + prompt library** (`../../prompts/`) that reasons over the collected data
under jethro's AI guardrails. Analytics (OAS on callable munis) is the deferred north star — the data model
in 0006 is built to serve it, and 0012 governs the LLM assistance around it.

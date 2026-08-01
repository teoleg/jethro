# ADR-0004: Ingestion architecture — connectors, polite scraping, and a landing→parse→normalise pipeline

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** ingestion, scraping, pipeline, provenance

## Context

Sources (ADR-0003) arrive as APIs, bulk files, HTML pages and PDFs, on different cadences and terms. We
need one architecture that ingests all of them without letting a parser bug lose the original data or a
scraper hammer a government site.

## Decision

A **three-stage pipeline**, provenance-first, with a common connector interface:

```
 register (ADR-0003) → FETCH → [raw landing, immutable] → PARSE → [staging] → NORMALISE → [canonical muni.*]
```

1. **Connector SPI.** Every source is fetched by a `SourceConnector` chosen by its `access_method`:
   - `ApiConnector` — structured endpoints (Census, state open-data/Socrata portals, any EMMA data
     downloads). Preferred whenever an API/bulk file exists — never scrape what you can pull cleanly.
   - `BulkFileConnector` — CSV/JSON/XBRL downloads (state comptroller extracts).
   - `ScrapeConnector` — HTML pages, obeying the polite-crawling policy (ADR-0008): honour `robots.txt`,
     per-host rate limits + backoff, conditional requests (ETag/Last-Modified), a descriptive User-Agent,
     and a fetch cache so a re-run doesn't re-hit the origin.
   - `DocumentConnector` — PDFs (official statements, ACFRs) handed to the extraction pipeline (ADR-0010).
2. **FETCH → raw landing (immutable).** Every fetch writes the *original bytes* to the raw store with
   provenance (ADR-0005) **before** any parsing. Parsing never touches the network; it reads the landing.
   A parser can be fixed and re-run over history without re-fetching.
3. **PARSE → staging.** Source-specific parsers turn raw artifacts into typed **staging** rows (still
   source-shaped, lightly typed). Parse failures are quarantined, counted and surfaced (ADR-0011) — never
   silently dropped (jethro invariant, inherited).
4. **NORMALISE → canonical.** Staging rows are mapped into the canonical `muni.*` model (ADR-0006),
   resolving identities (ADR-0007). This is where "an NJ AUD row" becomes "issuer X, fiscal year Y,
   metric Z", carrying its provenance link.

- **Idempotent & incremental.** Each stage keys on a content hash + source-record id so re-processing is
  safe and unchanged inputs are skipped (ADR-0009). At-least-once is fine; applying twice is a no-op.
- **Where it runs.** A scheduled worker inside the muni-world jar (own threads), publishing progress; the
  heavy PDF/OCR work can be isolated behind its own executor. Kafka (`muni.*` topics) carries
  ingest/parse/normalise events for observability and later fan-out; Postgres is the store.

## Consequences

- The **raw landing is the safety net**: parser and schema can evolve indefinitely without data loss, and
  every canonical value is traceable to the exact bytes it came from.
- "Prefer API/bulk over scrape" keeps us polite and robust; scraping is the fallback, boxed by ADR-0008.
- Stages are independently retryable and observable — a stuck county doesn't wedge the pipeline.

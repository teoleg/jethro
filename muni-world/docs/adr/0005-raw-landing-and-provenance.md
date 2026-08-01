# ADR-0005: Immutable raw landing zone and end-to-end provenance

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** provenance, storage, raw-data

## Context

A scraping/ingestion platform is only trustworthy if every derived fact can be traced to the exact source
artifact and fetch that produced it — for debugging, for re-processing, and because muni analysis feeds
decisions where "where did this number come from?" must always be answerable (the jethro "no invented
numbers" discipline, applied to sourced data).

## Decision

Every fetch lands as an **immutable raw artifact** with full provenance, and every canonical value links
back to it.

- **Raw artifact record** (`muni.raw_artifact`): `id`, `source_id` (ADR-0003), `url`, `http_status`,
  `fetched_at`, `content_type`, `content_hash` (SHA-256), `byte_size`, `storage_ref`, `etag`/`last_modified`.
  Rows are **append-only** — a re-fetch that differs is a *new* artifact (versioned by hash), never an
  overwrite. Identical content (same hash) is deduped to the existing artifact.
- **Where the bytes live.** Small text/JSON/CSV artifacts: Postgres (`bytea`/large object) — we already
  run Postgres. Large PDFs and bulk files: a content-addressed blob store referenced by `storage_ref`
  (local filesystem path now, S3 later behind a trigger — the record shape is storage-agnostic). Postgres
  stays the index/system-of-record either way.
- **Provenance chain.** Staging and canonical rows carry `raw_artifact_id` (and `parser_version` /
  `normaliser_version`). Any `muni.security`/`muni.financials` value can be walked back: canonical →
  staging → raw artifact → source → URL + timestamp + hash. Conflicting values from different sources are
  **both retained** with their provenance and reconciled by rule (ADR-0007/0011), never silently
  overwritten.
- **Reproducibility.** Because raw is immutable and parsers are versioned, the entire canonical store can
  be rebuilt from the landing zone — the landing is the ground truth, the canonical tables are a
  derivation.

## Consequences

- Total auditability: every fact answers "source, when, and the exact bytes."
- Re-processing (a fixed parser, a schema change) never needs to re-hit origins — kinder to the sites and
  far faster.
- Storage grows with raw retention; content-hash dedup and a storage-agnostic `storage_ref` keep it bounded
  and let large blobs move to object storage later without touching the data model.

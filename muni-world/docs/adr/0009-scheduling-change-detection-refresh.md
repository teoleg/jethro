# ADR-0009: Scheduling, change detection, and incremental refresh

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** scheduling, incremental, refresh, backfill

## Context

Muni data updates at wildly different rates: reference terms are near-static, ACFRs land annually, EMMA
disclosures trickle continuously, and trades print all day. Re-fetching everything constantly is impolite
(ADR-0008) and wasteful; never refreshing goes stale. We need per-source cadence, change detection, and a
clean backfill-vs-incremental split.

## Decision

A **scheduler + work queue** drives the pipeline (ADR-0004), keyed off the source registry.

- **Per-source cadence** on the `source` row (e.g. reference monthly, financials on a seasonal window
  around filing deadlines, EMMA disclosures daily, trades intraday if enabled). The scheduler enqueues due
  sources; it never fixes cadence in code.
- **Two modes per source:**
  - **Backfill** — walk the historical corpus once (e.g. all of a state's archived AUDs), priority-ordered
    by the Phase-1 geography (NY → NJ → PA) and source tier.
  - **Incremental** — thereafter fetch only what changed since a per-source **watermark** (last id / last
    modified / last page).
- **Change detection, three layers:** (1) HTTP conditional requests (ETag/Last-Modified) skip unchanged
  resources at the network; (2) **content hash** (ADR-0005) skips unchanged bytes before parsing;
  (3) record-level hashing skips unchanged rows before normalise. Unchanged inputs cost nothing downstream.
- **Frontier / discovery.** New issuers, documents and links discovered during a crawl are enqueued as new
  work (bounded, deduped) — the crawl expands coverage organically within the registered sources.
- **Reliability.** Retry with backoff; a **dead-letter** for persistently failing fetches/parses (surfaced,
  not lost — ADR-0011). At-least-once processing with idempotent stages (ADR-0004) makes retries safe.

## Consequences

- The origin sees minimal, conditional traffic; most cycles are near-no-ops until something actually
  changes — polite and cheap.
- Backfill and steady-state incremental share one pipeline, differing only by mode + watermark.
- Coverage grows deterministically and resumably: a crash resumes from watermarks, never restarts the crawl.

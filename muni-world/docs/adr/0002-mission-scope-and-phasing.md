# ADR-0002: Mission, scope, and phase order — a muni analytics platform, data first

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** mission, scope, roadmap

## Context

muni-world is inspired by Andrew Kalotay's *Interest Rate Management of Municipal Bonds*. The end goal is
**deep analysis of the U.S. municipal-bond universe** — the kind of option-adjusted analytics Kalotay is
known for: callable-bond OAS, effective duration/convexity, refunding efficiency, and relative-value on a
tax-aware basis. None of that is possible without a broad, clean, well-sourced **data foundation** first.

Muni data is uniquely fragmented: ~50,000 issuers, millions of CUSIPs, serial-bond structures, conduit
financings (issuer ≠ obligor), heavy PDF disclosure, and authority spread across federal (MSRB/EMMA,
Census, IRS), state (comptroller/finance departments), and thousands of local governments (counties,
cities, townships, school districts, authorities). There is no single feed.

## Decision

**Phase 1 is data. Full stop.** muni-world's current mission is to **collect, normalise, and warehouse the
municipal universe** — starting with data and building outward — before any pricing/analytics is attempted.

- **North star (deferred, but it shapes the schema).** The eventual analytics is Kalotay-style
  option-adjusted valuation of (mostly callable) municipal bonds. Every data decision is judged by whether
  it will feed that: security terms (coupon, maturity, **call schedule**, tax status, sinking funds),
  issuer/obligor credit and financials, and market prints. We build the data model to serve OAS later —
  but we do **not** build analytics this phase. Analysis, when it comes, is **Claude-assisted** over the
  collected data, governed by a versioned prompt library under jethro's AI guardrails — see ADR-0012.
- **Geographic staging: NY → NJ → PA first.** These three give us the richest state-level disclosure
  (NY Office of the State Comptroller, NJ Dept. of Community Affairs, PA DCED) and a large, diverse issuer
  base (state, authorities, counties, cities, townships, school districts) to prove the pipeline before
  going national. Coverage expands state-by-state behind a working pipeline, never by boiling the ocean.
- **Breadth of sources is the point.** "All possible data about munis" — official disclosure, market
  data, state/local government financials, and anything institutions publish — is the explicit aim
  (source taxonomy in ADR-0003). Web scraping is a first-class ingestion method, not an afterthought
  (ADR-0004/0008).
- **Reuse jethro's stack, own namespaces (ADR-0001).** Postgres is the system of record (the shared
  instance, muni-world's own `muni` schema). Exact-decimal money throughout (`NUMERIC`), never binary FP.

## Consequences

- Success this phase is measured in **coverage and cleanliness** (issuers/securities/documents captured
  per state, with provenance and quality metrics — ADR-0011), not in any analytic output yet.
- The domain model (ADR-0006) is designed against the *known* end use (OAS on callable munis), so call
  schedules, tax treatment and obligor credit are first-class from day one even though nothing prices them
  yet — avoiding a costly reshape later.
- A deliberately staged rollout (NY/NJ/PA) keeps the crawl polite, legal, and debuggable before scale.

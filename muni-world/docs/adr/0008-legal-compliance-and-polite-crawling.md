# ADR-0008: Legal, compliance, and polite-crawling policy

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** legal, compliance, scraping, ethics

## Context

muni-world scrapes government and institutional sites at scale. Most target data is **public record**, but
"public" is not "unlimited": sites have terms, robots directives, rate tolerances, and some data
(rating-agency feeds, CUSIP masterfiles) is licensed IP. A careless crawler is both a legal risk and a good
way to get blocked. Politeness and compliance are a design constraint, not an afterthought.

## Decision

Every connector (ADR-0004) operates under a **compliance policy enforced in code**, and every source row
(ADR-0003) records its legal basis.

- **Politeness.**
  - Honour `robots.txt` (fetch + cache per host; a disallowed path is not fetched).
  - Per-host **rate limit + exponential backoff**; a global concurrency/politeness budget.
  - **Conditional requests** (ETag/If-Modified-Since) and a fetch cache so re-runs don't re-hit origins.
  - A descriptive **User-Agent** identifying the project with a contact.
  - Per-source and global **kill-switch** (`enabled` flag) — one row disables a source instantly.
- **Access boundaries.**
  - Only publicly reachable pages — no authentication bypass, no paywall circumvention, no form/credential
    abuse.
  - Prefer official **APIs / bulk downloads** over scraping wherever offered (ADR-0004) — it is both
    kinder and usually permitted explicitly.
- **Licensing & IP.**
  - **CUSIP numbers** are licensed (CUSIP Global Services). Used as internal keys; **not** redistributed as
    a masterfile or bulk export (ADR-0007).
  - **Rating-agency** data (Moody's/S&P/Fitch/KBRA) is licensed — ingest only genuinely public rating
    actions/press releases, never a licensed feed; keep the `license`/`terms_url` on the source row.
  - **EMMA/MSRB**, Census, and state portals: follow each publisher's terms; retain attribution.
- **Privacy.** Government financial disclosure is not personal data, but if PII is encountered
  incidentally it is not stored — dropped at parse, logged as a count.
- **Auditability.** `robots_ok`, `terms_url`, `license` and `last_reviewed` live on every source row, so
  the legal posture of the whole crawl is queryable.

## Consequences

- Slower but sustainable and defensible collection; we don't get blocked or overstep.
- A clean line between *data we warehouse and can act on* and *identifiers/feeds we may key on but not
  redistribute* — important the moment any muni-world output leaves the system.
- Compliance is observable (source rows) rather than tribal knowledge.

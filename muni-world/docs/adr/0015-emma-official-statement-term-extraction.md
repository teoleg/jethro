# ADR-0015: EMMA Official Statement term extraction — the flagship data pipeline

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** Oleg
- **Tags:** emma, official-statement, pdf, extraction, terms, key-goal, legal

## Context

muni-world is a **public-data-only** platform (ADR-0002): we use legally-accessible disclosure, not paid
vendor feeds. That decision makes one capability the spine of the whole project — **turning an issuer's
Official Statement (OS) into structured bond terms.** Muni terms (coupon, maturity, **call schedule**, CUSIP,
tax status, par) are *disclosed by law* on **MSRB EMMA**, but only as **PDF documents**. There is no free,
clean, per-CUSIP terms API. So without OS extraction the bonds table stays empty on real data — which is
exactly where we are today (the Socrata path can't carry terms; the EMMA connector only *lands* the PDF).

The owner has therefore made OS extraction a **key goal of the project**, not a deferred nicety. This ADR is
the concrete realization of the generic ADR-0010 extraction frame, specialized to EMMA OS documents, and
promotes it in ADR-0002's phasing.

## Decision

Build an **EMMA Official Statement → structured terms** pipeline as a first-class, phased deliverable.

### What we extract (and what we deliberately do NOT)

- **Extract the durable term sheet.** From the OS **maturity schedule** and **redemption/optional-call**
  section: per-CUSIP **coupon, maturity date, call date/price, tax status, par**, keyed to the CUSIP-6 base.
  These are stable facts that don't go stale.
- **Do NOT treat the OS price/yield as a current mark.** The maturity schedule's yield/price is the
  **reoffering** level *at issuance*. Storing it as the bond's price would make the computed current
  yield/YTM/duration silently wrong as time passes. So **price is a separate concern** (a current-price
  source — MSRB trade prints / RTRS — is its own future source). A bond extracted from an OS is stored
  **terms-only**: it shows its terms immediately and its market economics **blank ("—") until a current
  price lands.** Honest gap over misleading number (the "no invented numbers" discipline).
- **Reoffering level is captured but labeled**, never shown as current (a later, clearly-marked field).

### How (phased)

1. **Phase 1 — the extraction core (this ADR's first build).** `PDFBox` (Apache-2.0) extracts text/layout
   from a **landed** OS PDF (ADR-0005 provenance; never mutate the raw). A deterministic
   `OfficialStatementParser` reads the maturity-schedule table + redemption paragraph into **term rows**,
   each with a **confidence** and page anchor. Rows it can't parse cleanly are **quarantined**, not guessed
   (ADR-0011). Term rows flow through the existing normalise→index pipeline (ADR-0004) into the bonds table.
   Testable offline on representative OS text — no network, no live EMMA.
2. **Phase 2 — live EMMA discovery + fetch.** Politely locate an issuer's/CUSIP's OS on EMMA and land the
   PDF (ADR-0008: respect EMMA Terms of Use, robots, rate limits, descriptive UA, conditional GETs; public
   disclosure documents, private analysis, no redistribution). Scanned-PDF OCR fallback (flagged) per
   ADR-0010. **Realization:** EMMA is postback/JS ASP.NET and serves no crawlable OS links in its raw HTML,
   so the fetch renders the page with **headless Chromium** (`HeadlessBrowser` → `EmmaAutoFetcher`),
   extracts the OS document links from the rendered DOM, downloads the PDFs into the OS inbox, and the
   folder loader (`DirectoryIngestService`) extracts them — fully hands-off. Off by default (needs a browser
   binary). A **manual/uploaded or folder-dropped OS PDF** is the always-available path when the browser
   isn't wired.
3. **Phase 3 — assisted extraction for hard layouts.** OS table formats vary widely; the deterministic
   parser covers the common columnar schedule, and messy layouts escalate to the local SLM / Claude frontier
   **under ADR-0012's guardrail** — grounded in the document, cited to the `raw_artifact_id`, confidence-
   gated, **never a model-asserted number**. Extraction is versioned and re-runnable over the immutable raw
   store, so improving the parser back-fills history without re-fetching.

## Consequences

- This is the pipeline that makes the platform *real* on public data alone — it's promoted to a **key goal**
  in ADR-0002 (no longer "the deferred hard part").
- The bonds table becomes genuinely populated from disclosure — terms first, economics when a current price
  source is added — with every value traceable to a landed OS (ADR-0005).
- **Price becomes optional** in the security model: a terms-only bond is first-class (blank economics), which
  is the honest state until the separate current-price source exists. That current-price source (MSRB trade
  prints) is now the clearest next data goal after this.
- Highest engineering cost in the project, deliberately staged (deterministic common-format parse → live
  crawl → assisted hard-format) so real terms land early and quality is never traded for coverage.

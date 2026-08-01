# ADR-0010: Document extraction pipeline (official statements, ACFRs, disclosures)

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** documents, pdf, extraction, ocr

## Context

The richest muni data lives in **documents**, mostly PDFs: **Official Statements** (bond terms, call
schedules, sources & uses, security), **ACFR/CAFR** annual financial reports (issuer/obligor financials),
and continuing-disclosure filings. Extracting structured facts from these is the highest-value and hardest
part of the data build. It must not compromise the "provenance + no unverified numbers" discipline.

## Decision

A dedicated **document-extraction sub-pipeline** feeding the parse→normalise stages (ADR-0004), on top of
the immutable raw PDF in the landing zone (ADR-0005):

1. **Text/layout extraction.** Native-text PDFs → direct text + table/layout extraction. **Scanned** PDFs →
   OCR fallback (flagged as OCR-derived, lower baseline confidence). The raw PDF is never mutated.
2. **Structure detection.** Classify the document (OS / ACFR / disclosure) and locate the sections that
   matter — for an OS: security description, **redemption/call provisions**, maturity schedule, tax
   opinion; for an ACFR: the financial statements and key totals.
3. **Field extraction → staging.** Pull the target fields into staging rows with per-field **confidence**,
   `parser_version`, page/coordinate anchors, and the `raw_artifact_id`. Start narrow and high-value:
   issuer + fiscal year + headline financial totals, and the call schedule / maturity terms (the fields OAS
   needs — ADR-0002/0006). Widen coverage later.
4. **Human-in-the-loop for low confidence.** A field below the confidence threshold is **quarantined for
   review**, never promoted to canonical silently. Mirrors the jethro discipline: a model/OCR output is
   **never** trusted as a money/terms number without a confidence gate + provenance (jethro invariant 7,
   inherited). A local SLM (Ollama, shared with jethro) may *assist* extraction/classification, but the
   number that lands is anchored to the document, not asserted by the model.

## Consequences

- The pipeline turns the PDF corpus — otherwise unusable — into structured, sourced facts, which is where
  most muni value hides.
- Confidence-gated promotion keeps a bad OCR read out of canonical financials while still capturing it (for
  review), so coverage grows without corrupting quality (ADR-0011).
- Extraction is versioned and re-runnable over the immutable raw store, so improving the extractor
  back-fills history without re-fetching.
- Highest engineering cost of the data phase — deliberately staged (native text before OCR, narrow fields
  before broad) so value lands early.

# ADR-0016 — SEC N-PORT fund holdings as the bulk CUSIP source

Status: Accepted (2026-08-10)

## Context

The universe problem: muni-world needs a real list of New York municipal CUSIPs, and the manual OS-PDF
path yields a handful of securities per document — no route to thousands. The obvious bulk sources fail
the ADR-0008 posture test: EMMA's Terms of Use prohibit automated access (settled — never scraped), and
CUSIP Global Services is a paid license whose data must not be redistributed. ETF issuers' holdings CSVs
are small (the NY ETF holds ~175 names) and sit behind brittle product-page URLs and site ToS.

There is a public, structured, sanctioned source: **Form N-PORT**. Every SEC-registered fund must file its
complete portfolio quarterly — security by security, **with CUSIP**, issuer name, coupon, and maturity — as
machine-readable XML on EDGAR. EDGAR is a public record explicitly served to programs (SEC fair-access
policy: declared User-Agent, ≤10 requests/sec; we make ~2 per fund per day). A New York municipal fund's
N-PORT is therefore a lawful bulk list of real NY muni CUSIPs.

## Decision

1. **New connector, existing pipeline.** `EdgarNportConnector` (an ADR-0004 connector) fetches a fund's
   EDGAR submissions JSON, finds its latest NPORT-P, lands `primary_doc.xml` as an immutable
   `RawArtifact` (ADR-0005 provenance), parses holdings, and upserts through the existing
   `MuniBondService.index` path (Postgres `muni.security` + LMDB, ADR-0006/0013). Sources are registered
   in a shipped read-only registry (`seeds/edgar-funds.csv`: `cik|expect_name|label|enabled`), following
   the audio-registry model — edit and rebuild, no writable host copy.

2. **A wrong CIK cannot silently pollute.** CIKs are configured by hand, so the connector **gates on the
   registrant name**: EDGAR's own submissions JSON carries the legal name, and ingestion refuses — loudly,
   naming both strings — unless it contains the registry's `expect_name`. No name match, no rows.

3. **Terms only, quarantine the rest.** A holding is ingested only when it is municipal debt
   (`issuerCat=MUN`; plain `DBT` accepted when the category is absent) with a 9-char CUSIP, a name, a
   maturity and a rate — the same durable-terms contract as the normaliser (ADR-0011). Cash, equities,
   repos, and unkeyable rows are counted and skipped, never force-fit. **No price is derived**: N-PORT's
   `valUSD/balance` is an as-of-quarter valuation, not a current mark; holdings land terms-only and stay
   blank until a real price source covers them (ADR-0015 discipline; see deferred register).

4. **Daily check, quarterly data.** A scheduler re-reads the latest filing daily (idempotent upsert,
   ~2 HTTP calls per fund) and reports per-fund status on `/api/muni/status`. Failures are loud and named.

## Consequences

- Thousands of real NY CUSIPs with issuer/coupon/maturity arrive from public filings, with provenance,
  and grow the issuer vocabulary the ADR-0014 lead matcher can draw on.
- The honest limits: this is the **held-by-funds** universe (small retail-only lines are
  underrepresented), refreshed quarterly, as-of the filing date — a starting list, not the full universe.
- CUSIPs obtained from public filings are used internally for identification and never redistributed as a
  dataset (ADR-0008 posture, unchanged).
- The SEC asks that the declared User-Agent identify the requester; `MUNI_HTTP_UA` should carry a contact
  address on the capture host.

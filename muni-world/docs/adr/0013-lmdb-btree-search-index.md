# ADR-0013: LMDB B-tree search-index layer (derived, ordered-key search)

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Oleg
- **Tags:** lmdb, search, indexing, storage

## Context

Postgres is muni-world's system of record (ADR-0006) — great for relational integrity and transactions.
But the analysis and UI need **fast ordered lookups** over large key spaces: *all CUSIPs for an issuer*,
*securities maturing 2030–2035*, *coupons between 4% and 5%*, *issuers in a state/county*. Those are
prefix and range scans over sorted keys — exactly what **LMDB's B+tree** does well, at memory-mapped speed,
with lock-free concurrent reads. LMDB is already muni-world's embedded store (ADR-0001), and per ADR-0005 a
derived store is rebuildable, so an index there costs nothing but a reindex if lost.

## Decision

Use LMDB as a **derived, ordered-key search-index layer** over the canonical Postgres data. Multiple named
databases (`Dbi`) in the single `Env`:

- **Primary** — `sec`: CUSIP-9 (fixed-width ASCII) → a compact security value blob. Because a CUSIP-6
  issuer is the **prefix** of its CUSIP-9s, this one B-tree serves **both** point lookup (get by CUSIP)
  **and** an issuer scan (prefix on CUSIP-6). No separate issuer index needed.
- **Secondary indexes** (composite keys, empty values): `idx_maturity` = `dateKey || cusip`;
  `idx_coupon` = `coupon || cusip`; `idx_geo` = `fips || cusip`. Range/prefix scans then return CUSIPs.

**Key encoding is the contract.** LMDB compares keys as **unsigned byte strings**, so:
- Numeric/date keys are **fixed-width big-endian** (`u32be` epoch-day, `u64be` scaled coupon) so **lexical
  byte order == numeric/chronological order**. A little-endian int would sort wrong — this is mandatory and
  enforced in `MuniKeys`.
- Identifiers (CUSIP, FIPS) are **fixed-width** so prefixes align (CUSIP-6 ⊂ CUSIP-9; state `34` ⊂ county
  `34003` ⊂ place). Composite keys concatenate fixed-width parts.
- Money stays exact: coupon is a **scaled long** (×1e6), never binary FP (inherited discipline).

**Derived + rebuildable.** The index is built/refreshed from Postgres; losing the LMDB file costs a reindex,
never data. Opened `MDB_NOSYNC` (fast; a crash just means reindex) — same posture as jethro's derived
stores. Reads are LMDB's zero-copy MVCC: many concurrent searches, one writer.

## Consequences

- Interactive **prefix and range search at memory-mapped speed** without loading Postgres for every query —
  the substrate for the search UI and the analysis layer (ADR-0012 prompts read candidate sets from here).
- The big-endian / fixed-width discipline is non-negotiable and centralised in `MuniKeys`; a bad key
  encoding silently breaks ordering, so it is unit-tested.
- Because the index is derived, a schema/index change is a **reindex from Postgres**, not an LMDB
  migration — no data at risk.
- LMDB's single-writer model is fine: indexing is a background batch; search is read-only and concurrent.

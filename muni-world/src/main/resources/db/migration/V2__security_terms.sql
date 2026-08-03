-- ADR-0006 `security` table: one CUSIP-9 per maturity/tranche — the unit whose terms are stored and (later)
-- priced against. This is muni-world's SYSTEM OF RECORD for bond terms; the LMDB index (ADR-0013) is a
-- DERIVED, rebuildable read layer over it. Loading an Official Statement writes rows here.
--
-- Money is exact NUMERIC (hard invariant 1 — never binary floating point). Terms-only rows are first-class:
-- `price` (and everything priced off it) stays NULL until a current-price source lands, never a fake number.
-- `source_id` is the provenance handle back to the landed artifact (ADR-0005); the full raw_artifact FK
-- arrives with the raw_artifact table — see docs/deferred-register.md.
CREATE TABLE IF NOT EXISTS muni.security (
    cusip         TEXT PRIMARY KEY,          -- CUSIP-9; its CUSIP-6 prefix is the issuer key
    issuer        TEXT,
    coupon        NUMERIC(9,6),              -- percent, e.g. 5.000000
    maturity_date DATE,
    dated_date    DATE,
    price         NUMERIC(12,6),             -- clean price per 100; NULL = terms-only (blank economics)
    tax_status    TEXT,
    call_date     DATE,
    call_price    NUMERIC(12,6),             -- per 100
    rating        TEXT,
    geo_fips      TEXT,
    source_id     TEXT,                      -- ADR-0005 provenance handle (landed artifact / upload)
    loaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Issuer scan (CUSIP-6 prefix) and geography scan mirror the LMDB access patterns.
CREATE INDEX IF NOT EXISTS security_cusip_prefix ON muni.security (cusip text_pattern_ops);
CREATE INDEX IF NOT EXISTS security_geo          ON muni.security (geo_fips);
CREATE INDEX IF NOT EXISTS security_updated      ON muni.security (updated_at DESC);

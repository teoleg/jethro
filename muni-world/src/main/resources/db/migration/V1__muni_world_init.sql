-- muni-world's own schema — kept entirely separate from the jethro schema. Flyway default-schema=muni.
-- V1 is just the founding table so the migration path exists; real domain tables arrive with the features.
CREATE SCHEMA IF NOT EXISTS muni;

-- A minimal municipal-instrument reference row. Exact-decimal money semantics (NUMERIC), never binary FP.
CREATE TABLE IF NOT EXISTS muni.instrument (
    instrument_id   TEXT PRIMARY KEY,
    cusip           TEXT UNIQUE,
    issuer          TEXT        NOT NULL,
    state           TEXT,
    coupon          NUMERIC(9,6),
    maturity_date   DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

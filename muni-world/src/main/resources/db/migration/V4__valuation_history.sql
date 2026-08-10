-- ADR-0016 amendment 2: the QUARTERLY VALUATION HISTORY hiding in EDGAR — every N-PORT ever filed is
-- still there (the form began mid-2019), so each held CUSIP has years of dated, fund-attested marks.
--
-- Rows are PER FUND per period (provenance preserved; the API par-weights across funds at read time in
-- exact NUMERIC). val_usd/par are verbatim from the filing; nothing is interpolated between quarters.
CREATE TABLE IF NOT EXISTS muni.valuation_history (
    cusip   TEXT        NOT NULL,
    as_of   DATE        NOT NULL,           -- the filing's repPdDate (period end), never the fetch date
    cik     TEXT        NOT NULL,           -- which fund attested this row
    par     NUMERIC(18,2),                  -- par held (units=PA, USD)
    val_usd NUMERIC(18,2),                  -- the fund's valuation of the position
    PRIMARY KEY (cusip, as_of, cik)
);
CREATE INDEX IF NOT EXISTS valuation_history_cusip ON muni.valuation_history (cusip, as_of);

-- Tiny ingest bookkeeping: which one-time jobs have completed (e.g. 'nport-backfill:<cik>'), so the
-- multi-hundred-filing history backfill runs once per fund, not on every boot.
CREATE TABLE IF NOT EXISTS muni.ingest_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

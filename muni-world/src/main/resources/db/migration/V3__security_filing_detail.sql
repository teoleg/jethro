-- ADR-0016 amendment: per-CUSIP detail the N-PORT filings carry but the first ingest dropped.
-- All of it is fund-attested fact from public SEC filings — no invented values:
--   coupon_kind    Fixed / Floating / Zero etc., verbatim from debtSec.couponKind
--   in_default     debtSec.isDefault — the issuer is in default on this issue (credit fact, not a model)
--   intr_arrears   debtSec.areIntrstPmntsInArrs — interest payments are in arrears
--   held_funds     how many registered funds held the CUSIP in the latest ingested cycle
--   held_par       total par those funds held (USD-denominated issues)
--   val_per100     par-weighted FILING valuation per 100: sum(valUSD)/sum(par)*100. As-of val_as_of,
--                  NEVER a live mark — displayed labeled, and analytics still wait for a real price.
--   val_as_of      the filings' reporting-period date (repPdDate), not the fetch date
ALTER TABLE muni.security ADD COLUMN IF NOT EXISTS coupon_kind  TEXT;
ALTER TABLE muni.security ADD COLUMN IF NOT EXISTS in_default   BOOLEAN;
ALTER TABLE muni.security ADD COLUMN IF NOT EXISTS intr_arrears BOOLEAN;
ALTER TABLE muni.security ADD COLUMN IF NOT EXISTS held_funds   INT;
ALTER TABLE muni.security ADD COLUMN IF NOT EXISTS held_par     NUMERIC(18,2);
ALTER TABLE muni.security ADD COLUMN IF NOT EXISTS val_per100   NUMERIC(12,6);
ALTER TABLE muni.security ADD COLUMN IF NOT EXISTS val_as_of    DATE;

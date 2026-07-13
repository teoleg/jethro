-- First non-USD instrument (quant-engine phase 3): SAP SE, a EUR-denominated equity.
-- Exercises cross-currency rollups end to end — its PnL/exposure is in EUR and converts
-- to USD in the book/firm rollups at the live EURUSD mark (Strata FxMatrix). ISIN is
-- SAP's real one; German shares use ISIN/WKN, not CUSIP.

insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    ('SAP', 'EQUITY', 'EUR', 1);

insert into instrument_symbology (instrument_id, source, symbol) values
    ('SAP', 'ISIN', 'DE0007164600'),
    ('SAP', 'sim', 'SAP');

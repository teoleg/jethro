-- Standard security identifiers on the instrument master. These live in
-- instrument_symbology (source, symbol) — the same table that holds the 'sim' feed
-- symbol — because it is exactly the external-identifier mapping. Invariant 2 is
-- unchanged: internal code (algos, risk, orders) keys on instrumentId; these IDs are
-- reference attributes surfaced in the reference-data view, not keys on the data path.
--
-- Identifiers are per asset class: equities carry CUSIP + ISIN; listed futures/rates
-- carry an exchange (CME Globex) root, not a CUSIP; FX spot carries the ISO pair.
-- Values are illustrative demo reference data.

insert into instrument_symbology (instrument_id, source, symbol) values
    -- equities: CUSIP (US) + ISIN
    ('AAPL', 'CUSIP', '037833100'), ('AAPL', 'ISIN', 'US0378331005'),
    ('MSFT', 'CUSIP', '594918104'), ('MSFT', 'ISIN', 'US5949181045'),
    ('AMZN', 'CUSIP', '023135106'), ('AMZN', 'ISIN', 'US0231351067'),
    ('GOOG', 'CUSIP', '02079K107'), ('GOOG', 'ISIN', 'US02079K1079'),
    -- futures / rates: exchange root (no CUSIP for listed derivatives)
    ('ES', 'GLOBEX', 'ES'), ('NQ', 'GLOBEX', 'NQ'), ('ZN', 'GLOBEX', 'ZN'),
    -- FX spot: ISO 4217 currency pair
    ('EURUSD', 'ISO', 'EUR/USD'), ('GBPUSD', 'ISO', 'GBP/USD');

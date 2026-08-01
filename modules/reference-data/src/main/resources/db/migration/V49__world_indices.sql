-- ADR-0129: major world equity indices as first-class reference data for the market-TREND feed —
-- US, Europe, Asia. Spot indices are a CONTEXT/trend reference, NOT tradable spot (the tradable
-- expression is the index future, e.g. ES/NQ). They are marked live via the existing Yahoo delayed
-- background poll (ADR-0056 — anything with 'yahoo' symbology is polled; no market-data code change) and
-- gated out of every trade path in code (sim tick set, forecast lifecycles, FusionExecutor veto).
--
-- Deliberately NO adv_usd / spread_bps: those are EXECUTION dials and an index never executes. currency
-- is the index's native currency. contract_multiplier is a nominal 1 (never used — indices carry no
-- position). region drives the US / EU / ASIA grouping on the trend panel.

-- INDEX is a NEW asset class, so widen the check constraint before inserting against it. AssetClass.java
-- already carries INDEX; this is the schema half of that same enum, and without it every insert below
-- fails 23514, Flyway aborts and the whole JVM refuses to boot. Same drop/re-add shape V7 used when it
-- added SWAP — the list is cumulative, no existing class is removed.
alter table instrument drop constraint instrument_asset_class_check;
alter table instrument add constraint instrument_asset_class_check
    check (asset_class in ('EQUITY', 'FUTURE', 'OPTION', 'FX', 'BOND', 'SWAP', 'INDEX'));

insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    ('SPX',    'INDEX', 'USD', 1),   -- S&P 500
    ('CCMP',   'INDEX', 'USD', 1),   -- Nasdaq Composite
    ('INDU',   'INDEX', 'USD', 1),   -- Dow Jones Industrial Average
    ('UKX',    'INDEX', 'GBP', 1),   -- FTSE 100
    ('DAX',    'INDEX', 'EUR', 1),   -- DAX 40
    ('CAC',    'INDEX', 'EUR', 1),   -- CAC 40
    ('SX5E',   'INDEX', 'EUR', 1),   -- Euro Stoxx 50
    ('NKY',    'INDEX', 'JPY', 1),   -- Nikkei 225
    ('HSI',    'INDEX', 'HKD', 1),   -- Hang Seng
    ('SHCOMP', 'INDEX', 'CNY', 1),   -- Shanghai Composite
    ('KOSPI',  'INDEX', 'KRW', 1),   -- KOSPI
    ('AS51',   'INDEX', 'AUD', 1)    -- S&P/ASX 200
on conflict (instrument_id) do nothing;

-- Yahoo symbology (V11 basis, ADR-0056): Yahoo's index tickers. The delayed background poll marks these
-- like any price-quoted name — no adapter change. Symbols never leak past market-data-gateway (invariant 2).
insert into instrument_symbology (instrument_id, source, symbol) values
    ('SPX',    'yahoo', '^GSPC'),
    ('CCMP',   'yahoo', '^IXIC'),
    ('INDU',   'yahoo', '^DJI'),
    ('UKX',    'yahoo', '^FTSE'),
    ('DAX',    'yahoo', '^GDAXI'),
    ('CAC',    'yahoo', '^FCHI'),
    ('SX5E',   'yahoo', '^STOXX50E'),
    ('NKY',    'yahoo', '^N225'),
    ('HSI',    'yahoo', '^HSI'),
    ('SHCOMP', 'yahoo', '000001.SS'),
    ('KOSPI',  'yahoo', '^KS11'),
    ('AS51',   'yahoo', '^AXJO')
on conflict (instrument_id, source) do nothing;

-- Display name + region (US / EU / ASIA) for the trend panel. An instrument in the master without a
-- display_name renders blank on the UI, so it is backfilled in the same change (refdata convention).
insert into instrument_attributes (instrument_id, name, value) values
    ('SPX',    'display_name', 'S&P 500'),                ('SPX',    'region', 'US'),
    ('CCMP',   'display_name', 'Nasdaq Composite'),       ('CCMP',   'region', 'US'),
    ('INDU',   'display_name', 'Dow Jones Ind. Avg'),     ('INDU',   'region', 'US'),
    ('UKX',    'display_name', 'FTSE 100'),               ('UKX',    'region', 'EU'),
    ('DAX',    'display_name', 'DAX 40'),                 ('DAX',    'region', 'EU'),
    ('CAC',    'display_name', 'CAC 40'),                 ('CAC',    'region', 'EU'),
    ('SX5E',   'display_name', 'Euro Stoxx 50'),          ('SX5E',   'region', 'EU'),
    ('NKY',    'display_name', 'Nikkei 225'),             ('NKY',    'region', 'ASIA'),
    ('HSI',    'display_name', 'Hang Seng'),              ('HSI',    'region', 'ASIA'),
    ('SHCOMP', 'display_name', 'Shanghai Composite'),     ('SHCOMP', 'region', 'ASIA'),
    ('KOSPI',  'display_name', 'KOSPI'),                  ('KOSPI',  'region', 'ASIA'),
    ('AS51',   'display_name', 'S&P/ASX 200'),            ('AS51',   'region', 'ASIA')
on conflict (instrument_id, name) do update set value = excluded.value;

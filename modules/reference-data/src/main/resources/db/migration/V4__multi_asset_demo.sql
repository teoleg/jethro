-- Multi-asset demo data (dev only): instruments across asset classes so the risk/PnL
-- views have several markets to consolidate. Multipliers use conventional contract
-- sizes; currencies are all USD here so the rollups don't need FX conversion yet
-- (cross-currency conversion is deferred — risk-pnl reports MIXED rather than guessing).
-- Prices are simulated from a common start price, so levels are illustrative, not real.

-- A macro desk to hold futures / FX / rates alongside the equity desks.
insert into book (book_id, name, base_currency, parent_id) values
    ('MACRO', 'Macro Desk', 'USD', 'FIRM');

insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    -- index & commodity futures (E-mini S&P 50/pt, E-mini Nasdaq 20/pt)
    ('ES', 'FUTURE', 'USD', 50),
    ('NQ', 'FUTURE', 'USD', 20),
    -- FX (spot, simplified to multiplier 1 for the demo)
    ('EURUSD', 'FX', 'USD', 1),
    ('GBPUSD', 'FX', 'USD', 1),
    -- rates (10Y note future, 1000/pt)
    ('ZN', 'BOND', 'USD', 1000);

insert into instrument_symbology (instrument_id, source, symbol) values
    ('ES', 'sim', 'ES'),
    ('NQ', 'sim', 'NQ'),
    ('EURUSD', 'sim', 'EURUSD'),
    ('GBPUSD', 'sim', 'GBPUSD'),
    ('ZN', 'sim', 'ZN');

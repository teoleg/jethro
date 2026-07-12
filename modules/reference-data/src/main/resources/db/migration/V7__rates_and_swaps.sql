-- Debt/rates coverage (demo): the Treasury futures curve (2Y/5Y/30Y alongside the
-- existing ZN 10Y) and first OTC interest-rate swap DEFINITIONS. Swaps are reference
-- data only for now — no sim marks, not tradeable — until Strata pricing gives them
-- honest valuation (quant-engine phase 4, ADR-0020); a market order on one would be
-- rejected for lack of market data, which is correct.

alter table instrument drop constraint instrument_asset_class_check;
alter table instrument add constraint instrument_asset_class_check
    check (asset_class in ('EQUITY', 'FUTURE', 'OPTION', 'FX', 'BOND', 'SWAP'));

-- Product terms for OTC instruments (tenor, index, day counts, ...): key/value so the
-- schema doesn't guess at every product shape before Strata's product model arrives.
create table instrument_attributes (
    instrument_id varchar(64)  not null references instrument (instrument_id),
    name          varchar(64)  not null,
    value         varchar(256) not null,
    primary key (instrument_id, name)
);

-- Treasury futures curve. CONVENTION: CME point multipliers — ZT (2Y, $200k face) $2000/pt;
-- ZF (5Y) and ZB (30Y bond, $100k face) $1000/pt, same as ZN.
insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    ('ZT', 'BOND', 'USD', 2000),
    ('ZF', 'BOND', 'USD', 1000),
    ('ZB', 'BOND', 'USD', 1000);

insert into instrument_symbology (instrument_id, source, symbol) values
    ('ZT', 'GLOBEX', 'ZT'), ('ZT', 'sim', 'ZT'),
    ('ZF', 'GLOBEX', 'ZF'), ('ZF', 'sim', 'ZF'),
    ('ZB', 'GLOBEX', 'ZB'), ('ZB', 'sim', 'ZB');

-- USD SOFR interest-rate swaps (vanilla fixed-vs-float). No CUSIP/exchange symbol —
-- real-world OTC identifiers are ANNA-DSB ISINs / UPIs, assigned per trade economics;
-- omitted here rather than invented. Multiplier 1: notional-quoted.
insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    ('USD_IRS_5Y', 'SWAP', 'USD', 1),
    ('USD_IRS_10Y', 'SWAP', 'USD', 1);

insert into instrument_attributes (instrument_id, name, value) values
    ('USD_IRS_5Y',  'product',        'Vanilla fixed-vs-float interest rate swap'),
    ('USD_IRS_5Y',  'tenor',          '5Y'),
    ('USD_IRS_5Y',  'float_index',    'SOFR (compounded in arrears)'),
    ('USD_IRS_5Y',  'fixed_day_count','30E/360'),
    ('USD_IRS_5Y',  'float_day_count','ACT/360'),
    ('USD_IRS_5Y',  'payment_freq',   'Annual fixed / Annual float'),
    ('USD_IRS_10Y', 'product',        'Vanilla fixed-vs-float interest rate swap'),
    ('USD_IRS_10Y', 'tenor',          '10Y'),
    ('USD_IRS_10Y', 'float_index',    'SOFR (compounded in arrears)'),
    ('USD_IRS_10Y', 'fixed_day_count','30E/360'),
    ('USD_IRS_10Y', 'float_day_count','ACT/360'),
    ('USD_IRS_10Y', 'payment_freq',   'Annual fixed / Annual float');

-- Dev seed: matches the sim adapter's instruments so every layer lines up locally.
-- Production reference data arrives through proper channels; this keeps the
-- local-dev-first invariant honest.

insert into book (book_id, name, base_currency, parent_id) values
    ('FIRM',  'Firm',        'USD', null),
    ('ALPHA', 'Alpha Desk',  'USD', 'FIRM'),
    ('BETA',  'Beta Desk',   'USD', 'FIRM');

insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    ('AAPL', 'EQUITY', 'USD', 1),
    ('MSFT', 'EQUITY', 'USD', 1),
    ('AMZN', 'EQUITY', 'USD', 1),
    ('GOOG', 'EQUITY', 'USD', 1);

insert into instrument_symbology (instrument_id, source, symbol) values
    ('AAPL', 'sim', 'AAPL'),
    ('MSFT', 'sim', 'MSFT'),
    ('AMZN', 'sim', 'AMZN'),
    ('GOOG', 'sim', 'GOOG');

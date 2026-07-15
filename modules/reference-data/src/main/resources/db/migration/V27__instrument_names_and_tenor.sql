-- Instrument display names + numeric swap tenor as reference-data attributes (review GAP-4).
-- These were hardcoded in app code (InstrumentDescriptions' 16-entry name map; swap tenor maps
-- duplicated in SwapBookService/Dv01Service). Refdata is the single source for instrument
-- attributes — code must not carry per-name data. A real (or larger sim) universe extends by
-- inserting rows here, not by recompiling. The description text is display/prompt-only, never a
-- number into sizing/risk (invariant 1).

-- display_name: what the instrument IS, so the narration model reads a real issuer instead of
-- inventing one for the ticker (ADR-0022). Anything without a row falls back to a generic
-- asset-class phrase in code (that's a generic phrase, not a hardcoded security).
insert into instrument_attributes (instrument_id, name, value) values
    ('AAPL',        'display_name', 'Apple stock'),
    ('MSFT',        'display_name', 'Microsoft stock'),
    ('AMZN',        'display_name', 'Amazon stock'),
    ('GOOG',        'display_name', 'Alphabet (Google) stock'),
    ('SAP',         'display_name', 'SAP SE stock (EUR)'),
    ('ES',          'display_name', 'S&P 500 future'),
    ('NQ',          'display_name', 'Nasdaq-100 future'),
    ('ZT',          'display_name', '2Y US Treasury future (rates)'),
    ('ZF',          'display_name', '5Y US Treasury future (rates)'),
    ('ZN',          'display_name', '10Y US Treasury future (rates)'),
    ('ZB',          'display_name', '30Y US Treasury future (rates)'),
    ('USD_IRS_5Y',  'display_name', '5Y USD rate swap (pay-fixed)'),
    ('USD_IRS_10Y', 'display_name', '10Y USD rate swap (pay-fixed)'),
    ('EURUSD',      'display_name', 'EUR/USD FX'),
    ('GBPUSD',      'display_name', 'GBP/USD FX')
on conflict (instrument_id, name) do update set value = excluded.value;

-- tenor_years: the swap's tenor as an integer the schedule builder needs (V7 already carries a
-- human '5Y'/'10Y' 'tenor' attribute; this is the machine-readable one code reads).
insert into instrument_attributes (instrument_id, name, value) values
    ('USD_IRS_5Y',  'tenor_years', '5'),
    ('USD_IRS_10Y', 'tenor_years', '10')
on conflict (instrument_id, name) do update set value = excluded.value;

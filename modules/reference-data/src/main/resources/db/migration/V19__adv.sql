-- Average daily volume in USD notional per instrument (ADR-0025 follow-up: market impact).
-- Stylized but realistic liquid-market magnitudes (order of magnitude is what impact math
-- needs): US mega-cap equities ~$5-15B/day, ES ~$250B, NQ ~$90B, Treasury futures $40-190B,
-- FX majors are the deepest markets on earth. The square-root impact law and the ADV
-- participation cap read these; instruments without a value simply have no impact model
-- (disclosed, never guessed). Swaps: D2D SOFR volume is large but lumpy; a $1M lot is
-- negligible participation, so impact is left unmodelled (null) for v1.
insert into instrument_attributes (instrument_id, name, value) values
    ('AAPL',   'adv_usd', '12000000000'),
    ('MSFT',   'adv_usd', '9000000000'),
    ('AMZN',   'adv_usd', '8000000000'),
    ('GOOG',   'adv_usd', '5000000000'),
    ('SAP',    'adv_usd', '600000000'),
    ('ES',     'adv_usd', '250000000000'),
    ('NQ',     'adv_usd', '90000000000'),
    ('ZT',     'adv_usd', '80000000000'),
    ('ZF',     'adv_usd', '120000000000'),
    ('ZN',     'adv_usd', '190000000000'),
    ('ZB',     'adv_usd', '40000000000'),
    ('EURUSD', 'adv_usd', '400000000000'),
    ('GBPUSD', 'adv_usd', '150000000000');
-- USDJPY trades on the sim tape but is not in the instrument master yet — no attribute row
-- (adding it here would violate the FK; add the instrument first when it becomes tradeable).

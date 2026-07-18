-- Modest hedge-focused universe expansion (ADR-0038): more equities spanning the beta range so
-- the beta-hedge has something to size against — a defensive low-beta name (little index cover
-- needed), a high-beta name (lots), and a financial — plus a risk-on FX pair. Kept to the
-- XXXUSD quote convention (like EUR/GBP) so FX P&L needs no inverted-quote special case.
-- Additive only: new instrument rows, nothing existing is touched (applies on next boot).
-- Factor betas/vols live in sim-calibration.json; this is the reference-data master + attributes.

insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    ('JNJ',    'EQUITY', 'USD', 1),
    ('NVDA',   'EQUITY', 'USD', 1),
    ('JPM',    'EQUITY', 'USD', 1),
    ('AUDUSD', 'FX',     'USD', 1);

insert into instrument_symbology (instrument_id, source, symbol) values
    ('JNJ',    'sim', 'JNJ'),
    ('NVDA',   'sim', 'NVDA'),
    ('JPM',    'sim', 'JPM'),
    ('AUDUSD', 'sim', 'AUDUSD');

-- ADV (USD notional/day): JNJ/JPM ~$7-8B mega-caps, NVDA among the most-traded names ~$30B,
-- AUDUSD a deep major (~$120B). The impact model + ADV participation cap read these.
insert into instrument_attributes (instrument_id, name, value) values
    ('JNJ',    'adv_usd', '7000000000'),
    ('NVDA',   'adv_usd', '30000000000'),
    ('JPM',    'adv_usd', '8000000000'),
    ('AUDUSD', 'adv_usd', '120000000000');

-- Per-name full bid/ask spreads (bps): liquid mega-caps a couple bps, NVDA tight, AUDUSD ~1 pip.
insert into instrument_attributes (instrument_id, name, value) values
    ('JNJ',    'spread_bps', '2.5'),
    ('NVDA',   'spread_bps', '2'),
    ('JPM',    'spread_bps', '2.5'),
    ('AUDUSD', 'spread_bps', '0.9');

insert into instrument_attributes (instrument_id, name, value) values
    ('JNJ',    'display_name', 'Johnson & Johnson stock (defensive, low beta)'),
    ('NVDA',   'display_name', 'NVIDIA stock (high beta)'),
    ('JPM',    'display_name', 'JPMorgan Chase stock (financials)'),
    ('AUDUSD', 'display_name', 'AUD/USD FX (risk-on)')
on conflict (instrument_id, name) do update set value = excluded.value;

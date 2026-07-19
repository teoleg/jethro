-- Backfill reference data for the V31 universe expansion (NVDA, JNJ, JPM, AUDUSD).
--
-- V31 added these four instruments to the master and V32 gave the three equities hedge betas,
-- but every ATTRIBUTE migration that feeds the UI ran earlier (symbology V5/V11/V12, ADV V19,
-- spreads V25, display names V27) and never covered them — so they showed up on the UI with a
-- generic name, no identifiers, no live-feed symbols, and default execution economics. This
-- backfills the same attributes the original names carry, on the same documented basis. All
-- inserts are idempotent (on conflict do update), so re-running is safe.
--
-- Contract specs (V28) are futures/swaps-only (CME deliverable windows, reference coupons), so
-- equities/FX need no rows there — nothing is missing for these four.

-- Display names (V27 basis) — display/prompt-only text, never a number into risk (invariant 1).
insert into instrument_attributes (instrument_id, name, value) values
    ('NVDA',   'display_name', 'Nvidia stock'),
    ('JNJ',    'display_name', 'Johnson & Johnson stock'),
    ('JPM',    'display_name', 'JPMorgan Chase stock'),
    ('AUDUSD', 'display_name', 'AUD/USD FX')
on conflict (instrument_id, name) do update set value = excluded.value;

-- Standard identifiers (V5 basis): equities carry CUSIP + ISIN; FX carries the ISO pair. Real
-- published identifiers — reference attributes surfaced in the refdata view, never data-path keys
-- (invariant 2). They live in instrument_symbology alongside the feed symbols.
insert into instrument_symbology (instrument_id, source, symbol) values
    ('NVDA',   'CUSIP', '67066G104'), ('NVDA', 'ISIN', 'US67066G1040'),
    ('JNJ',    'CUSIP', '478160104'), ('JNJ',  'ISIN', 'US4781601046'),
    ('JPM',    'CUSIP', '46625H100'), ('JPM',  'ISIN', 'US46625H1005'),
    ('AUDUSD', 'ISO',   'AUD/USD')
on conflict (instrument_id, source) do update set symbol = excluded.symbol;

-- Yahoo symbology (V11 basis, dev/demo adapter): equities map to their ticker; FX uses TICKER=X.
insert into instrument_symbology (instrument_id, source, symbol) values
    ('NVDA',   'yahoo', 'NVDA'),
    ('JNJ',    'yahoo', 'JNJ'),
    ('JPM',    'yahoo', 'JPM'),
    ('AUDUSD', 'yahoo', 'AUDUSD=X')
on conflict (instrument_id, source) do update set symbol = excluded.symbol;

-- Finnhub symbology (V12 basis): US-listed equities only — Finnhub's free tier streams US stock
-- trades; FX stays on the sim/Yahoo (so AUDUSD gets no finnhub row, exactly like EURUSD/GBPUSD).
insert into instrument_symbology (instrument_id, source, symbol) values
    ('NVDA', 'finnhub', 'NVDA'),
    ('JNJ',  'finnhub', 'JNJ'),
    ('JPM',  'finnhub', 'JPM')
on conflict (instrument_id, source) do update set symbol = excluded.symbol;

-- ADV in USD notional/day (V19 basis — money-adjacent: the sqrt-impact law + the ADV participation
-- cap read this). STYLIZED, order-of-magnitude realistic per the V19 convention (mega-caps
-- $1-15B/day, FX majors the deepest markets): NVDA is one of the highest-volume US names (~$25B);
-- JNJ a large defensive (~$1.2B); JPM a large bank (~$2.5B); AUDUSD a major but below EUR/GBP
-- (~$100B). Same stylized basis as V19, not a measured feed — refine if used for real execution.
insert into instrument_attributes (instrument_id, name, value) values
    ('NVDA',   'adv_usd', '25000000000'),
    ('JNJ',    'adv_usd', '1200000000'),
    ('JPM',    'adv_usd', '2500000000'),
    ('AUDUSD', 'adv_usd', '100000000000')
on conflict (instrument_id, name) do update set value = excluded.value;

-- Per-name execution spread in bps (V25 basis — money: the sim synthesises quotes from this AND
-- the cost model charges it, so quoted touch == charged touch). STYLIZED on the V25 convention
-- (liquid mega-caps a couple of bps; FX majors well under a bp): NVDA/JNJ/JPM ~2bp like the other
-- mega-caps; AUDUSD ~0.7bp (between EURUSD 0.46 and GBPUSD 0.8). Stylized, not measured — Oleg to
-- refine if these drive real execution.
insert into instrument_attributes (instrument_id, name, value) values
    ('NVDA',   'spread_bps', '2'),
    ('JNJ',    'spread_bps', '2'),
    ('JPM',    'spread_bps', '2'),
    ('AUDUSD', 'spread_bps', '0.7')
on conflict (instrument_id, name) do update set value = excluded.value;

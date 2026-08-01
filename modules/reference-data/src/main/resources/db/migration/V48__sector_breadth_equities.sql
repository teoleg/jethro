-- Sector breadth expansion (ADR-0125): the single highest-leverage move for EDGE on the live book.
--
-- On the live Alpaca feed the effective tradable universe was ~8 real-time US equities that are almost
-- all mega-cap tech (AAPL/MSFT/AMZN/GOOG/NVDA) plus JNJ/JPM — a tightly correlated cluster. By Grinold's
-- Fundamental Law (IR ≈ IC·√breadth) a momentum/reversion signal on a handful of names that move together
-- has almost no achievable information ratio no matter how good the signal is, which is exactly what the
-- loop measured (reversion positive but only t≈1.3, nothing significant). The fix is BREADTH, not more
-- signal tuning: add liquid names Alpaca prices in REAL TIME (US equities) spanning LOW-CORRELATION
-- sectors — energy, healthcare, financials, staples, industrials, consumer, utilities — so the same
-- signals have many more, less-correlated bets to harvest.
--
-- 12 names across 7 sectors, ~tripling the live equity cross-section. All are large/mega-cap, deeply
-- liquid, and stream on the Alpaca free IEX tier (US equities). Additive only — nothing existing is
-- touched; applies on next boot. This is the reference-data master + attributes (invariant 9: the
-- universe is the refdata master). Real CUSIP/ISIN identifiers are display-only reference attributes
-- (invariant 2 — never data-path keys) and are DEFERRED rather than invented here (see deferred-register);
-- their absence shows a blank identifier in the refdata view but does not affect trading, pricing or risk.

-- Master rows (EQUITY, USD, multiplier 1 — a share is quoted per unit of currency, like every equity here).
insert into instrument (instrument_id, asset_class, currency, contract_multiplier) values
    ('XOM',  'EQUITY', 'USD', 1),   -- Energy
    ('CVX',  'EQUITY', 'USD', 1),   -- Energy
    ('UNH',  'EQUITY', 'USD', 1),   -- Healthcare
    ('PFE',  'EQUITY', 'USD', 1),   -- Healthcare
    ('BAC',  'EQUITY', 'USD', 1),   -- Financials
    ('PG',   'EQUITY', 'USD', 1),   -- Consumer staples
    ('KO',   'EQUITY', 'USD', 1),   -- Consumer staples
    ('WMT',  'EQUITY', 'USD', 1),   -- Consumer staples / retail
    ('CAT',  'EQUITY', 'USD', 1),   -- Industrials
    ('HD',   'EQUITY', 'USD', 1),   -- Consumer discretionary
    ('MCD',  'EQUITY', 'USD', 1),   -- Consumer discretionary
    ('NEE',  'EQUITY', 'USD', 1)    -- Utilities
on conflict (instrument_id) do nothing;

-- Sim symbology (the dev/test adapter maps 1:1 on the ticker, like every equity here).
insert into instrument_symbology (instrument_id, source, symbol) values
    ('XOM','sim','XOM'),('CVX','sim','CVX'),('UNH','sim','UNH'),('PFE','sim','PFE'),
    ('BAC','sim','BAC'),('PG','sim','PG'),('KO','sim','KO'),('WMT','sim','WMT'),
    ('CAT','sim','CAT'),('HD','sim','HD'),('MCD','sim','MCD'),('NEE','sim','NEE')
on conflict (instrument_id, source) do update set symbol = excluded.symbol;

-- Alpaca symbology (V44 basis, ADR-0056): US equities stream on the free IEX tier; the symbol IS the
-- ticker. This is what makes each name REAL-TIME tradable on the live feed rather than a delayed poll.
insert into instrument_symbology (instrument_id, source, symbol) values
    ('XOM','alpaca','XOM'),('CVX','alpaca','CVX'),('UNH','alpaca','UNH'),('PFE','alpaca','PFE'),
    ('BAC','alpaca','BAC'),('PG','alpaca','PG'),('KO','alpaca','KO'),('WMT','alpaca','WMT'),
    ('CAT','alpaca','CAT'),('HD','alpaca','HD'),('MCD','alpaca','MCD'),('NEE','alpaca','NEE')
on conflict (instrument_id, source) do update set symbol = excluded.symbol;

-- Yahoo symbology (V11 basis, dev/background adapter): equities map to their ticker.
insert into instrument_symbology (instrument_id, source, symbol) values
    ('XOM','yahoo','XOM'),('CVX','yahoo','CVX'),('UNH','yahoo','UNH'),('PFE','yahoo','PFE'),
    ('BAC','yahoo','BAC'),('PG','yahoo','PG'),('KO','yahoo','KO'),('WMT','yahoo','WMT'),
    ('CAT','yahoo','CAT'),('HD','yahoo','HD'),('MCD','yahoo','MCD'),('NEE','yahoo','NEE')
on conflict (instrument_id, source) do update set symbol = excluded.symbol;

-- Finnhub symbology (V12 basis): US-listed equities stream on the free tier; symbol is the ticker.
insert into instrument_symbology (instrument_id, source, symbol) values
    ('XOM','finnhub','XOM'),('CVX','finnhub','CVX'),('UNH','finnhub','UNH'),('PFE','finnhub','PFE'),
    ('BAC','finnhub','BAC'),('PG','finnhub','PG'),('KO','finnhub','KO'),('WMT','finnhub','WMT'),
    ('CAT','finnhub','CAT'),('HD','finnhub','HD'),('MCD','finnhub','MCD'),('NEE','finnhub','NEE')
on conflict (instrument_id, source) do update set symbol = excluded.symbol;

-- ADV in USD notional/day (V19/V31 basis — money-adjacent: the sqrt-impact law + the ADV participation
-- cap read this). STYLIZED, order-of-magnitude realistic per the V19 convention (large/mega-cap US names
-- ~$1.5-8B/day), NOT a measured feed — refine if used for real execution. Values chosen from each name's
-- typical dollar volume band.
insert into instrument_attributes (instrument_id, name, value) values
    ('XOM','adv_usd','8000000000'), ('CVX','adv_usd','5000000000'), ('UNH','adv_usd','4000000000'),
    ('PFE','adv_usd','3000000000'), ('BAC','adv_usd','5000000000'), ('PG','adv_usd','3000000000'),
    ('KO','adv_usd','2500000000'),  ('WMT','adv_usd','4000000000'), ('CAT','adv_usd','2000000000'),
    ('HD','adv_usd','3000000000'),  ('MCD','adv_usd','2500000000'), ('NEE','adv_usd','2000000000')
on conflict (instrument_id, name) do update set value = excluded.value;

-- Per-name full bid/ask spread in bps (V25/V31 basis — money: the sim synthesises quotes from this AND
-- the cost model charges it). STYLIZED on the V25 convention (liquid large-caps a couple of bps), not
-- measured — Oleg to refine if these drive real execution.
insert into instrument_attributes (instrument_id, name, value) values
    ('XOM','spread_bps','2.5'), ('CVX','spread_bps','2.5'), ('UNH','spread_bps','3'),
    ('PFE','spread_bps','2'),   ('BAC','spread_bps','2'),   ('PG','spread_bps','2.5'),
    ('KO','spread_bps','2'),    ('WMT','spread_bps','2.5'), ('CAT','spread_bps','3'),
    ('HD','spread_bps','2.5'),  ('MCD','spread_bps','2.5'), ('NEE','spread_bps','3')
on conflict (instrument_id, name) do update set value = excluded.value;

-- Display names (V27 basis — display/prompt text only, never a number into risk). Sector noted so the
-- diversification intent is legible on the UI and in the model's narrative context.
insert into instrument_attributes (instrument_id, name, value) values
    ('XOM','display_name','Exxon Mobil stock (energy)'),
    ('CVX','display_name','Chevron stock (energy)'),
    ('UNH','display_name','UnitedHealth stock (healthcare)'),
    ('PFE','display_name','Pfizer stock (healthcare)'),
    ('BAC','display_name','Bank of America stock (financials)'),
    ('PG','display_name','Procter & Gamble stock (consumer staples)'),
    ('KO','display_name','Coca-Cola stock (consumer staples)'),
    ('WMT','display_name','Walmart stock (consumer staples / retail)'),
    ('CAT','display_name','Caterpillar stock (industrials)'),
    ('HD','display_name','Home Depot stock (consumer discretionary)'),
    ('MCD','display_name','McDonald''s stock (consumer discretionary)'),
    ('NEE','display_name','NextEra Energy stock (utilities)')
on conflict (instrument_id, name) do update set value = excluded.value;

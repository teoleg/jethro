-- Per-NAME execution spreads (ADR-0025): liquid names differ 10x within one asset class,
-- so the class-level constant is now only the fallback. Values are stylized full bid/ask
-- spreads anchored on real tick sizes / typical touch widths: mega-caps a couple of bps;
-- index futures ~1 tick (ES 0.25/5450 = 0.46bp, NQ 0.25/19800 = 0.13bp); Treasury futures
-- ~1 tick (ZN 1/64 on 110.5 = 1.4bp, ZT 1/128 on 103 = 0.76bp); EURUSD ~0.5 pip. The sim's
-- quote synthesis reads the SAME attribute, so quoted touch == charged touch, per name.
insert into instrument_attributes (instrument_id, name, value) values
    ('AAPL',   'spread_bps', '2'),
    ('MSFT',   'spread_bps', '2.5'),
    ('AMZN',   'spread_bps', '3'),
    ('GOOG',   'spread_bps', '3.5'),
    ('SAP',    'spread_bps', '8'),
    ('ES',     'spread_bps', '0.46'),
    ('NQ',     'spread_bps', '0.13'),
    ('ZT',     'spread_bps', '0.76'),
    ('ZF',     'spread_bps', '0.73'),
    ('ZN',     'spread_bps', '1.4'),
    ('ZB',     'spread_bps', '2.6'),
    ('EURUSD', 'spread_bps', '0.46'),
    ('GBPUSD', 'spread_bps', '0.8');

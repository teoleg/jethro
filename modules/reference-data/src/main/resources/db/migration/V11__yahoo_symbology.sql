-- Yahoo Finance symbology (ADR-0023): maps internal instrumentIds to Yahoo tickers for the
-- dev/demo-only Yahoo market-data adapter. Price-quoted names only — equities, index futures,
-- FX. Rates (Treasury futures, swaps, the SOFR curve) stay on the curve sim: there is no free
-- SOFR zero curve on Yahoo, and keeping them simulated preserves the coherent Rates/Swaps demo.
-- Yahoo symbols never leak past the market-data gateway (invariant 2); internal code keys on
-- instrumentId. Real-world tickers where known; SAP trades in Frankfurt as SAP.DE.

insert into instrument_symbology (instrument_id, source, symbol) values
    ('AAPL', 'yahoo', 'AAPL'),
    ('MSFT', 'yahoo', 'MSFT'),
    ('AMZN', 'yahoo', 'AMZN'),
    ('GOOG', 'yahoo', 'GOOG'),
    ('SAP',  'yahoo', 'SAP.DE'),
    ('ES',   'yahoo', 'ES=F'),
    ('NQ',   'yahoo', 'NQ=F'),
    ('EURUSD', 'yahoo', 'EURUSD=X'),
    ('GBPUSD', 'yahoo', 'GBPUSD=X');

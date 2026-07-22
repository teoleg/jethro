-- Alpaca symbology (ADR-0056): maps internal instrumentIds to Alpaca symbols for the free real-time
-- WebSocket adapter (IEX tier). US-listed equities only — Alpaca's free tier streams US stock trades;
-- FX, index/rate futures and the SOFR curve stay on the sim / Yahoo background (Alpaca free doesn't
-- cover them). SAP is deliberately left off, exactly like Finnhub (V12): our SAP is EUR/Frankfurt while
-- Alpaca's "SAP" is the USD NYSE ADR — mixing them would corrupt the currency. Symbols never leak past
-- the gateway (invariant 2); internal code keys on instrumentId.

insert into instrument_symbology (instrument_id, source, symbol) values
    ('AAPL', 'alpaca', 'AAPL'),
    ('MSFT', 'alpaca', 'MSFT'),
    ('AMZN', 'alpaca', 'AMZN'),
    ('GOOG', 'alpaca', 'GOOG'),
    ('NVDA', 'alpaca', 'NVDA'),
    ('JNJ',  'alpaca', 'JNJ'),
    ('JPM',  'alpaca', 'JPM');

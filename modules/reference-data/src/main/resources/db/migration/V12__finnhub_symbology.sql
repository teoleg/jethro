-- Finnhub symbology (ADR-0024): maps internal instrumentIds to Finnhub symbols for the
-- real-time WebSocket adapter. US-listed equities only — Finnhub's free tier streams US stock
-- trades; index/FX futures and the SOFR curve stay on the sim (Finnhub free doesn't cover them).
-- SAP is deliberately left off: our SAP instrument is EUR (Frankfurt), while Finnhub's "SAP" is
-- the USD NYSE ADR — mixing them would corrupt the currency. Symbols never leak past the gateway
-- (invariant 2); internal code keys on instrumentId.

insert into instrument_symbology (instrument_id, source, symbol) values
    ('AAPL', 'finnhub', 'AAPL'),
    ('MSFT', 'finnhub', 'MSFT'),
    ('AMZN', 'finnhub', 'AMZN'),
    ('GOOG', 'finnhub', 'GOOG');

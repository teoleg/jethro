-- Finnhub symbology for the liquid US majors (ADR-0056). JPM/NVDA/JNJ were added to the master in
-- V31 (attributes backfilled V34) but only ever had a 'yahoo' symbol, so they rode a SINGLE (delayed)
-- source — and a lone-source hiccup blanked the position (observed: a short JPM in the ALPHA book
-- showed mark 0 → 0 exposure → frozen PnL). Giving them a 'finnhub' symbol adds a REAL-TIME primary;
-- combined with the retained Yahoo background (fallback) and the MarkCache freshness guard, a name
-- stays marked while either source is live. All three are USD US-listed equities that Finnhub's free
-- tier streams — matching our instrument currency (unlike SAP, still off: our SAP is EUR/Frankfurt,
-- Finnhub's "SAP" is the USD NYSE ADR — see V12). Symbols never leak past the gateway (invariant 2).

insert into instrument_symbology (instrument_id, source, symbol) values
    ('JPM',  'finnhub', 'JPM'),
    ('NVDA', 'finnhub', 'NVDA'),
    ('JNJ',  'finnhub', 'JNJ');

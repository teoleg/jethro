-- ADR-0029 (full session-epoch namespacing): scope the persisted firm/book equity by feed mode.
-- These tables back the P&L-history / equity curve AND the firm drawdown breaker's peak equity
-- (select max(total_pnl) from firm_equity). Without a feed_mode they mix sim and live: a live
-- session's chart shows the prior sim P&L, and — a real correctness issue — the breaker's
-- high-water mark is contaminated by sim gains. The per-day series is now per-mode; the day PK
-- gains feed_mode. Existing rows were the sim session → default SIM. Reads filter by the running
-- Provenance.mode() (mirrors V29 fills / V35 orders / V36 hypotheses).
alter table firm_equity add column feed_mode varchar(8) not null default 'SIM';
alter table firm_equity drop constraint firm_equity_pkey;
alter table firm_equity add primary key (day, feed_mode);

alter table book_equity add column feed_mode varchar(8) not null default 'SIM';
alter table book_equity drop constraint book_equity_pkey;
alter table book_equity add primary key (day, feed_mode, book);

-- ADR-0027 EOD day boundary: per-book cumulative P&L at each session close, mirroring
-- firm_equity. Day attribution = consecutive-row differences, computed at read time —
-- the close snapshot is the stored fact, the daily delta is derived (never stored twice).
create table if not exists book_equity (
    day             date            not null,
    book            varchar(64)     not null,
    realized_pnl    numeric(38, 10) not null,
    unrealized_pnl  numeric(38, 10) not null,
    total_pnl       numeric(38, 10) not null,
    primary key (day, book)
);

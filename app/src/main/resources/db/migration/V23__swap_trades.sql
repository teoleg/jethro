-- Trade-dated swap registry (ADR-0020 follow-up): every SWAP fill becomes a dated trade so
-- the rates view can price the SEASONED swap (remaining schedule -> roll-down) instead of
-- the ledger's fresh-tenor approximation. One row per fill, idempotent on fill_id (the
-- risk consumer replays fills from the beginning on every boot, invariant 6). trade_day is
-- the SESSION day at recording time (sim-compressed days age trades in minutes).
create table swap_trades (
    fill_id    varchar(64)     primary key,
    instrument varchar(64)     not null,
    book       varchar(64)     not null,
    side       varchar(8)      not null,
    lots       numeric(24, 6)  not null,
    entry_par  numeric(18, 6)  not null,
    trade_day  date            not null
);

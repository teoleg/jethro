-- Historical-simulation VaR + firm drawdown breaker (ADR-0027).
--
-- daily_close: one close per instrument per (server-local) day, written by upserting the
-- latest mark all day — at rollover yesterday's row freezes as the close. A calendar-less
-- day boundary on purpose: the session calendar (ADR-0027 item 4) later refines "close"
-- to the session close without changing this schema. Curve pseudo-instruments
-- (USD.SOFR.*/USD.TSY.*) are stored too — that history enables rates VaR later.
--
-- firm_equity: one total-P&L observation per day (same upsert pattern) — the persisted
-- peak feeds the firm max-drawdown breaker across restarts.

create table daily_close (
    day        date          not null,
    instrument varchar(64)   not null,
    close      numeric(24, 6) not null,
    primary key (day, instrument)
);

create table firm_equity (
    day       date           primary key,
    total_pnl numeric(38, 10) not null
);

create index idx_daily_close_instrument on daily_close (instrument, day desc);

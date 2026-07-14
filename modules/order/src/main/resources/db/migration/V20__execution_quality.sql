-- TCA vs arrival price (ADR-0025): implementation shortfall per fill.
--
-- orders.arrival_price: the mid at ORDER SUBMISSION (the decision price) — captured
-- before routing so a worked LIMIT order that fills minutes later still measures against
-- what the market showed when the decision was made.
--
-- execution_quality: one row per fill. slippage_bps sign convention: POSITIVE = cost
-- (BUY filled above arrival / SELL below), NEGATIVE = price improvement. Price-quoted
-- instruments: bps of arrival price; rate-quoted (swaps, rate_quoted=true): basis points
-- of RATE, additive — the two are different units, so aggregates keep them apart.
alter table orders add column arrival_price numeric(24, 6);

create table execution_quality (
    order_id      varchar(64)     primary key,
    instrument    varchar(64)     not null,
    side          varchar(8)      not null,
    quantity      numeric(24, 6)  not null,
    arrival_price numeric(24, 6)  not null,
    fill_price    numeric(24, 6)  not null,
    slippage_bps  numeric(18, 4)  not null,
    rate_quoted   boolean         not null,
    filled_at     timestamptz     not null
);

create index idx_execution_quality_instrument on execution_quality (instrument, filled_at desc);

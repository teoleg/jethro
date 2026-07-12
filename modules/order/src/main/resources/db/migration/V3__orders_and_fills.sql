-- Order lifecycle + fills (ADR-0008). Money as NUMERIC (invariant 1). idempotency_key
-- is UNIQUE so a re-submitted command cannot create a second order (invariant 6).
-- fills is the source of truth for positions (invariant 3); risk-pnl projects from it.

create table orders (
    order_id        varchar(64)  primary key,
    idempotency_key varchar(128) not null unique,
    book_id         varchar(64)  not null,
    instrument_id   varchar(64)  not null,
    side            varchar(8)   not null check (side in ('BUY', 'SELL')),
    order_type      varchar(8)   not null check (order_type in ('MARKET', 'LIMIT')),
    quantity        numeric(24, 6) not null check (quantity > 0),
    limit_price     numeric(24, 6),
    status          varchar(20)  not null,
    reason          varchar(256),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);

create table fills (
    fill_id       varchar(64)  primary key,
    order_id      varchar(64)  not null references orders (order_id),
    book_id       varchar(64)  not null,
    instrument_id varchar(64)  not null,
    side          varchar(8)   not null check (side in ('BUY', 'SELL')),
    quantity      numeric(24, 6) not null check (quantity > 0),
    price         numeric(24, 6) not null check (price >= 0),
    executed_at   timestamptz  not null
);

create index idx_orders_book on orders (book_id);
create index idx_orders_created on orders (created_at desc);
create index idx_fills_order on fills (order_id);
create index idx_fills_executed on fills (executed_at desc);

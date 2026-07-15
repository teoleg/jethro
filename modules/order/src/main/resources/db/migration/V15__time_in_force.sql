-- Time-in-force (ADR-0025): GTC works until filled/cancelled, IOC cancels on arrival if
-- not marketable, DAY expires at the session close (rejected until the session calendar
-- exists, ADR-0027). Existing rows are historically GTC — the only behaviour that existed.

alter table orders
    add column time_in_force varchar(8) not null default 'GTC'
        check (time_in_force in ('GTC', 'IOC', 'DAY'));

-- Working-order matching (ADR-0025) scans ROUTED LIMIT orders per instrument on new marks.
create index idx_orders_working on orders (instrument_id) where status = 'ROUTED';

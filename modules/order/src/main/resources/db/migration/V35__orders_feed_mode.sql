-- ADR-0029 (full session-epoch namespacing): tag each order with the feed mode it was created under,
-- exactly as V29 did for fills. Without it a sim→live switch shows the prior sim session's orders in
-- the live order list, and — worse — live matching would fill a leftover sim ROUTED LIMIT order
-- against live marks (cross-mode fill). Existing rows predate the switch and were the sim session, so
-- they default to SIM. Reads filter by the running Provenance.mode(); id lookups stay unfiltered.
alter table orders add column feed_mode varchar(8) not null default 'SIM';
create index if not exists idx_orders_feed_mode on orders (feed_mode);

-- ADR-0029 (full session-epoch namespacing): tag each dated swap trade with the feed mode its fill was
-- booked under, so a sim→live switch doesn't value the prior sim session's swaps into the live rates
-- book (DV01 / seasoned PV). Mirrors V29 (fills) / V35 (orders) / V36 (hypotheses). Existing rows were
-- the sim session → default SIM; SwapBookService reads filter by the running Provenance.mode().
alter table swap_trades add column feed_mode varchar(8) not null default 'SIM';
create index if not exists idx_swap_trades_feed_mode on swap_trades (feed_mode);

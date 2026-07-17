-- ADR-0029: scope the fills source-of-truth by feed mode so positions never rebuild across
-- sim/live/replay. Existing rows are sim history (default SIM). The positions projection seeds
-- only from the current mode's fills; the fills topic (now mode-namespaced) supplies increments.
alter table fills add column feed_mode varchar(8) not null default 'SIM';
create index if not exists idx_fills_feed_mode on fills (feed_mode);

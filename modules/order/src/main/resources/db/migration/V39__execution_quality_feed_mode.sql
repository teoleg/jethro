-- ADR-0029 (full session-epoch namespacing): tag each TCA row with the feed mode its fill was executed
-- under, so a sim→live switch doesn't blend the prior sim session's slippage into the live TCA view or
-- its per-instrument averages. Mirrors V29 (fills) / V35 (orders). Existing rows were the sim session →
-- default SIM; recent()/aggregates() read filters by the running Provenance.mode().
alter table execution_quality add column feed_mode varchar(8) not null default 'SIM';
create index if not exists idx_execution_quality_feed_mode on execution_quality (feed_mode);

-- ADR-0029 (full session-epoch namespacing): tag each executed AI hypothesis with the feed mode it
-- was raised under, so a sim→live switch doesn't show the prior sim session's theses/outcomes in the
-- live AI view. Mirrors V29 (fills) / V35 (orders). Existing rows were the sim session → default SIM;
-- the recent() read filters by the running Provenance.mode().
alter table hypothesis_record add column feed_mode varchar(8) not null default 'SIM';
create index if not exists idx_hypothesis_record_feed_mode on hypothesis_record (feed_mode);

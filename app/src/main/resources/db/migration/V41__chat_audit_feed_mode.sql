-- ADR-0029 (full session-epoch namespacing): stamp each chat turn with the feed mode it was asked
-- under. Chat answers about positions/PnL/risk are answered against the running session, so the audit
-- trail records which mode's book a turn described — a later mode-scoped "recent questions" view (or an
-- export) can filter on it. Provenance stamp only: the audit log itself is never aggregated into a
-- number. Existing rows were the sim session → default SIM.
alter table chat_audit add column feed_mode varchar(8) not null default 'SIM';

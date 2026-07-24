-- Dynamic discovery-driven universe audit trail (ADR-0060 §5: "every promotion/eviction is a row —
-- who/when/why/evidence"). Durable, append-only log of what the daily promotion gate DECIDED, so the
-- growth of the tracked universe is reversible and auditable.
--
-- Phase 1 writes PROPOSED / REJECTED rows only (dry-run: the gate is evaluated but nothing is written to
-- refdata). Phase 2 adds PROMOTED / EVICTED rows when the runtime refdata write path lands. dry_run marks
-- which era a row is from. Stamped with feed_mode + session_epoch (invariant 8) so proposals made under
-- SIM are never confused with a LIVE session's.

create table universe_promotion (
    id            bigserial    primary key,
    at_millis     bigint       not null,
    session_epoch varchar(64),
    feed_mode     varchar(16),
    instrument_id varchar(64)  not null,
    action        varchar(16)  not null
        check (action in ('PROPOSED', 'PROMOTED', 'EVICTED', 'REJECTED')),
    outcome       varchar(32)  not null,   -- the policy Outcome enum name (LOW_SCORE, PROMOTE, ...)
    score         numeric(18, 4),
    distinct_days integer,
    sources       varchar(512),            -- comma-joined source labels that corroborated it
    reason        varchar(512) not null,
    dry_run       boolean      not null default true
);

create index idx_universe_promotion_instrument on universe_promotion (instrument_id);
create index idx_universe_promotion_at on universe_promotion (at_millis);

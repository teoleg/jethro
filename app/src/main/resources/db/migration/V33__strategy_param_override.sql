-- ADR-0052: live, DB-persisted strategy tuning.
-- The effective value of a jethro.strategy.* dial is the override here if present, else the
-- application.properties value (precedence: DB override > config). Config stays the seed and the
-- "reset to default" target. No risk/money NUMBER lives in this schema — it stores whatever value
-- an operator set at runtime; its provenance is the audit row below (who/when/old->new), which is
-- the source-of-record the risk convention requires for a live-set dial.

create table if not exists strategy_param_override (
    param       text primary key,          -- the dial key, e.g. 'threshold-sigmas'
    value       text not null,             -- the override, stored as text (parsed to the dial's type)
    updated_by  text,                       -- actor who set it (from the request; 'ui' when unattributed)
    note        text,                       -- optional operator note ("widened to see small moves")
    updated_at  timestamptz not null default now()
);

-- Append-only history: every set/reset is one row. THIS is the provenance for a live-set risk dial.
create table if not exists strategy_param_change (
    id          bigserial primary key,
    param       text not null,
    old_value   text,                       -- effective value before the change (null if none)
    new_value   text,                       -- null on a reset-to-default
    actor       text,
    note        text,
    changed_at  timestamptz not null default now()
);

create index if not exists idx_strategy_param_change_param on strategy_param_change (param, changed_at desc);

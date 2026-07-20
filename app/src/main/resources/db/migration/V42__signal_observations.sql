-- ADR-0055 phase 1: per-signal health telemetry. One row per source's live directional call, resolved
-- by the realised forward return of following it — the evidence the fusion layer weights sources by.
-- Analytics only: NEVER a number into positions/PnL/risk (ADR-0016 / invariant 7). Prices are NUMERIC
-- (invariant 1); the realised return is a dimensionless ratio, also NUMERIC at the persistence boundary.
-- feed_mode-scoped (ADR-0029) so a sim→live switch never blends sim signal quality into the live view.
create table signal_observations (
    id               varchar(64)  primary key,
    source           varchar(32)  not null,   -- momentum, mean-reversion, hypothesis, social, learned
    instrument       varchar(32)  not null,
    direction        smallint     not null,   -- +1 long, -1 short, 0 neutral (no bet)
    entry_mark       numeric(20, 6) not null, -- the mark when the signal fired
    entry_at         timestamptz  not null,
    horizon_seconds  integer      not null,   -- forward-return measurement horizon
    resolved         boolean      not null default false,
    exit_mark        numeric(20, 6),
    realized_return  numeric(20, 10),         -- directional return of following the call (dimensionless)
    outcome          varchar(8),              -- WIN / LOSS / FLAT
    resolved_at      timestamptz,
    feed_mode        varchar(8)   not null default 'SIM'
);
create index idx_signal_obs_due on signal_observations (resolved, entry_at);
create index idx_signal_obs_source on signal_observations (source, resolved);
create index idx_signal_obs_feed_mode on signal_observations (feed_mode);

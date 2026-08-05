-- ADR-0140: the ADR-0080 aim is DURABLE derived state.
--
-- The aim is the desk's intended position — an exponential path toward the planner's target whose
-- time constant is, by the ADR-0080 identity, exactly one signal-evidence horizon. It was held in a
-- plain in-memory HashMap that was reseeded from the held quantity at every process start AND
-- discarded the moment a name fell out of one cycle's target list, so the path could never complete
-- its transient and the desk could not reach the fraction of its target the ADR-0094 buffer requires
-- before it will route an order.
--
-- Derived data only (ADR-0014): every row here is recomputable from the planner's own target
-- sequence — nothing in this table is a source of truth for a position, a fill or a PnL. `fills`
-- remains the source of truth for positions (invariant 3).
--
-- No risk/money NUMBER lives in this schema. It stores a QUANTITY the planner computed; the dials
-- that size it (buffer fraction, adjustment rate, gross cap) are unchanged and live elsewhere.
--
-- feed_mode-scoped (ADR-0029 / invariant 8) so a sim->live switch starts a fresh intent rather than
-- continuing one built on the other mode's stream.
--
-- NUMERIC, not a float: a quantity is exact decimal end to end (invariant 1). Scale 6 matches the
-- planner's QTY_SCALE.

create table if not exists fusion_aim (
    feed_mode   varchar(8)     not null,
    instrument  text           not null,
    aim         numeric(20, 6) not null,
    updated_at  timestamptz    not null default now(),
    primary key (feed_mode, instrument)
);

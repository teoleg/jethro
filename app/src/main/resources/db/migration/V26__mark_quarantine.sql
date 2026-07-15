-- Corporate-action / bad-print quarantine (ADR-0024), made durable across restart.
-- The MarkCache jump guard freezes an instrument in memory; without this table a restart
-- forgot the freeze and reloaded the mark as stale (jump-guard exempt), letting the bad
-- print the operator quarantined flow straight into risk/orders. A safety control must
-- survive a restart. Scaled-long prices (1e-6 units, PRICE_SCALE) — the exact on-disk
-- form the cache uses; boundary conversion happens on read (invariant 1).
create table mark_quarantine (
    instrument_id       varchar(64) primary key,
    last_good_scaled    bigint      not null,
    suspect_scaled      bigint      not null,
    quarantined_at      timestamptz not null default now()
);

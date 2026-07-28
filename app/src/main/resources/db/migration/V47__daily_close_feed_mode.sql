-- ADR-0073 — invariant 8 / ADR-0029: feed-mode scope the daily close series.
--
-- `daily_close` is the ONE end-of-day artifact that was never mode-scoped. EodService.rollover()
-- writes firm_equity and book_equity WITH feed_mode (their PKs include it) and, three lines earlier,
-- daily_close WITHOUT it, keyed (day, instrument) with an upsert. So a LIVE session's closes and a
-- SIM session's closes land in the SAME series and overwrite each other on shared days.
--
-- Everything downstream of that series measures RISK: historical + parametric VaR (VarService) and
-- per-instrument daily volatility (InstrumentVolService, which vol-targets position sizing). A feed
-- boundary injects a phantom close-to-close return the size of the gap between the two feeds' price
-- levels, and the EWMA(lambda=0.94) estimator weights the most recent observations most heavily —
-- precisely where the interleaving sits.
--
-- Backfill is deterministic from the platform's OWN session record (firm_equity.feed_mode), never
-- from any rule about prices:
--   * days before the first session this platform ever closed are the HistorySeeder bootstrap ->
--     'SEED': reference history, not any session's output, admissible as the prior in every mode;
--   * session days whose feed mode is recorded unambiguously (exactly one mode in firm_equity for
--     that day) take that mode;
--   * session-era days with no unambiguous recorded mode are DELETED. A close whose feed mode cannot
--     be established is not an admissible risk observation, and daily_close is derived data
--     (ADR-0014) that the running session re-accrues.
-- On a fresh database firm_equity is empty, both bounds are NULL, and every statement is a no-op.

alter table daily_close add column feed_mode varchar(8) not null default 'SIM';

update daily_close set feed_mode = 'SEED'
 where day < (select min(day) from firm_equity);

delete from daily_close
 where day >= (select min(day) from firm_equity)
   and day not in (select day from firm_equity group by day having count(distinct feed_mode) = 1);

update daily_close dc set feed_mode = fe.feed_mode
  from (select day, max(feed_mode) as feed_mode from firm_equity
         group by day having count(distinct feed_mode) = 1) fe
 where dc.day = fe.day;

-- The key that made the two sessions collide. A LIVE and a SIM close for the same name on the same
-- day are now two distinct observations, each in its own series, and neither overwrites the other.
alter table daily_close drop constraint daily_close_pkey;
alter table daily_close add constraint daily_close_pkey primary key (day, instrument, feed_mode);
drop index if exists idx_daily_close_instrument;
create index idx_daily_close_instrument on daily_close (instrument, feed_mode, day desc);

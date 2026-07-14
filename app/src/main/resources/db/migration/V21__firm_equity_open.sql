-- ADR-0027 calendar refinement: the firm total at each SESSION OPEN (captured at the day
-- boundary, i.e. after the sim's close->open gap), so today's P&L splits into an overnight
-- leg (open - previous close) and an intraday leg (live - open) that survive restarts.
alter table firm_equity add column open_pnl numeric(38, 10);

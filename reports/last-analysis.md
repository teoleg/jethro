The platform has been DEAD since 07:00Z — ADR-0129 added `INDEX` to the Java enum but never widened the SQL check constraint, so Flyway aborts and the JVM refuses to boot; I completed the migration.

*(Every figure below is read from `logs/report.md`, `logs/jethro-app.log`, `reports/run-status.json`,
`flyway_schema_history` / `pg_constraint`, or `score-change.py status`. None is authored here —
invariant 7 / ADR-0016.)*

## Situation triage

1. **Money** — unreadable, and that is the finding. Every live endpoint in this run's `logs/report.md`
   returns `URLError: [Errno 111] Connection refused`; `score-change.py status` prints `app unreachable —
   not measured`. There is no java process and no listener on 8080 (only Postgres 5432 and Redpanda 9092
   are up). The `total_pnl 126.87240898 / gross 0E-8` in `run-status.json` is a **frozen last-known value**
   repeated verbatim across all nine cycles from 07:00Z to 11:00Z — not a live read. The book is not flat
   by choice; it is flat because the trading platform does not exist right now.
2. **Risk** — no exposure and no headroom reading, because nothing is running. This is the DORMANT
   failure state in its most literal form: not a strategy that declined to trade, but a JVM that cannot
   start. Zero risk, and also zero possibility of return, for four and a half hours.
3. **Cause** — not a loop change and not the market. `logs/jethro-app.log` ends in a Flyway
   `FlywaySqlScriptException: Script V49__world_indices.sql failed`, `SQLSTATE 23514`, `new row for
   relation "instrument" violates check constraint "instrument_asset_class_check"`, failing row
   `(SPX, INDEX, USD, 1.00000000)`. `AssetClass.java` carries `INDEX` (ADR-0129); the schema does not —
   `V7__rates_and_swaps.sql:9` still lists only `EQUITY, FUTURE, OPTION, FX, BOND, SWAP`, which I
   confirmed is the live constraint definition in `pg_constraint`. ADR-0129 shipped the enum half of a new
   asset class and not the schema half. Flyway fails → `PersistenceConfig.flyway` bean fails → Spring
   context aborts → exit. `flyway_schema_history` tops out at **V48**; V49 has never applied anywhere and
   Postgres rolled its transaction back cleanly, so there is no failed row to repair.
4. **Danger** — not the bleeding-near-the-cap state. The danger here is total unavailability: a dead
   platform cannot make money, cannot cut a position if the market moves against the marks it left behind,
   and cannot be measured. It overrides every signal-research idea in the queue.
5. **Order-level post-mortem** — no orders to attribute; the window contains none. `recent_orders` is
   unreadable for the same reason everything else is.
6. **Memory** — rule 103 said a WARN nothing consumes is a confession; the sharper version this cycle is
   that an *enum* nothing constrains is the same defect one layer down. Findings appended.
7. **Change vs market** — **neither.** No loop commit was deployed into this window and no market move
   touched the book, because the book was not live. The PnL is unchanged because it is a stale number, not
   because a position held. I claim no credit and take no blame for the 126.87 figure; it is simply the
   last thing the app said before it died.

## What I changed and why

`V49__world_indices.sql` now drops and re-adds `instrument_asset_class_check` with `INDEX` appended,
using the same cumulative drop/re-add shape `V7` used when it introduced `SWAP` — no existing class
removed. I edited V49 in place rather than adding a V50 because Flyway runs in version order: a V50
widening would never be reached, since V49 fails first. This is safe precisely because V49 has never
successfully applied (history ends at V48). Verified by piping the whole migration through psql inside a
`BEGIN … ROLLBACK`: `ALTER TABLE / ALTER TABLE / INSERT 0 12 / INSERT 0 12 / INSERT 0 24`, then rolled
back, so Flyway still applies it fresh on boot. `./gradlew -Pci test` is green.

I deliberately fixed forward rather than reverting ADR-0129: the owner committed the world-index UI ticker
strip (`867b803`) immediately after, so the feature is wanted, and the only thing wrong with it was the
missing constraint. Before shipping I confirmed this does not open a trade path — bringing 12 `INDEX` rows
live for the first time is gated at the order chokepoint (`FusionExecutor:128` vetoes `INDEX` as
"not tradable spot", explicitly holding even with `require-backtest-support=false`) and in
`CrossSectionalReversionLifecycle:159`. Indices carry no position, so no exposure.

## On the pending baseline

`reports/.pending-baseline.json` still holds ADR-0126, so the standing rule is no new change while it
measures. I shipped anyway, and the reason is that the rule protects *evidence* and there is none to
protect: ADR-0126 has accumulated zero cycles because the app it was supposed to run in has been dead, and
it will accumulate zero more forever unless something boots. This is a service restore, not a strategy or
sizing change — it touches no signal, no dial, no risk model. I did not hand-edit the pending baseline;
`score-change.py baseline` correctly declined to record a new one against an unreachable app, so ADR-0126
remains the change under measurement and should start earning real cycles once the wrapper restarts.
Next run should grade ADR-0126 on live telemetry for the first time.

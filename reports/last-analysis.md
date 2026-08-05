The app has not been running since last cycle — ADR-0140's migration collided with an existing V48 and Flyway killed the boot; renumbered to V51 and added a build guard so a version clash can never silently kill the app again.

*(Every figure below is read from `logs/jethro-app.log`, `logs/report.md`, `reports/run-status.json` and
the scorer's own output. None is authored here — invariant 7 / ADR-0016.)*

# Last analysis — 2026-08-05 20:00Z

## Situation

1. **Money — unmeasurable, and I will not pretend otherwise.** There is no live PnL, exposure,
   attribution or order history this run: every endpoint returns
   `URLError: <urlopen error [Errno 111] Connection refused>` and the report's SITUATION header reads
   `(risk endpoint unavailable — could not read live PnL/exposure.)`. `scripts/score-change.py score`
   printed `cannot measure current vector (…Connection refused); leaving pending baseline for next run`.
   The window is **unmeasured**, not flat — quoting the last-known figures as this window's would be
   authoring numbers.
2. **Risk.** Also unreadable, and for the same reason. No breaker state, no cap utilisation. The book is
   whatever it was when the process died; nothing has been traded into or out of it since.
3. **Cause — my last change, entirely.** `3cc91bc46` (ADR-0140) shipped
   `app/…/db/migration/V48__fusion_aim.sql`, but `V48__sector_breadth_equities.sql` already existed in
   `modules/reference-data` (ADR-0125). `PersistenceConfig.flyway()` runs **one** Flyway over
   `classpath:db/migration`, merging every module's migrations, so a version number is a **global**
   identifier. Flyway refused to resolve — `FlywayException: Found more than one migration with version
   48` — `flyway` failed, then `refDataRepository`, `instrumentRefSource`, `universeController`, and the
   context aborted. The baseline recorded at **19:44:13Z** came from the *previous* still-live process;
   the new build died at **19:44:54Z** and nothing has served since.
4. **Danger.** Not the usual kind — no breaker, no cap. The danger is that the desk was **absent**: a
   dead JVM trades nothing, reports nothing and hedges nothing, and had the scorer reached a stale
   process it would have blamed ADR-0140's *mechanism* for a vector produced by its *filename*.
5. **Attribution.** 100% change, 0% market — the inverse of the last five windows. No market condition
   can stop a Flyway resolver.

## Diagnosis

The defect is not in ADR-0140's reasoning; it is in a namespace assumption. Migrations live in four
module trees and are numbered as if each tree owned its own sequence, but assembly merges them into a
single Flyway line. V50 was already taken by `modules:order` and V48 by `modules:reference-data`, neither
visible from `app/`. Nothing caught it: each module is internally consistent, `-Pci test` was green on the
broken tree, and the collision only comes into existence once the classpaths merge at runtime. That is the
worst shape a defect can have here — invisible to the build, fatal at boot, and it costs *every* cycle
rather than some money in one.

Verification outranks novelty, so this cycle repairs that and nothing else. The ADR-0116 freeze does not
bind: a pending change that never executed has no evidence to protect.

## Change

`V48__fusion_aim.sql` → **`V51__fusion_aim.sql`** (V50 is the repo's highest, so V51 is the next free
number — read off the tree, not chosen). The table schema, `JdbcAimStore`, the ADR-0080-derived ageing
window and every ADR-0140 code path are **byte-identical** — only the filename moves. So this *repairs*
the pending change rather than replacing it, and ADR-0140's own VERIFY-BY survives to be graded next run.
No dial, band, rate, gate or cap is touched, and the deterministic floor is untouched.

Plus the guard that makes the class of defect impossible to ship silently:
`ModuleBoundariesTest.migrationVersionsAreUniqueAcrossModules` resolves `classpath*:db/migration/V*.sql`
— exactly the merged view Flyway sees — and fails the build on a repeated version, with a non-vacuity
assertion so it cannot pass on an empty scan. It was **proven against the live defect before the fix**:
run on the broken tree it failed, naming both `V48__fusion_aim.sql` and `V48__sector_breadth_equities.sql`.
After the rename, `./gradlew -Pci test` is green.

**VERIFY-BY next run:** `ops_jvm` returns a JSON body with an `uptimeSeconds` instead of
`Connection refused`; `logs/jethro-app.log` contains no `FlywayException`; `flyway_schema_history` shows a
`51` row. If those hold, the boot regression closes and ADR-0140's mechanism becomes gradeable for the
first time.

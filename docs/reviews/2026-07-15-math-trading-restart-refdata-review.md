# Focused review: restart survival, UI identifiers, hardcoded refdata (2026-07-15)

Commissioned axes (Oleg): (1) the system must survive restart; (2) every object must have
a clear, searchable identifier shown in the UI (order id, instrument id); (3) main app
code must contain no hardcoded securities or refdata — everything comes from the sim or a
real source. Plus a math/trading lens on what actually blocks real use.

Severity: **BLOCKER** (stops real use) / **GAP** (wrong behaviour or violates the stated
principle) / **POLISH**.

---

## Axis 1 — Restart survival

State inventory: what survives a process restart today, and what silently does not.

| State | Persisted? | On restart | Verdict |
|---|---|---|---|
| Orders (all states) | Postgres `orders` | reloaded; working-order index reseeded (OrderService.java:69) | ✅ |
| Fills | Postgres `fills` | source of truth on disk | ✅ (but see BLOCKER-1) |
| Arrival prices (TCA) | `orders.arrival_price` | reloaded | ✅ |
| Firm drawdown high-water mark | seeded from `firm_equity` (FirmBreakerMonitor.java:50) | peak survives — breaker can't be reset by a bounce | ✅ (well done) |
| Autonomy track record | `hypothesis_record` reloaded into `executed` (HypothesisLifecycle.java:157) | scored outcomes + P&L rebuilt | ✅ |
| Daily closes / EOD equity | Postgres | reloaded | ✅ |
| Marks (last value) | LMDB `marks` db → `loadStale` | reload as STALE, refreshed by feed | ✅ |
| **Positions / realized P&L** | rebuilt by replaying the `fills` **topic** from offset 0 | correct only within broker retention | ❌ **BLOCKER-1** |
| **Mark-jump quarantine** | **nothing** (MarkCache is memory-only; LMDB stores marks, not the flag) | **quarantine forgotten** | ❌ **GAP-2** |

### BLOCKER-1 — positions rebuild from the fills TOPIC, not the fills TABLE

`RiskDataConsumer` (app/.../risk/RiskDataConsumer.java:82-107) seeks the fills topic to
the beginning at every boot to rebuild `RiskProjection`. No topic retention is configured
anywhere in the repo, so the auto-created topic keeps the broker default (~7 days). Once
the platform has run longer than that, **a restart rebuilds positions from a truncated
fill history** — every position, realized P&L, exposure, VaR and EOD equity is then wrong,
with no error raised. Invariant 3 names the `fills` **table** as the source of truth, but
nothing reads it back. This is the single biggest "survives restart" defect: it survives,
but silently produces wrong numbers after the retention window.
Fix: rebuild the projection from the Postgres `fills` table at boot, then consume the
topic from LATEST for live increments. (Also raised as P1 in the general review;
consolidated here.)

### GAP-2 — corporate-action quarantine does not survive restart

An operator freezes a bad-print / split instrument via the MarkCache jump guard; the
`quarantined` flag lives only on the in-memory `MarkHolder`. On restart the flag is gone
and marks reload as `stale`, which is *explicitly exempt from the jump guard* (the first
post-stale update is accepted as the new baseline). Net effect: **a restart clears a
safety quarantine and lets the very bad print it was protecting against flow straight into
risk/orders.** A safety control that a restart disables is worse than no control, because
the operator believes it is still armed.
Fix: persist the quarantine set (LMDB or a small Postgres table) and restore it on boot;
a quarantined instrument must reload quarantined, not stale.

---

## Axis 2 — Searchable identifiers in the UI

Rule requested: order id and instrument id (and by extension fill id) must be visible and
searchable for every object.

- **Instrument id** — shown everywhere: positions, orders, fills, blotter, indicators. ✅
- **Order id** — **not shown anywhere as a column.** In `orders.html` the order id exists
  only internally (the cancel button's `onclick`, the fill-lookup key); the visible columns
  are time / instr / CUSIP / book / side / type / TIF / qty / limit / status / fill. The
  index.html order blotter (line 399) and AI-action blotter (line 372) likewise omit it.
  So a user cannot read, copy, or search an order by its id from the UI. ❌ **GAP-3**
- **Fill id** — not shown anywhere. ❌ **GAP-3**

GAP-3: add an order-id column (and fill-id where fills are listed) to the order/fill
tables, monospaced and copyable, so every object on screen carries the identifier that
ties it to logs, the DB, and the event stream. This is a small, high-value change — it is
what makes the platform debuggable in operation.

---

## Axis 3 — Hardcoded securities / refdata in main app code

Sweep of main source (excluding sim calibration `sim-calibration.json`, the reference-data
Flyway migrations, worked-example javadoc, and format samples — those are legitimate
sources). What remains is instrument-specific data hardcoded IN CODE, keyed by instrument
id, that a real (or larger sim) universe could not extend without a recompile:

| Location | What is hardcoded | Severity | Where it belongs |
|---|---|---|---|
| `InstrumentDescriptions.java` | 16 instrument → English name/description ("AAPL"→"Apple stock", …) | GAP-4 | a `name`/`description` column in reference-data (refdata has no name field today — that's the root cause) |
| `SwapPricingService.REFERENCE_SWAPS` | `USD_IRS_5Y/10Y` demo coupon + $1M notional, in code | GAP-4 | refdata (swap contract attributes) |
| `SwapBookService.TENOR_YEARS` **and** `Dv01Service.SWAP_TENOR_YEARS` | swap id → tenor years, **duplicated** in two classes | GAP-4 | a `tenor_years` refdata attribute; de-dup |
| `BondFutureDurations.DELIVERABLE` | ZT/ZF/ZN/ZB CME deliverable windows | GAP-4 (mild) | real CME contract spec — legitimately a table, but should be refdata-sourced, not a code constant |
| `SimIndicatorsSource.TILES` | 7 instrument ids chosen for the top strip | POLISH | a display-config property (it's presentation curation, not trading logic) |
| `HypothesisGenerator` few-shot prompt | example uses "AAPL" | POLISH | harmless, but a neutral placeholder avoids biasing the model toward a real ticker |

Not violations (verified): `FxConversion` keys on the `…USD` naming *convention*, not
specific pairs; `HistoricalBars` only names tickers in a javadoc format example; the sim
universe itself comes from `sim-calibration.json`; all multipliers/ADV/spread/notional/
mod-duration already come from reference-data migrations.

**Root cause (GAP-4):** reference-data has no `name`/`description` field and no
`tenor_years`/contract-spec attributes for rates products, so code fills the gap with
hardcoded maps. The fix is one small refdata schema addition plus reading those attributes
where the maps are today — after which the maps delete. This is exactly the principle you
stated: instrument attributes must come from the refdata source, not code.

**RESOLVED (commits after this review).** All of the above now come from reference data:
- `display_name` (V27) → `InstrumentNameSource`; the 16-entry name map deleted.
- `tenor_years` (V27) → `SwapTenorSource`; both duplicated swap-tenor maps deleted.
- `deliverable_short/long_years` (V28) → `BondFutureDurations.DeliverableWindowSource`;
  the CME window map deleted.
- `reference_coupon` (V28) + `tenor_years` → `SwapPricingService.ReferenceSwapUniverse`;
  the hardcoded REFERENCE_SWAPS list deleted.
Each source snapshots the refdata attribute at wiring time and falls back to skip/empty
when refdata is absent (never guesses). `SimIndicatorsSource.TILES` (display curation) and
the `HypothesisGenerator` few-shot "AAPL" (a prompt example) remain — they are presentation
/ prompt scaffolding, not instrument data feeding risk, and are POLISH not GAP.

---

## Math / trading lens — what still blocks *real* (real-money) use

Independent of the above, for completeness — these are known and mostly ADR-tracked, listed
so nothing hides:

1. **Order module still in-process** — ADR-0015 hard gate requires extracting `order` to
   its own JVM before any real broker. Not started; the single biggest structural task.
2. **No authentication** on any endpoint — acceptable on the Pi, a hard precondition for
   any non-localhost deployment (register it).
3. **Swap P&L ledger** still books at the avg-cost approximation (live DV01×100 multiplier);
   the trade-dated seasoned book is the precise *view* beside it, not the ledger of record.
   Documented convention, fine for sim; a real rates book wants the seasoned P&L booked.
4. **No settlement / margin / clearing model** — correct to omit in sim; a real broker
   connection needs at least margin-aware buying power.
5. Finance math itself checks out: exact-decimal end to end, DV01/duration/CTD/curve/VaR
   all have exact-value tests, scenario legs full-revalue with convexity. No numerical
   correctness defect found in this pass.

---

## Recommended order

1. **BLOCKER-1** (fills-table rebuild) — silently corrupts every number after retention.
2. **GAP-2** (persist quarantine) — a safety control a restart disables.
3. **GAP-3** (order/fill id columns) — small, makes the platform operable/debuggable.
4. **GAP-4** (refdata name + tenor attributes; delete the hardcoded maps) — realises the
   "no hardcoded refdata" principle; one schema add + read-through.
5. POLISH items opportunistically.

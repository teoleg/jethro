The desk cannot OPEN a position because its intent is erased faster than it accumulates — the aim is now durable derived state, closing BOTH resets (the per-cycle `retainAll` and the per-process one), which is why last cycle's "persistence would not help" was half right (ADR-0140).

*(Every figure below is read from `/api/risk`, `/api/attribution`, `/api/fusion/targets`, `ops_jvm`, the
report's `recent_orders` and the scorer. None is authored here — invariant 7 / ADR-0016.)*

# Last analysis — 2026-08-05 19:30Z

## Situation

1. **Money.** Total PnL **$-637.53**. **+3.62** since last run; **-22.54** over the last three. The
   heartbeat reads PnL growth **-0.61%** against the **+1.0%** target — `on_track=False`, `stale=True`,
   `underwater=True`. Not bleeding fast, but off target and underwater.
2. **Risk.** Gross **$5,246.10** — **0.3%** of the firm cap, headroom **$1,494,754**. Net **$508.54**,
   **0.1%** of the net cap. `breaker.halted` **false**. This is a **DORMANT** book: the problem is
   undeployed capital, not exposure.
3. **Cause.** The previous change (`e956dcf46`, the completed ADR-0139 revert) scored **❌ BAD** and its
   auto-revert hit a git conflict again. It is deliberately **not** re-reverted: reverting it would
   reinstate ADR-0139, which was itself scored ❌ BAD. The loop does not re-attempt a rejected mechanism.
4. **Danger.** None of the danger kind — no breaker, no cap proximity. `regime.trend` **CHOP**,
   `volRatio` **1.03**.
5. **Order post-mortem.** **Fifth consecutive zero-order window**; the newest row is still the 17:00:28Z
   ADR-0019 auto-hedge. Attribution is **100% market, 0% change**: ALPHA **-600.08**, MACRO **-56.80**,
   HEDGE **+19.35** — all marks on untouched positions.

## Diagnosis

The desk is not choosing to be flat, it is **structurally unable to open**. `insideBuffer` reads **19** of
**20** planned names with `currentQty` **0** for most, while the planner produces real targets throughout
(WMT `targetQty` **-846.588220**, KO **-599.105696**, JPM **139.136530**) and routes against none of them.

Three individually-sound controls compose into a freeze. ADR-0080 gives the aim a time constant of one
whole evidence horizon (`a = 1 − e^(−c/h)` = **0.008298…**), so it reaches only `1 − e^(−t/h)` of its
target after `t`. Against a flat book ADR-0094's band reduces the release condition to exactly
**`|aim|/|target| > 1/|forecast|`** at the live `bufferFraction` **0.10** and `TARGET_ABS` **10.0**, so
time-to-first-order is `t > −h·ln(1 − 1/|f|)` — between roughly **900 s** and **2400 s** at the live
forecasts. And the aim was reset far more often than that, by **two** independent mechanisms:
`aims.keySet().retainAll(planned)` deleted the entire intent of any name absent from a **single** cycle's
plan (membership churns — consecutive snapshots read `instruments` **21** then **20**), and the map was
in-memory against observed process lifetimes of **1344–1439 s**.

Two readings make this diagnostic rather than a story. Six names sharing one rate, one band and one seed
(`currentQty` **0** for each) show `|aim|/|target|` spanning **12.7×** (AAPL **0.017274** … CVX
**0.219300**) — a common clock forces *identical* ratios, so those clocks had been restarted at different
times. And the only two names that opened at all in this window were `fusion entry` on GOOG at
`forecast=6.341` and NVDA at `forecast=-9.609` — the two **lowest** `1/|f|` thresholds seen. Every
currently-planned name sits between **2.1** and **4.7**, and not one of them opens.

**Why last cycle's refutation was half right.** It showed that four of six frozen names had release
requirements inside a single process lifetime and still routed nothing, and concluded persistence would
not help. That correctly kills *persistence alone* — but it tested only the **per-process** reset while
the **per-cycle** `retainAll` was also firing, which is what the 12.7× spread was measuring. Closing
either one alone leaves the freeze; this change closes both.

## Change

**ADR-0140 — the aim is durable derived state.** Absence now **ages** an intent over a window derived by
inverting the ADR-0080 identity (`−1/ln(1−a) = h/c` = **120** cycles = exactly one evidence horizon — no
number introduced), and the map is written through each cycle to `fusion_aim` (V48, `NUMERIC(20,6)`,
`feed_mode`-scoped, derived data only) and restored once per process. No band, width, rate, conviction
floor, edge gate or cap is altered, and the deterministic floor is untouched. A restored aim is never
acted on directly: it is stepped at this cycle's rate and clamped by ADR-0102 into `[flat, current
target]`, so it can never exceed or oppose the current view. Capping the band was ADR-0133 — scored ❌ BAD
— and is **not** re-attempted. `-Pci test` green, five new tests.

**VERIFY-BY next run:** at least one name's `|aim|/|targetQty|` above the `1 − e^(−uptime/3600)` an
in-memory reseed could produce; `insideBuffer` strictly below `instruments` **and** a matching `fusion
entry`/`fusion exit` row in `recent_orders` in the same window; and the `|aim|/|target|` spread across
names collapsing toward a common value.

# System footprint analysis — services, threads, connections, and the freeze

*2026-07-20. A deep look at what actually runs inside the single JVM, prompted by the recurring
whole-server freeze and the (correct) worry that we've accumulated "so many services and connections."
Evidence-based — every number below came from grepping the tree, not memory. The conclusion is that the
freeze is most likely a **contention + resource architecture** problem, not a single bug.*

## 1. Shape: one JVM, 12 Gradle modules

Per ADR-0015 this is a modular monolith — all 12 modules load into ONE `app/` process:

```
common-domain · common-messaging
trading-core:{market-data, algo-engine, risk-pnl, runtime}
modules:{order, reference-data, ui-gateway, finops}
app  (wires everything + hosts the Spring context and the :8080 edge)
```

Module isolation is a compile-time discipline (Gradle + ArchUnit); at runtime it is a single heap, a
single thread pool space, and — critically — a **single set of shared singletons** that everything calls.

## 2. Background threads — 28 lifecycles, ~72 scheduler/thread creation sites

Every one of these owns at least one live thread or repeating timer, all in the same process:

| Cadence | Services on it |
|---|---|
| ~2–5 s | `StrategyLifecycle` (5 s), `RiskDataConsumer`, `TradingCoreLifecycle` (tick loop), `SpikeMonitor`, `MarkPublisher`, `RiskSnapshotPublisher` |
| ~20–60 s | `HypothesisLifecycle` (20 s), `SignalTelemetryResolver` (60 s), `SocialLifecycle` (60 s), `RiskCommentatorLifecycle` (60 s), `FusionLifecycle` (30 s), `ScenarioMonitor`, `RiskLimitMonitor`, `FirmBreakerMonitor`, `MarketHistoryRecorder`, `MarkQuarantineMonitor`, `HedgeLifecycle`, `EodService` |
| minutes | `IndicatorsService` (300 s), `DiscoveryLifecycle` (300 s), Treasury curve (120 s), `TrainingBarsLoader` (once), `HistorySeeder` (once), `LearnedSignalService` (on demand) |
| hourly | `StrategySelector` OOS backtest (60 min) — the heavy one |

Each lifecycle tends to spin its **own** single-thread `ScheduledExecutorService`. That's ~two-dozen
scheduler threads before Spring's web pool, the market-data feed threads, Kafka client threads, and the
Hikari pool. Thread count alone isn't fatal — but it means two-dozen independent things can each block,
and several of them pile onto the same shared locks (below).

## 3. The real hazard: one global lock, ~23 callers

`RiskProjection` is a single bean with **10 `synchronized` methods** (`snapshot`, `positionQuantity`,
`instrumentNetExposure`, `projectedExposure`, …). It is the source of truth for positions/marks, and it
is called from **~23 classes**, including most of the schedulers above AND every UI risk poll:

```
FusionLifecycle(30s) HedgeLifecycle RiskLimitMonitor FirmBreakerMonitor ScenarioMonitor
RiskSnapshotPublisher MarketHistoryRecorder MarkQuarantineMonitor SpikeMonitor StrategyLifecycle(5s)
HypothesisLifecycle(20s) SocialLifecycle(60s) RiskCommentatorLifecycle VarService Dv01Service
SessionConfig MarkPublisher  + RiskController/HedgeController/HistoryController (every UI request)
```

This is the classic monolith choke point. `snapshot()` walks every position and re-marks it **while
holding the lock**. If that walk ever runs long — a GC pause lands mid-snapshot, or the position set is
large — **every other thread that needs risk state parks behind the lock**. From the outside that looks
exactly like "the whole server froze": the tick loop, the UI, the strategy, the hedger, all waiting on
one monitor. A heap stall doesn't have to freeze the JVM directly; it only has to pause the thread
*holding this lock*, and the contention does the rest. That unifies the heap theory and the freeze.

## 4. External connections — many blocking I/O points, each on a scheduler thread

Outbound HTTP/WS clients found: **Ollama** (inference + embeddings), **Finnhub** (news REST + yield
curve REST + trade WebSocket), **Tiingo** (history), **Yahoo** (indicators), **StockTwits**,
**Telegram**, **RSS discovery**, **TreasuryDirect**. Plus **Postgres** (Hikari, max pool = **8**) and
**Redpanda/Kafka** producers+consumers.

Two risks here:
- **Blocking calls on the lifecycle's own thread.** e.g. `RiskCommentatorLifecycle`/`HypothesisLifecycle`
  call Ollama, which takes *seconds* per inference. If an external host is slow or a timeout is long
  (or missing), that scheduler thread is wedged for the duration — and if it grabbed the risk lock
  first (the commentator snapshots, then narrates), it wedges everyone (see §3).
- **Pool starvation.** 8 DB connections shared across ~28 background threads + web threads. A few slow
  queries and threads queue for a connection — another way to look "frozen."

## 5. UI polling — the frontend multiplies backend load, funnelled through the lock

The landing page alone runs **10 poll loops**: `loadRisk`(2 s), `loadTicker`(2 s), `loadMarketState`(5 s),
`loadFeeds`(5 s), `loadHedging`(5 s), `loadEod`(5 s), `loadFusionPanel`(10 s), `loadHistory`(15 s),
`loadVar`(15 s), `loadIndicators`(30 s). `loadRisk` at 2 s hits `RiskController` → the §3 lock. Every
open browser tab adds its own copy of all ten loops. Two or three dashboards left open overnight =
a steady stream of risk-lock acquisitions from the *outside*, on top of the internal schedulers. The UI
is not passive — it is an active load source that competes for the same monitor the tick loop needs.
(58 distinct `/api/*` endpoints are wired across the pages.)

## 6. Verdict on the freeze

No single smoking gun — it's an architecture that **serialises too much through shared singletons under a
tight heap**, so any one slow actor (a GC pause, a slow Ollama call, a big snapshot) stalls the holder of
a hot lock and everything queues. The hourly OOS backtest is the most likely *trigger* (heaviest
allocator → longest GC pauses), which is why the heap guard helped — but it treats the symptom. The
disease is the fan-out in §3 plus the blocking I/O in §4.

## 7. Recommendations, ranked by leverage

1. **Cache the risk snapshot; make readers lock-free.** One publisher (`RiskSnapshotPublisher`, already
   on a timer) computes an **immutable** `ConsolidatedRisk` once per second and stores it in a
   `volatile`; every other caller (UI, hedge, fusion, monitors, commentary) reads that reference with NO
   lock and NO recompute. Writers still serialise on fills, but the ~23 readers stop contending. This is
   the single highest-leverage change — it dissolves §3 and most of §5.
2. **Every external call gets a short timeout and runs off the lifecycle's critical path.** Never hold a
   shared lock across an Ollama/Finnhub/Tiingo call. Bound connect+read timeouts (a few seconds) so a
   slow host can't wedge a scheduler thread.
3. **Consolidate schedulers onto a small shared pool.** Replace ~two-dozen private single-thread
   executors with one bounded `ScheduledThreadPoolExecutor` (say 4–6 threads); fewer threads, and a
   slow task can't monopolise since others share the pool. (Keep the tick loop separate.)
4. **Right-size heap vs the backtest.** Either run at `HEAP=1g`+ (the guard then rarely trips) or shrink
   the hourly OOS job (fewer seeds/ticks, or a leaner value type than `BigDecimal` in the inner loop).
5. **UI: back off idle polling.** `poll.js` already pauses hidden tabs; also widen `loadRisk` from 2 s →
   5 s and coalesce the landing calls — the dashboard doesn't need 2 s risk refreshes.
6. **A watchdog** (separate from the fixes): a max-priority thread that dumps all stacks + heap/GC when a
   heartbeat stalls, so the *next* freeze is self-diagnosing instead of inferred.

## 8. What this is NOT

The module count (12) and the service count are fine for a modular monolith — the problem isn't "too many
features," it's that too many of them **synchronously share one lock and one small heap**, and several do
**blocking I/O on threads that hold shared state**. Fixing §7.1–2 addresses the freeze without removing a
single feature.

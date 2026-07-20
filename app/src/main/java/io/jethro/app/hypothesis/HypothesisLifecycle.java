package io.jethro.app.hypothesis;

import io.jethro.app.backtest.BacktestResult;
import io.jethro.app.backtest.BacktestService;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.order.NewOrder;
import io.jethro.order.OrderService;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.hypothesis.HypothesisContext;
import io.jethro.trading.algo.hypothesis.NarrativeItem;
import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the hypothesis pipeline (ADR-0022) on a cadence: gather the context (marks +
 * narrative + portfolio), ask the LLM for structured theses, run each through the
 * deterministic quant evaluator, and surface the admissible candidates on the attention
 * feed for human execution (ADR-0018 — no auto-submit in this slice). The market path never
 * depends on this; a model outage just means no new hypotheses (invariant 7).
 */
public final class HypothesisLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HypothesisLifecycle.class);

    private final io.jethro.trading.algo.hypothesis.HypothesisGenerator generator;
    private final HypothesisEvaluator evaluator;
    private final NarrativeFeed narrativeFeed;
    private final BacktestService backtest;
    private final TradingCoreLifecycle tradingCore;
    private final RiskProjection risk;
    private final InstrumentRefSource refs;
    private final InstrumentNameSource names; // refdata display names (GAP-4)
    private final AttentionFeed feed;
    private final SseBroadcaster sse;
    private final HypothesisProperties props;
    private final AutonomyEnvelope envelope;
    private final OrderService orderService; // nullable: null → human-in-loop only
    private final HypothesisRecordStore recordStore;
    private final io.jethro.app.risk.TradingHaltSwitch halt; // firm breaker (ADR-0027)
    private final HypothesisMemory memory; // semantic de-dup (ADR-0035); DISABLED when RAG is off

    private static final int EXECUTED_CAP = 50;
    private static final int LEDGER_CAP = 60;
    private static final int MAX_NARRATIVE_TO_MODEL = 6; // cap prompt size (slow-box inference)
    private static final int ALREADY_PROPOSED_TO_MODEL = 12; // live calls fed back for idempotency
    // Same news won't re-fire a hypothesis inside this window; genuinely new news (new id) always
    // fires. Wall-clock — the hypothesis cycle and news timestamps are real time (ADR-0022).
    private static final long IDEMPOTENCY_WINDOW_MILLIS = 4 * 60 * 60 * 1_000L;
    private final HypothesisIdempotency idempotency = new HypothesisIdempotency(IDEMPOTENCY_WINDOW_MILLIS);
    private final Deque<HypothesisRecord> executed = new ArrayDeque<>(); // sticky, newest first
    // The event ledger: (instrument|thesis) → event, insertion-ordered (chronological). A new
    // narrative for the same security is a new event; a repeat updates in place. Never overwritten
    // by the next cycle the way a "current proposals" view was — so it matches the attention feed.
    private final java.util.LinkedHashMap<String, HypothesisEvent> ledger = new java.util.LinkedHashMap<>();
    private final Set<String> active = new HashSet<>();
    private final Map<String, Long> lastAutoExec = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private volatile Set<String> autoTradedIds = Set.of();
    private volatile ScheduledExecutorService scheduler;

    public HypothesisLifecycle(io.jethro.trading.algo.hypothesis.HypothesisGenerator generator,
                               HypothesisEvaluator evaluator, NarrativeFeed narrativeFeed,
                               BacktestService backtest, TradingCoreLifecycle tradingCore, RiskProjection risk,
                               InstrumentRefSource refs, AttentionFeed feed, SseBroadcaster sse,
                               HypothesisProperties props, OrderService orderService,
                               HypothesisRecordStore recordStore,
                               io.jethro.app.risk.TradingHaltSwitch halt,
                               InstrumentNameSource names, HypothesisMemory memory) {
        this.generator = generator;
        this.memory = memory != null ? memory : HypothesisMemory.DISABLED;
        this.evaluator = evaluator;
        this.narrativeFeed = narrativeFeed;
        this.backtest = backtest;
        this.envelope = new AutonomyEnvelope(props.autonomyOrDefault());
        this.orderService = orderService;
        this.recordStore = recordStore;
        this.halt = halt;
        this.tradingCore = tradingCore;
        this.risk = risk;
        this.refs = refs;
        this.feed = feed;
        this.sse = sse;
        this.props = props;
        this.names = names != null ? names : InstrumentNameSource.NONE;
    }

    // ADR-0055 phase 1: optional health telemetry — an observer the hypothesis layer never depends on.
    private volatile io.jethro.app.signal.SignalTelemetry signalTelemetry;
    private volatile io.jethro.app.fusion.ForecastRegistry forecastRegistry; // ADR-0055 phase 4 (shadow)

    public void setSignalTelemetry(io.jethro.app.signal.SignalTelemetry signalTelemetry) {
        this.signalTelemetry = signalTelemetry;
    }

    public void setForecastRegistry(io.jethro.app.fusion.ForecastRegistry forecastRegistry) {
        this.forecastRegistry = forecastRegistry;
    }

    /** The hypothesis event ledger, newest first — every distinct thesis the model proposed,
     *  retained (not just the current cycle), for the /api/hypotheses surface. */
    public List<HypothesisEvent> ledger() {
        synchronized (ledger) {
            List<HypothesisEvent> out = new ArrayList<>(ledger.values());
            java.util.Collections.reverse(out); // insertion order is oldest→newest
            return out;
        }
    }

    /** Live calls fed back to the model for idempotency (ADR-0022): recent, not-yet-scored ledger
     *  theses as "INSTRUMENT DIRECTION: thesis", newest first, capped — so the model can see what
     *  it already proposed and not repeat it on unchanged news. */
    private List<String> activeCallsForPrompt() {
        List<String> out = new ArrayList<>();
        for (HypothesisEvent e : ledger()) { // newest first
            if (e.outcome() != null) {
                continue; // already scored/closed — no longer a live call
            }
            out.add(e.instrumentId() + " " + e.direction() + ": " + e.thesis());
            if (out.size() >= ALREADY_PROPOSED_TO_MODEL) {
                break;
            }
        }
        return out;
    }

    /** Re-embeds persisted scored hypotheses into the RAG outcome memory at boot (ADR-0035) —
     *  durability by rebuild, no vector store. Best-effort; runs off the boot thread. */
    private void rebuildOutcomeMemory(List<HypothesisRecord> persisted) {
        int rebuilt = 0;
        for (HypothesisRecord r : persisted) {
            if (!r.isOpen() && r.outcome() != null) {
                memory.rememberOutcome(r.instrumentId(), r.direction(), r.thesis(), r.outcome(),
                        r.outcomePnl() != null ? r.outcomePnl().toPlainString() : "");
                rebuilt++;
            }
        }
        if (rebuilt > 0) {
            log.info("RAG: rebuilt outcome memory from {} persisted scored hypotheses (ADR-0035)", rebuilt);
        }
    }

    /** The recent directional mix of LIVE (not-yet-scored) calls (ADR-0036) — surfaced and fed
     *  back to the model so it self-corrects a one-sided book. Descriptive, never a risk limit. */
    public record DirectionBalance(int longs, int shorts, String skew) {
    }

    /** RAG health for the ops view (ADR-0035): indexed chunks, embedding dim, hit/miss counters. */
    public HypothesisMemory.RagStats ragStats() {
        return memory.stats();
    }

    public DirectionBalance directionBalance() {
        int longs = 0;
        int shorts = 0;
        for (HypothesisEvent e : ledger()) {
            if (e.outcome() != null) {
                continue; // scored/closed — not a live call
            }
            if ("BUY".equals(e.direction())) {
                longs++;
            } else if ("SELL".equals(e.direction())) {
                shorts++;
            }
        }
        return new DirectionBalance(longs, shorts, HypothesisBalance.skew(longs, shorts));
    }

    /** The balance line fed to the model (ADR-0036), or "" when there are too few calls to matter. */
    private String directionBalanceForPrompt() {
        DirectionBalance b = directionBalance();
        return HypothesisBalance.promptLine(b.longs(), b.shorts());
    }

    /** Records/updates a hypothesis in the ledger: same (instrument, thesis) updates in place
     *  (keeping its first-seen time); a new thesis is a new event; identical repeats don't pile up. */
    private void updateLedger(List<HypothesisEvaluator.Evaluated> evaluated, Set<String> autoTraded, long now) {
        synchronized (ledger) {
            for (HypothesisEvaluator.Evaluated e : evaluated) {
                Hypothesis h = e.hypothesis();
                String key = h.instrumentId() + "|" + h.thesis();
                HypothesisEvent prev = ledger.get(key);
                long ts = prev != null ? prev.timestampMillis() : now;
                boolean auto = autoTraded.contains(h.instrumentId()) || (prev != null && prev.autoTraded());
                var bt = e.backtest();
                // Why this thesis did NOT auto-execute (the gate it missed), so "only one order"
                // is self-explanatory. Null when it auto-traded or autonomy is off.
                String autonomyReason = null;
                if (!auto) {
                    if (!props.autonomyOrDefault().enabledOrDefault() || orderService == null) {
                        autonomyReason = "autonomy off — human review";
                    } else if (!deterministicallySupported(e)) {
                        // ADR-0049: the deterministic backtest is the hard gate, applied before the
                        // envelope — so "no AI order" on an unvalidated name is self-explanatory.
                        autonomyReason = "gated (ADR-0049): deterministic backtest does not support this name — AI never orders unvalidated";
                    } else {
                        BigDecimal mult = refs.find(h.instrumentId())
                                .map(InstrumentRef::multiplier).orElse(BigDecimal.ONE);
                        AutonomyEnvelope.Decision d = envelope.decide(e, mult, trackRecord());
                        autonomyReason = d.allowed() ? "cooldown or already open" : d.reason();
                    }
                }
                ledger.put(key, new HypothesisEvent(ts, h.instrumentId(), h.direction().name(),
                        h.conviction().name(), h.thesis(), e.verdict().name(), auto,
                        bt != null ? bt.supports() : null,
                        bt != null ? bt.pnl().toPlainString() : null,
                        bt != null ? bt.trades() : null, e.note(), autonomyReason,
                        prev != null ? prev.outcome() : null,          // a scored outcome is final
                        prev != null ? prev.outcomePnl() : null));
            }
            while (ledger.size() > LEDGER_CAP) {
                var it = ledger.keySet().iterator();
                it.next();
                it.remove(); // evict the eldest event
            }
        }
    }

    @Override
    public void start() {
        // Reload persisted executed hypotheses so they stay on the page across restarts, and
        // seed the ledger with them (oldest-first, so the chronological order holds).
        List<HypothesisRecord> persisted = recordStore.recent(EXECUTED_CAP);
        synchronized (executed) {
            for (HypothesisRecord r : persisted) {
                executed.addLast(r); // recent() is newest-first
            }
        }
        synchronized (ledger) {
            for (int i = persisted.size() - 1; i >= 0; i--) {
                HypothesisRecord r = persisted.get(i);
                ledger.putIfAbsent(r.instrumentId() + "|" + r.thesis(), new HypothesisEvent(
                        r.timestampMillis(), r.instrumentId(), r.direction(), r.conviction(), r.thesis(),
                        "ADMISSIBLE", true, r.backtestSupported(), null, null,
                        "auto-executed on the AI sleeve — order " + (r.orderStatus() == null ? "" : r.orderStatus()),
                        null, r.outcome(),
                        r.outcomePnl() != null ? r.outcomePnl().toPlainString() : null));
            }
        }
        // Rebuild the RAG outcome memory (ADR-0035) from the persisted scored records — the vectors
        // are derived data, so re-embedding the durable Postgres records at boot gives durability
        // with no vector store. Off the boot thread (embeddings hit Ollama) and best-effort.
        if (memory.enabled()) {
            Thread rebuild = new Thread(() -> rebuildOutcomeMemory(persisted), "rag-memory-rebuild");
            rebuild.setDaemon(true);
            rebuild.start();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hypothesis");
            t.setDaemon(true);
            return t;
        });
        long interval = props.intervalSecondsOrDefault();
        scheduler.scheduleWithFixedDelay(this::runOnce, interval, interval, TimeUnit.SECONDS);
        var auto = props.autonomyOrDefault();
        if (auto.enabledOrDefault() && orderService != null) {
            log.warn("BOUNDED AUTONOMY ON (ADR-0022), HARD-GATED by ADR-0049: a thesis auto-submits a "
                            + "SIMULATED order ONLY when the deterministic OOS backtest supports the name AND it "
                            + "clears the envelope (admissible, conviction ≥ {}, notional ≤ {}, cooldown {}s, "
                            + "whitelist {}). The AI never originates an order without deterministic edge. "
                            + "Never against a real broker.",
                    auto.minConvictionOrDefault(), auto.maxOrderNotionalOrDefault().toPlainString(),
                    auto.cooldownSecondsOrDefault(),
                    auto.whitelistOrEmpty().isEmpty() ? "(all)" : auto.whitelistOrEmpty());
        }
        log.info("hypothesis layer started: every {}s, up to {} per cycle (local SLM, {})",
                interval, props.maxPerCycleOrDefault(),
                auto.enabledOrDefault() && orderService != null ? "bounded autonomy" : "human-in-loop");
    }

    private void runOnce() {
        long startNanos = System.nanoTime();
        try {
            var runtime = tradingCore.runtime();
            if (runtime == null) {
                return;
            }
            long now = System.currentTimeMillis();

            List<HypothesisContext.MarkView> markViews = new ArrayList<>();
            Map<String, BigDecimal> priceMap = new HashMap<>();
            Set<String> tradable = new HashSet<>();
            List<String> singleNames = new ArrayList<>();
            for (var mark : runtime.markCache().snapshot()) {
                Optional<InstrumentRef> ref = refs.find(mark.instrumentId());
                if (ref.isEmpty()) {
                    continue; // curve pseudo-instruments (USD.SOFR.*) aren't tradable — skip
                }
                tradable.add(mark.instrumentId());
                priceMap.put(mark.instrumentId(), mark.price());
                String assetClass = ref.get().assetClass();
                // Tell the model WHAT each instrument is (asset class + description), so it reasons
                // about the real instrument instead of inventing an issuer for the ticker.
                markViews.add(new HypothesisContext.MarkView(
                        mark.instrumentId(), assetClass, ref.get().currency(),
                        InstrumentDescriptions.of(names.displayName(mark.instrumentId()), assetClass),
                        mark.price().toPlainString(), mark.stale()));
                if ("EQUITY".equals(assetClass) || "FUTURE".equals(assetClass)) {
                    singleNames.add(mark.instrumentId());
                }
            }
            if (tradable.isEmpty()) {
                return; // nothing to reason about yet
            }

            // Horizon-expiry sweep BEFORE generation (ADR-0027): exits and scoring must happen
            // even when the model is slow or down — the market path never waits on inference.
            settleExpired(now, priceMap);

            List<NarrativeItem> narrative = narrativeFeed.poll(tradingCore.regime(), singleNames, now);
            // Cap the narrative fed to the model — every extra headline is more prompt to eval,
            // which on a slow box dominates inference time. Keep the most recent handful.
            if (narrative.size() > MAX_NARRATIVE_TO_MODEL) {
                narrative = narrative.subList(narrative.size() - MAX_NARRATIVE_TO_MODEL, narrative.size());
            }
            List<HypothesisContext.PortfolioLine> portfolio = new ArrayList<>();
            for (var p : risk.snapshot(now).positions()) {
                if (p.quantity().signum() != 0) {
                    portfolio.add(new HypothesisContext.PortfolioLine(p.bookId(), p.instrumentId(),
                            p.quantity().toPlainString(), p.unrealizedPnl().toPlainString()));
                }
            }

            List<Hypothesis> hypotheses;
            try {
                // Tell the model which calls are already live (ADR-0022 idempotency) so it stops
                // re-proposing the same trade on unchanged news; the deterministic guard below is
                // the backstop. Plus RAG memory (ADR-0035): its own outcomes on setups like today's
                // news, retrieved semantically — empty/no-op when RAG is off.
                hypotheses = generator.generate(new HypothesisContext(
                        markViews, narrative, portfolio, tradable,
                        activeCallsForPrompt(), memory.recallSimilar(narrative), directionBalanceForPrompt()));
                consecutiveFailures.set(0);
            } catch (InferenceException e) {
                long failures = consecutiveFailures.incrementAndGet();
                if (failures == 1 || failures % 10 == 0) {
                    log.warn("hypothesis generation inference failed ({} consecutive): {}",
                            failures, e.getMessage());
                }
                return;
            }

            // OUT-OF-SAMPLE edge gate (ADR-0027): the strategy is backtested on K seeds disjoint
            // from the live tape and aggregated by median — "supported" now means net-positive
            // on a majority of independent paths, not on the very tape the sim replays (which
            // was in-sample self-confirmation). A failure just omits the annotation.
            Map<String, BacktestResult.InstrumentResult> backtestByInstrument = new HashMap<>();
            if (!hypotheses.isEmpty()) {
                try {
                    backtestByInstrument.putAll(backtest.oosByInstrument(
                            props.backtestTicksOrDefault(), props.oosSeedsOrDefault()));
                } catch (Exception e) {
                    log.debug("hypothesis backtest annotation skipped: {}", e.toString());
                }
            }

            List<HypothesisEvaluator.Evaluated> evaluated = new ArrayList<>(hypotheses.size());
            for (Hypothesis h : hypotheses) {
                evaluated.add(evaluator.evaluate(h, priceMap, backtestByInstrument));
            }
            // Idempotency (ADR-0022 follow-up): drop calls whose triggering news already fired a
            // hypothesis — the model re-proposes the same trade every cycle while a headline sits
            // in the narrative window. Ledger writes and autonomy see only genuinely new triggers;
            // cards (surface) keep working off the full set so a still-valid card doesn't blink.
            List<HypothesisEvaluator.Evaluated> fresh = new ArrayList<>(evaluated.size());
            int suppressed = 0;
            for (HypothesisEvaluator.Evaluated e : evaluated) {
                // Deterministic floor (news id / text) + semantic layer (ADR-0035): the same story
                // reworded across sources is still a repeat. Semantic is best-effort — off/failed,
                // isSemanticDuplicate is false and the deterministic guard governs.
                if (idempotency.isDuplicate(e.hypothesis(), now) || memory.isSemanticDuplicate(e.hypothesis())) {
                    suppressed++;
                    continue;
                }
                idempotency.markFired(e.hypothesis(), now); // also de-dups within this cycle
                memory.remember(e.hypothesis());
                fresh.add(e);
                // ADR-0055 phase 1: record the model's directional call for health telemetry — scored
                // later by realised forward return. The deduped (fresh) call, so one event = one signal.
                if (signalTelemetry != null) {
                    BigDecimal mark = priceMap.get(e.hypothesis().instrumentId());
                    if (mark != null) {
                        signalTelemetry.record("hypothesis", e.hypothesis().instrumentId(),
                                e.hypothesis().direction(), mark);
                    }
                }
                if (forecastRegistry != null) {
                    forecastRegistry.submitHypothesis(e.hypothesis().instrumentId(),
                            e.hypothesis().direction(), e.hypothesis().conviction()); // ADR-0055 phase 4
                }
            }
            Set<String> autoTraded = runAutonomy(fresh, now);
            autoTradedIds = autoTraded;
            updateLedger(fresh, autoTraded, now); // append/update the event ledger (new triggers only)
            surface(evaluated, now, autoTraded);

            int admissible = (int) fresh.stream()
                    .filter(e -> e.verdict() == HypothesisEvaluator.Verdict.ADMISSIBLE).count();
            if (suppressed > 0) {
                log.debug("hypothesis idempotency: suppressed {} repeat trigger(s) on unchanged news", suppressed);
            }
            // Log every cycle so "no hypotheses" is explained: the model ran (Ollama up) but
            // returned 0 usable structured theses, vs the layer being disabled or the model down.
            // The elapsed time is dominated by the local SLM inference — it's the real floor on how
            // often the cycle can run (scheduleWithFixedDelay waits interval AFTER this completes).
            long tookMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("hypotheses: model proposed {}, {} admissible, {} auto-traded (regime {}) — took {}ms",
                    evaluated.size(), admissible, autoTraded.size(), tradingCore.regime(), tookMs);
        } catch (Throwable t) {
            log.warn("hypothesis cycle failed: {}", t.toString());
        }
    }

    /**
     * Bounded-autonomy pass (ADR-0022), hard-gated by ADR-0049: a news-driven thesis may become a
     * SIMULATED order (ADR-0019) ONLY when the deterministic OOS backtest independently supports the
     * name — the model never originates an order. The deterministic gate is applied FIRST; the risk
     * envelope (admissibility/conviction/track-record/cap) then applies on top. Off unless autonomy
     * is enabled and an order path exists. @return instruments auto-traded this cycle.
     */
    private Set<String> runAutonomy(List<HypothesisEvaluator.Evaluated> evaluated, long now) {
        Set<String> traded = new HashSet<>();
        var auto = props.autonomyOrDefault();
        if (!auto.enabledOrDefault() || orderService == null) {
            return traded;
        }
        if (halt.isHalted()) {
            return traded; // firm breaker (ADR-0027): no NEW autonomy entries while halted
        }
        long cooldownMillis = auto.cooldownSecondsOrDefault() * 1_000;
        AutonomyEnvelope.TrackRecord track = trackRecord();
        for (HypothesisEvaluator.Evaluated e : evaluated) {
            if (!deterministicallySupported(e)) {
                continue; // ADR-0049 HARD GATE: no deterministic edge → never an AI order
            }
            BigDecimal multiplier = refs.find(e.hypothesis().instrumentId())
                    .map(InstrumentRef::multiplier).orElse(BigDecimal.ONE);
            AutonomyEnvelope.Decision decision = envelope.decide(e, multiplier, track);
            if (!decision.allowed()) {
                continue; // outside the envelope — stays a human-review card (reason on the ledger)
            }
            String instrument = e.hypothesis().instrumentId();
            Long last = lastAutoExec.get(instrument);
            if (last != null && now - last < cooldownMillis) {
                continue;
            }
            if (submitAuto(e, decision.quantity(), decision.probation(), now)) {
                traded.add(instrument);
            }
        }
        return traded;
    }

    /**
     * ADR-0049 hard gate: an AI/news thesis may become an order ONLY when the DETERMINISTIC OOS
     * backtest independently supports the name — a positive, cost-honest median edge over a majority
     * of out-of-sample paths ({@link HypothesisEvaluator.Backtest#supports()}). Fail-CLOSED: a null
     * backtest (name never measured, or the OOS run was skipped this cycle) is NOT support, so
     * nothing auto-trades without a completed deterministic measurement. The model never originates
     * an order; the deterministic model's measured edge is what admits the trade.
     */
    static boolean deterministicallySupported(HypothesisEvaluator.Evaluated e) {
        HypothesisEvaluator.Backtest bt = e.backtest();
        return bt != null && bt.supports();
    }

    /** The AI sleeve's measured record: scored outcomes + summed mark-to-mark P&L (ADR-0027). */
    AutonomyEnvelope.TrackRecord trackRecord() {
        int scored = 0;
        BigDecimal pnl = BigDecimal.ZERO;
        synchronized (executed) {
            for (HypothesisRecord r : executed) {
                if (!r.isOpen()) {
                    scored++;
                    pnl = pnl.add(r.outcomePnl() != null ? r.outcomePnl() : BigDecimal.ZERO);
                }
            }
        }
        return new AutonomyEnvelope.TrackRecord(scored, pnl);
    }

    private boolean submitAuto(HypothesisEvaluator.Evaluated e, BigDecimal quantity,
                               boolean probation, long now) {
        Hypothesis h = e.hypothesis();
        try {
            var command = new NewOrder("hypo:" + h.instrumentId() + ":" + UUID.randomUUID(),
                    e.book(), h.instrumentId(), h.direction(), OrderType.MARKET, quantity, null);
            var order = orderService.submit(command);
            lastAutoExec.put(h.instrumentId(), now);
            recordExecuted(e.withQuantity(quantity), now, order.orderId(), String.valueOf(order.status()));
            log.warn("AUTONOMY auto-executed {} {} {} → {} on {} ({}){} — thesis: {}",
                    h.direction(), quantity.toPlainString(), h.instrumentId(), order.status(),
                    e.book(), order.orderId(),
                    probation ? " [PROBATION size — building the track record]" : "", h.thesis());
            return true;
        } catch (Exception ex) {
            log.warn("autonomy auto-execute of {} failed: {}", h.instrumentId(), ex.getMessage());
            return false;
        }
    }

    /** Instruments auto-traded by autonomy in the last cycle (for the UI). */
    public Set<String> autoTradedIds() {
        return autoTradedIds;
    }

    /** Persists an executed hypothesis (with entry mark + horizon expiry, ADR-0027) and pins
     *  it to the sticky list (newest first, capped). */
    private void recordExecuted(HypothesisEvaluator.Evaluated e, long now, String orderId, String orderStatus) {
        Hypothesis h = e.hypothesis();
        Boolean backtestSupported = e.backtest() != null ? e.backtest().supports() : null;
        long expiresAt = now + props.horizonSecondsFor(h.horizon().name()) * 1_000;
        HypothesisRecord record = HypothesisRecord.open(orderId, now, h.instrumentId(),
                h.direction().name(), h.horizon().name(), h.conviction().name(), h.thesis(),
                e.book(), e.quantity(), backtestSupported, orderId, orderStatus,
                e.price(), expiresAt);
        try {
            recordStore.save(record); // durable; NOOP when persistence is off
        } catch (Exception ex) {
            log.warn("could not persist executed hypothesis {}: {}", orderId, ex.toString());
        }
        synchronized (executed) {
            executed.addFirst(record);
            while (executed.size() > EXECUTED_CAP) {
                executed.removeLast();
            }
        }
    }

    /**
     * Horizon-expiry sweep (ADR-0027): for every OPEN executed hypothesis past its expiry —
     * (1) CLOSE the AI-sleeve position it opened (the sleeve's missing exit: an opposite-side
     * MARKET order, clamped to what the book still holds, so a manual close is never fought);
     * (2) SCORE the call mark-to-mark (entry vs the current mark) and persist WIN/LOSS/FLAT;
     * (3) reflect the outcome on the ledger so the panel shows it. Skips (and retries next
     * cycle) when the instrument has no live mark — never scores against a stale guess.
     */
    private void settleExpired(long now, Map<String, BigDecimal> marks) {
        List<HypothesisRecord> due;
        synchronized (executed) {
            due = executed.stream()
                    .filter(r -> r.isOpen() && r.expiresAtMillis() > 0 && r.expiresAtMillis() <= now)
                    .toList();
        }
        for (HypothesisRecord r : due) {
            BigDecimal exitMark = marks.get(r.instrumentId());
            if (exitMark == null || r.entryPrice() == null) {
                continue; // no live mark (or a pre-outcome-era record) — retry next cycle
            }
            closeSleevePosition(r, now);
            BigDecimal multiplier = refs.find(r.instrumentId())
                    .map(InstrumentRef::multiplier).orElse(BigDecimal.ONE);
            var score = HypothesisOutcomes.score(r.direction(), r.entryPrice(), exitMark,
                    r.quantity(), multiplier);
            try {
                recordStore.markOutcome(r.id(), score.outcome(), score.pnl(), exitMark,
                        java.time.Instant.ofEpochMilli(now));
            } catch (Exception ex) {
                log.warn("could not persist outcome for {}: {}", r.id(), ex.toString());
            }
            HypothesisRecord scored = r.scored(score.outcome(), score.pnl(), exitMark);
            // RAG memory (ADR-0035): remember the scored call so future prompts recall it.
            memory.rememberOutcome(r.instrumentId(), r.direction(), r.thesis(),
                    score.outcome(), score.pnl().toPlainString());
            synchronized (executed) {
                executed.removeIf(x -> x.id().equals(r.id()));
                executed.addFirst(scored);
            }
            synchronized (ledger) {
                String key = r.instrumentId() + "|" + r.thesis();
                HypothesisEvent event = ledger.get(key);
                if (event != null) {
                    ledger.put(key, event.withOutcome(score.outcome(), score.pnl().toPlainString()));
                }
            }
            log.info("HYPOTHESIS {}: {} {} {} scored {} ({}) at horizon expiry — entry {} exit {}",
                    score.outcome(), r.direction(), r.quantity().toPlainString(), r.instrumentId(),
                    score.outcome(), score.pnl().toPlainString(),
                    r.entryPrice().toPlainString(), exitMark.toPlainString());
        }
    }

    /** Closes what the AI sleeve still holds of this record's position (clamped, opposite side). */
    private void closeSleevePosition(HypothesisRecord r, long now) {
        if (orderService == null) {
            return; // human-in-loop mode: score the call, the human manages the position
        }
        BigDecimal held = risk.positionQuantity(r.book(), r.instrumentId());
        if (held.signum() == 0) {
            return; // already flat (manual close or an earlier expiry) — nothing to unwind
        }
        // Close at most this record's quantity, never crossing through flat.
        BigDecimal qty = r.quantity().min(held.abs());
        Side side = held.signum() > 0 ? Side.SELL : Side.BUY;
        try {
            var command = new NewOrder("hypo-exit:" + r.id() + ":" + UUID.randomUUID(),
                    r.book(), r.instrumentId(), side, OrderType.MARKET, qty, null);
            var order = orderService.submit(command);
            log.info("AI-sleeve exit at horizon expiry: {} {} {} → {} ({})",
                    side, qty.toPlainString(), r.instrumentId(), order.status(), order.orderId());
        } catch (Exception ex) {
            log.warn("AI-sleeve exit of {} failed: {}", r.instrumentId(), ex.getMessage());
        }
    }

    /** Executed (autonomy) hypotheses, newest first — persisted, sticky across cycles/restarts. */
    public List<HypothesisRecord> executed() {
        synchronized (executed) {
            return List.copyOf(executed);
        }
    }

    /** Measured hit-rate/expectancy per conviction (ADR-0027) — what the model's labels are
     *  actually worth, and the data the autonomy min-conviction dial should be set from. */
    public record ConvictionStats(String conviction, int total, int open, int wins, int losses,
                                  int flat, BigDecimal outcomePnl) {
    }

    public List<ConvictionStats> outcomeStats() {
        Map<String, int[]> counts = new java.util.LinkedHashMap<>(); // [total, open, win, loss, flat]
        Map<String, BigDecimal> pnl = new java.util.LinkedHashMap<>();
        synchronized (executed) {
            for (HypothesisRecord r : executed) {
                String c = r.conviction() == null ? "UNKNOWN" : r.conviction();
                int[] k = counts.computeIfAbsent(c, x -> new int[5]);
                k[0]++;
                if (r.isOpen()) {
                    k[1]++;
                } else {
                    switch (r.outcome()) {
                        case "WIN" -> k[2]++;
                        case "LOSS" -> k[3]++;
                        default -> k[4]++;
                    }
                    pnl.merge(c, r.outcomePnl() != null ? r.outcomePnl() : BigDecimal.ZERO, BigDecimal::add);
                }
            }
        }
        List<ConvictionStats> out = new ArrayList<>();
        counts.forEach((c, k) -> out.add(new ConvictionStats(
                c, k[0], k[1], k[2], k[3], k[4], pnl.getOrDefault(c, BigDecimal.ZERO))));
        return out;
    }

    /** Admissible candidates become INFO cards on the attention feed; stale ones resolve. */
    private void surface(List<HypothesisEvaluator.Evaluated> evaluated, long now, Set<String> autoTraded) {
        Set<String> current = new HashSet<>();
        for (HypothesisEvaluator.Evaluated e : evaluated) {
            if (e.verdict() != HypothesisEvaluator.Verdict.ADMISSIBLE) {
                continue;
            }
            String id = "hypothesis:" + e.hypothesis().instrumentId();
            current.add(id);
            feed.upsert(card(id, e, now, autoTraded.contains(e.hypothesis().instrumentId())));
        }
        boolean changed = false;
        for (String id : Set.copyOf(active)) {
            if (!current.contains(id)) {
                feed.resolve(id);
                active.remove(id);
                changed = true;
            }
        }
        for (String id : current) {
            if (active.add(id)) {
                changed = true;
            }
        }
        if (changed) {
            sse.broadcast("attention", feed.snapshot());
        }
    }

    private static AttentionFeed.AttentionItem card(String id, HypothesisEvaluator.Evaluated e, long now,
                                                    boolean autoTraded) {
        Hypothesis h = e.hypothesis();
        String dir = h.direction() == Side.BUY ? "LONG" : "SHORT";
        String title = (autoTraded ? "Auto-traded (hypothesis): " : "Hypothesis: ")
                + dir + " " + h.instrumentId() + " (" + h.conviction() + ")";
        String backtestNote = "";
        if (e.backtest() != null) {
            backtestNote = e.backtest().supports()
                    ? " Backtest supports it (+" + e.backtest().pnl().toPlainString()
                        + " on " + e.backtest().trades() + " trades)."
                    : " Backtest does NOT support it (strategy not profitable on this name).";
        }
        boolean detSupported = e.backtest() != null && e.backtest().supports();
        String tail = autoTraded
                ? " Auto-submitted a SIMULATED order — deterministic backtest supports the name (ADR-0049) and it cleared the risk envelope (ADR-0019/0022)."
                : detSupported
                    ? " Deterministic backtest supports it — review and execute on the Orders ticket."
                    : " Deterministic backtest does NOT support this name — information only (ADR-0049), not order-eligible.";
        String body = h.thesis() + " — Quant sized " + e.quantity().toPlainString() + " " + h.instrumentId()
                + " on " + e.book() + " (" + h.horizon() + " horizon); pre-trade check passed." + backtestNote + tail;
        return new AttentionFeed.AttentionItem(id, now, AttentionFeed.Severity.INFO,
                "hypothesis", title, body, "/orders.html");
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
            log.info("hypothesis layer stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}

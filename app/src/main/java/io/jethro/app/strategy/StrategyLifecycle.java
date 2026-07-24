package io.jethro.app.strategy;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.order.NewOrder;
import io.jethro.order.OrderService;
import io.jethro.domain.OrderType;
import io.jethro.trading.algo.strategy.Strategy;
import io.jethro.trading.algo.strategy.TradeSignal;
import io.jethro.domain.Side;
import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Runs the toy momentum strategy (step 8) on a cadence and turns its signals into
 * advisory suggestions on the attention feed (ADR-0018 candidate → guardrail → surface):
 * read marks → strategy signals → size to target notional → deterministic pre-trade
 * guardrail against the configured book → surface the admissible ones. Never routes an
 * order (invariant 7); a human acts on the suggestion from the Orders ticket.
 */
public final class StrategyLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StrategyLifecycle.class);

    private final Strategy strategy;
    private final TradingCoreLifecycle tradingCore;
    private final InstrumentRefSource refs;
    private final PreTradeGuardrail guardrail;
    private final RiskProjection risk;
    private final RiskLimitSource limits;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;
    private final StrategyProperties props;
    private final StrategyControl control; // ADR-0052: effective (override-or-config) tuning, live
    private final OrderService orderService; // nullable: null → suggestions only
    private final io.jethro.app.risk.TradingHaltSwitch halt; // firm breaker (ADR-0027)
    private final io.jethro.app.risk.InstrumentVolSource vols; // measured daily vol (sizing)
    private final io.jethro.app.risk.PortfolioCorrelationSource correlations; // covariance-aware sizing
    private final io.jethro.app.order.MeasuredAdvSource measuredAdv; // nullable: live ADV for the liquidity cap (ADR-0033)
    private final io.jethro.trading.algo.strategy.VolatilityRegime volRegime; // ADR-0051: price-derived risk-off sensing

    private static final int ACTIVITY_CAP = 50;
    private final java.util.Deque<StrategyActivity> activity = new java.util.ArrayDeque<>(); // newest first

    /** Why the book is (or isn't) trading right now — a snapshot of the last cycle (ADR-0052). Makes
     *  "not a lot of activity" diagnosable: how many names cleared the signal threshold, and for each
     *  that did, the reason it did or didn't turn into a trade. */
    public record Diag(long atMillis, int universe, int freshMarks, int staleMarks, int belowThreshold,
                       String regime, String regimeScale, boolean autoExecuting, boolean halted,
                       int signals, int executed, int exited, java.util.List<DiagSignal> outcomes) {
    }

    /** One signalling instrument and what became of it this cycle. */
    public record DiagSignal(String instrumentId, String side, String outcome, String detail) {
    }

    private volatile Diag lastDiag = new Diag(0, 0, 0, 0, 0, "—", "1", false, false, 0, 0, 0, java.util.List.of());

    private static final long HEARTBEAT_CYCLES = 24; // ~2 min at a 5s cadence
    // In-flight guard on exits: a close is submitted synchronously but the fill only shrinks
    // the projection after it round-trips Kafka, so suppress re-closing the same position
    // for this window to avoid a burst of duplicate closes before the projection catches up.
    private static final long EXIT_COOLDOWN_MILLIS = 15_000;

    private final Set<String> active = new HashSet<>();
    private final Map<String, Long> lastAutoExec = new ConcurrentHashMap<>();
    private final Map<String, Long> lastExit = new ConcurrentHashMap<>();
    private final Set<String> deriskedBooks = new HashSet<>();
    private long cycles;
    private boolean throttledActive;
    private volatile ScheduledExecutorService scheduler;

    public StrategyLifecycle(Strategy strategy, TradingCoreLifecycle tradingCore,
                             InstrumentRefSource refs, PreTradeGuardrail guardrail, RiskProjection risk,
                             RiskLimitSource limits, AttentionFeed feed, SseBroadcaster sse,
                             StrategyProperties props, StrategyControl control, OrderService orderService,
                             io.jethro.app.risk.TradingHaltSwitch halt,
                             io.jethro.app.risk.InstrumentVolSource vols,
                             io.jethro.app.risk.PortfolioCorrelationSource correlations,
                             io.jethro.app.order.MeasuredAdvSource measuredAdv,
                             io.jethro.trading.algo.strategy.VolatilityRegime volRegime) {
        this.strategy = strategy;
        this.measuredAdv = measuredAdv;
        this.tradingCore = tradingCore;
        this.refs = refs;
        this.guardrail = guardrail;
        this.risk = risk;
        this.limits = limits;
        this.feed = feed;
        this.sse = sse;
        this.props = props;
        this.control = control;
        this.orderService = orderService;
        this.halt = halt;
        this.vols = vols;
        this.correlations = correlations;
        this.volRegime = volRegime;
    }

    // ADR-0055 phase 1: optional health telemetry — wired post-construction so it stays an observer
    // the strategy never depends on (null when signals telemetry is disabled).
    private volatile io.jethro.app.signal.SignalTelemetry signalTelemetry;
    private volatile io.jethro.app.fusion.ForecastRegistry forecastRegistry; // ADR-0055 phase 4 (shadow)

    public void setSignalTelemetry(io.jethro.app.signal.SignalTelemetry signalTelemetry) {
        this.signalTelemetry = signalTelemetry;
    }

    public void setForecastRegistry(io.jethro.app.fusion.ForecastRegistry forecastRegistry) {
        this.forecastRegistry = forecastRegistry;
    }

    // ADR-0055 phase 5: when the fusion layer is the sole order origin, the strategy's OWN auto-exec
    // (entries and managed exits) stands down — it becomes a forecast source, not an order source.
    private volatile boolean fusionRoutingActive;

    public void setFusionRoutingActive(boolean active) {
        this.fusionRoutingActive = active;
    }

    private boolean autoExecuting() {
        return control.autoExecute() && orderService != null && !fusionRoutingActive;
    }

    /** The SENSED volatility regime (ADR-0051) — CALM/ELEVATED/UNKNOWN, price-derived (never a sim
     *  label). For the landing-page badge. */
    public String volatilityRegime() {
        return volRegime.regime().name();
    }

    /** Market vol ÷ its baseline on the last sizing cycle (ADR-0051), for the badge tooltip. */
    public String volatilityRatio() {
        return volRegime.marketVolRatio().setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "strategy");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce,
                props.intervalSeconds(), props.intervalSeconds(), TimeUnit.SECONDS);
        log.info("{} strategy started: every {}s, lookback {}, threshold {}σ (floor {}bps), default book {} "
                        + "(dials live-tunable — ADR-0052)",
                strategy.name(), props.intervalSeconds(), control.lookback(), control.thresholdSigmas(),
                control.minSignalBps().toPlainString(), props.book());
        if (autoExecuting()) {
            log.warn("AUTO-EXECUTE ON (ADR-0019): strategy signals auto-submit SIMULATED orders "
                    + "(routed by asset class {}, default {}; cooldown {}s). Never enable against a real broker.",
                    props.bookByClass(), props.book(), control.autoCooldownSeconds());
        } else if (control.autoExecute()) {
            log.warn("strategy auto-execute is on but no order service available — suggestions only");
        }
    }

    private void runOnce() {
        try {
            var runtime = tradingCore.runtime();
            if (runtime == null) {
                return;
            }
            List<Strategy.Observation> observations = new ArrayList<>();
            int stale = 0;
            var volumeStats = runtime.volumeStats(); // ADR-0033: relative volume for signal confirmation
            for (var mark : runtime.markCache().snapshot()) {
                double relativeVolume = volumeStats != null
                        ? volumeStats.relativeVolume(mark.instrumentId()) : 1.0;
                observations.add(new Strategy.Observation(
                        mark.instrumentId(), mark.price(), mark.stale(), relativeVolume));
                if (mark.stale()) {
                    stale++;
                }
            }
            int fresh = observations.size() - stale;

            long now = System.currentTimeMillis();
            // Exit pass first (the risk-reducing half of the loop, ADR-0019): stop-loss,
            // take-profit and book de-risk close positions before we look for new entries.
            int exited = manageOpenPositions(now);
            // Regime-aware sizing (ADR-0051): shrink new entries when the market is in an elevated-
            // volatility (risk-off) state — SENSED from the marks we just observed (price-derived),
            // never the sim's regime label, so it behaves identically in sim, live and replay. Exits
            // above are unaffected — you can always reduce.
            volRegime.update(observations);
            var volState = volRegime.regime();
            BigDecimal regimeScale =
                    volState == io.jethro.trading.algo.strategy.VolatilityRegime.Regime.ELEVATED
                            ? control.regimeVolatileScale() : BigDecimal.ONE;
            String regime = volState.name();
            Set<String> current = new HashSet<>();
            int signals = 0;
            int suppressed = 0;
            int oversized = 0;
            int atPosition = 0;
            int shortsBlocked = 0;
            int executed = 0;
            String sampleReason = null;
            java.util.List<DiagSignal> outcomes = new ArrayList<>();
            for (TradeSignal signal : strategy.evaluate(observations)) {
                signals++;
                // ADR-0055 phase 1: record this source's directional call for health telemetry — scored
                // later by realised forward return. Observational only; never gates or sizes this signal.
                if (signalTelemetry != null) {
                    signalTelemetry.record(signal.kind(), signal.instrumentId(), signal.side(), signal.price());
                }
                if (forecastRegistry != null) {
                    forecastRegistry.submitStrategy(signal); // ADR-0055: current forecast for the fusion layer
                }
                String book = bookFor(signal.instrumentId()); // route by asset class, not all to one book
                Optional<BigDecimal> sized = size(signal, regimeScale);
                if (sized.isEmpty()) {
                    oversized++; // unsizeable under the cap, or standing aside in this regime
                    outcomes.add(new DiagSignal(signal.instrumentId(), signal.side().name(), "unsizeable",
                            regimeScale.signum() == 0 ? "standing aside — regime entry scale is 0"
                                    : "one unit exceeds the order-notional cap"));
                    continue;
                }
                // Position-aware (quant-engine phase 5): once the book already holds the
                // target position in this instrument, don't pile on in the same direction —
                // opposite-direction signals still pass (they REDUCE risk).
                BigDecimal held = risk.instrumentNetExposure(book, signal.instrumentId());
                boolean sameDirection = (held.signum() > 0) == (signal.side() == Side.BUY);
                if (held.signum() != 0 && sameDirection
                        && held.abs().compareTo(control.maxPositionNotional()) >= 0) {
                    atPosition++;
                    outcomes.add(new DiagSignal(signal.instrumentId(), signal.side().name(), "at-position",
                            "book already holds the max position in this direction — only reducing signals pass"));
                    continue;
                }
                BigDecimal quantity = sized.get();
                // Long-only guard (default): a SELL may only REDUCE an existing long, never
                // open or extend a short. Clamp the reducing order so it stops at flat.
                if (!control.allowShort() && signal.side() == Side.SELL) {
                    BigDecimal heldQty = risk.positionQuantity(book, signal.instrumentId());
                    if (heldQty.signum() <= 0) {
                        shortsBlocked++; // nothing to reduce — a short would be opened; skip
                        outcomes.add(new DiagSignal(signal.instrumentId(), signal.side().name(), "short-blocked",
                                "SELL with no long to reduce — shorts are off (allow-short)"));
                        continue;
                    }
                    if (quantity.compareTo(heldQty) > 0) {
                        quantity = heldQty; // reduce to flat, never cross into a short
                    }
                }
                BigDecimal signed = signal.side().signed(quantity);
                Optional<String> rejection =
                        guardrail.rejectionReason(book, signal.instrumentId(), signed);
                if (rejection.isPresent()) {
                    suppressed++;
                    sampleReason = rejection.get();
                    outcomes.add(new DiagSignal(signal.instrumentId(), signal.side().name(), "blocked",
                            "pre-trade guardrail: " + rejection.get()));
                    continue; // not admissible under the book's limits — don't suggest it
                }
                // Firm breaker (ADR-0027): a halt stops NEW entries; the exit pass above is
                // risk-REDUCING and keeps running — a breaker must never trap an open book.
                boolean traded = autoExecuting() && !halt.isHalted()
                        && maybeAutoExecute(signal, book, quantity, now);
                if (traded) {
                    executed++;
                }
                // Diagnostic: distinguish executed / cooling / breaker-held / advisory-only so the
                // panel shows WHY a cleared signal did or didn't trade (ADR-0052).
                String outcome;
                if (traded) {
                    outcome = "executed";
                } else if (!autoExecuting()) {
                    outcome = "suggested — auto-execute off";
                } else if (halt.isHalted()) {
                    outcome = "held — firm breaker halt";
                } else if (withinCooldown(signal.instrumentId(), now)) {
                    outcome = "cooldown — re-entry throttled";
                } else {
                    outcome = "suggested";
                }
                outcomes.add(new DiagSignal(signal.instrumentId(), signal.side().name(), outcome, signal.rationale()));
                String id = "signal:" + signal.instrumentId();
                current.add(id);
                feed.upsert(toItem(signal, book, quantity, now, traded));
            }
            lastDiag = new Diag(now, observations.size(), fresh, stale, Math.max(0, fresh - signals),
                    regime, regimeScale.toPlainString(), autoExecuting(), halt.isHalted(),
                    signals, executed, exited, java.util.List.copyOf(outcomes));
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

            // Surface "why did it go quiet" on the feed the user already watches: every
            // signal blocked by the guardrail (e.g. the book hit an exposure limit).
            if (suppressed > 0 && current.isEmpty()) {
                if (!throttledActive) {
                    feed.upsert(new AttentionFeed.AttentionItem("strategy-throttled", now,
                            AttentionFeed.Severity.WARN, "strategy-throttled",
                            "Strategy idling — a book is at a risk limit",
                            suppressed + " signal(s) blocked by the pre-trade guardrail: " + sampleReason
                                    + ". Auto-trading resumes when the book's exposure frees up.", "/books.html"));
                    throttledActive = true;
                    changed = true;
                }
            } else if (throttledActive) {
                feed.resolve("strategy-throttled");
                throttledActive = false;
                changed = true;
            }
            if (changed) {
                sse.broadcast("attention", feed.snapshot());
            }

            // Heartbeat: the log always shows the strategy is alive and why it is/ isn't trading.
            if (++cycles % HEARTBEAT_CYCLES == 0 || signals > 0 || suppressed > 0 || exited > 0) {
                log.info("strategy [{}{}]: {} fresh marks ({} stale), {} signals, {} unsizeable, {} at-position, "
                                + "{} short-blocked, {} suppressed by limits, {} auto-executed, {} exited{}",
                        regime, regimeScale.compareTo(BigDecimal.ONE) < 0 ? " ×" + regimeScale.toPlainString() : "",
                        fresh, stale, signals, oversized, atPosition, shortsBlocked, suppressed, executed, exited,
                        fresh == 0 ? "  — NO FRESH MARKS (feed may be stale)" : "");
            }
        } catch (Throwable t) {
            // Never let a Throwable silently cancel the scheduled task — that would stop the
            // strategy for good with no further logging. Catch, log, keep the cadence alive.
            log.warn("strategy run failed: {}", t.toString());
        }
    }

    /** Routes an instrument to the book that fits its asset class (falls back to the default). */
    private String bookFor(String instrumentId) {
        return props.bookFor(refs.find(instrumentId).map(InstrumentRef::assetClass).orElse(null));
    }

    /**
     * Sizes a suggestion to the target notional: qty = targetNotional / (price × multiplier),
     * rounded down. Never rounds up: if even one unit exceeds the max-order-notional cap
     * (one ES contract ≈ $272k vs a $25k target), the signal is skipped — empty result.
     */
    private Optional<BigDecimal> size(TradeSignal signal, BigDecimal regimeScale) {
        if (regimeScale.signum() == 0) {
            return Optional.empty(); // standing aside this regime (e.g. VOLATILE scale 0)
        }
        Optional<InstrumentRef> ref = refs.find(signal.instrumentId());
        if (ref.isEmpty()) {
            return Optional.empty(); // not in the instrument master (e.g. a curve quote) — never trade it
        }
        BigDecimal multiplier = ref.map(InstrumentRef::multiplier).orElse(BigDecimal.ONE);
        BigDecimal notionalPerUnit = signal.price().multiply(multiplier);
        String assetClass = ref.map(InstrumentRef::assetClass).orElse(null);
        // Vol-TARGETED sizing (risk budget, the primary path): notional = riskBudgetDaily /
        // σ_daily measured from recorded daily closes (same history as VaR), capped at the
        // per-class order cap — every position carries a comparable expected daily P&L swing.
        // Until the instrument has measured vol: the signal-window vol scale (clamp(refσ/σ,
        // 0.5, 2) of targetNotional) — the disclosed warm-up fallback, never a guess.
        BigDecimal notionalTarget;
        var dailyVol = vols.dailyVol(signal.instrumentId());
        if (dailyVol.isPresent() && dailyVol.get().signum() > 0) {
            // Covariance-aware when ρ_ip is measured: size to the position's CONTRIBUTION to
            // portfolio vol (a duplicate of the book sizes like standalone; a real diversifier
            // earns more, floored at ρ=0.25). Standalone vol-targeting until then, disclosed.
            var rho = correlations.correlationToPortfolio(signal.instrumentId());
            notionalTarget = rho.isPresent()
                    ? io.jethro.app.risk.VolTargeting.marginalNotionalFor(
                            control.riskBudgetDaily(), dailyVol.get(), rho.get(),
                            control.maxOrderNotionalFor(assetClass))
                    : io.jethro.app.risk.VolTargeting.notionalFor(
                            control.riskBudgetDaily(), dailyVol.get(), control.maxOrderNotionalFor(assetClass));
        } else {
            double z = Math.abs(signal.zScore());
            double sigmaBps = Double.isFinite(z) && z > 1e-9
                    ? Math.abs(signal.changeBps().doubleValue()) / z
                    : control.volReferenceBps();
            double scale = Math.max(0.5, Math.min(2.0, control.volReferenceBps() / Math.max(sigmaBps, 1e-9)));
            notionalTarget = control.targetNotional().multiply(BigDecimal.valueOf(scale));
        }
        // Regime scale on top: risk-off in VOLATILE/RISK_OFF/INFLATION_SHOCK.
        notionalTarget = notionalTarget.multiply(regimeScale);
        // Liquidity cap (ADR-0033): never size above a small fraction of the instrument's live
        // measured ADV — below the execution participation cap, so the sized order passes. Only
        // when measured ADV exists (warm sim / not a live feed); vol/notional caps stand otherwise.
        if (measuredAdv != null) {
            var advUsd = measuredAdv.advUsd(signal.instrumentId());
            if (advUsd.isPresent()) {
                BigDecimal liquidityCap = advUsd.get()
                        .multiply(BigDecimal.valueOf(control.liquidityCapAdvFraction()));
                if (liquidityCap.signum() > 0 && notionalTarget.compareTo(liquidityCap) > 0) {
                    notionalTarget = liquidityCap;
                }
            }
        }
        BigDecimal qty = notionalTarget.divide(notionalPerUnit, 0, RoundingMode.DOWN);
        if (qty.signum() <= 0) {
            // Cap is per asset class: one Treasury contract (~$110k) is a legitimate order
            // for a rates book even though it dwarfs the equity-sized default cap.
            BigDecimal cap = control.maxOrderNotionalFor(ref.map(InstrumentRef::assetClass).orElse(null));
            if (notionalPerUnit.compareTo(cap) > 0) {
                return Optional.empty(); // one unit already blows the order cap — unsizeable
            }
            qty = BigDecimal.ONE;
        }
        return Optional.of(qty);
    }

    /**
     * Auto-submits a signal as a simulated MARKET order through the normal order path
     * (ADR-0019: sim only, guardrail re-checked in OrderService), throttled by cooldown.
     * @return true if an order was submitted this cycle.
     */
    /** True when this instrument auto-executed within the (live) cooldown window — shared by the
     *  execute path and the diagnostic so the panel can say "cooldown" for the exact same reason. */
    private boolean withinCooldown(String instrumentId, long now) {
        Long last = lastAutoExec.get(instrumentId);
        return last != null && now - last < control.autoCooldownSeconds() * 1_000;
    }

    private boolean maybeAutoExecute(TradeSignal signal, String book, BigDecimal qty, long now) {
        if (withinCooldown(signal.instrumentId(), now)) {
            return false; // still cooling down for this instrument
        }
        try {
            var command = new NewOrder("auto:" + signal.instrumentId() + ":" + UUID.randomUUID(),
                    book, signal.instrumentId(), signal.side(), OrderType.MARKET, qty, null);
            var order = orderService.submit(command);
            lastAutoExec.put(signal.instrumentId(), now);
            recordActivity(new StrategyActivity(now, StrategyActivity.ENTRY, signal.instrumentId(), book,
                    signal.side().name(), qty.toPlainString(), signal.rationale(), String.valueOf(order.status())));
            log.info("auto-executed {} {} {} → {} on {} ({})",
                    signal.side(), qty.toPlainString(), signal.instrumentId(), order.status(), book, order.orderId());
            return true;
        } catch (Exception e) {
            log.warn("auto-execute of {} failed: {}", signal.instrumentId(), e.getMessage());
            return false;
        }
    }

    /**
     * The risk-reducing half of the loop (ADR-0019): closes positions the strategy manages
     * when a per-position stop-loss/take-profit trips, or when the whole book is over its
     * loss cap (de-risk backstop — what makes a floored book actually unwind instead of the
     * alarm latching forever). Closing orders REDUCE risk, so they always pass the guardrail
     * even on a loss-breached book. Only runs when auto-executing (nothing to submit else).
     *
     * <p>Scope: only books the strategy routes into ({@link #managedBooks()}). It does not
     * reach into books the strategy never trades. @return number of close orders submitted.
     */
    private int manageOpenPositions(long now) {
        if (!autoExecuting()) {
            return 0;
        }
        ConsolidatedRisk snapshot = risk.snapshot(now);
        Set<String> managed = managedBooks();

        // Managed books that still hold risk AND are at/over their max-loss cap → flatten.
        // Gated on live exposure so the card clears once the book is flat (the day's realized
        // loss legitimately keeps the separate loss ALERT up — that happened, it's honest).
        Set<String> flatten = new HashSet<>();
        if (control.deriskOnLossCap()) {
            for (ConsolidatedRisk.Group g : snapshot.byBook()) {
                if (!managed.contains(g.key()) || g.grossExposure().signum() == 0) {
                    continue;
                }
                BigDecimal cap = limits.limitsFor(g.key()).maxLossPnl();
                // COMPREHENSIVE (actual loss incl. FX translation) — a loss cap guards real
                // money, not the clean trading figure (ADR-0037).
                BigDecimal loss = g.comprehensivePnl();
                if (cap != null && cap.signum() > 0 && loss.signum() < 0
                        && loss.negate().compareTo(cap) >= 0) {
                    flatten.add(g.key());
                }
            }
        }

        BigDecimal stop = control.stopLossPctOrNull();
        BigDecimal takeProfit = control.takeProfitPctOrNull();
        int closed = 0;
        for (PositionRisk p : snapshot.positions()) {
            if (p.quantity().signum() == 0 || !managed.contains(p.bookId())) {
                continue;
            }
            String reason = flatten.contains(p.bookId())
                    ? "book over loss cap — de-risking"
                    : priceExitReason(p, stop, takeProfit).orElse(null);
            if (reason != null && closePosition(p, reason, now)) {
                closed++;
            }
        }

        // De-risk cards: shown while a book is being flattened, resolved once it's flat —
        // so the user SEES the floored book being worked down, not just a latched alarm.
        boolean changed = false;
        for (String book : Set.copyOf(deriskedBooks)) {
            if (!flatten.contains(book)) {
                feed.resolve("derisk:" + book);
                deriskedBooks.remove(book);
                changed = true;
            }
        }
        for (String book : flatten) {
            feed.upsert(new AttentionFeed.AttentionItem("derisk:" + book, now,
                    AttentionFeed.Severity.WARN, "strategy-derisk",
                    book + " over loss cap — auto de-risking",
                    "The strategy is submitting risk-reducing orders to bring " + book
                            + " back under its loss limit (simulated, ADR-0019).", "/books.html"));
            if (deriskedBooks.add(book)) {
                changed = true;
            }
        }
        if (changed) {
            sse.broadcast("attention", feed.snapshot());
        }
        return closed;
    }

    /**
     * The stop-loss / take-profit reason for a price-quoted position, or empty to hold.
     * Unrealized return is measured in the position's PnL direction: a long profits when
     * mark &gt; cost, a short when mark &lt; cost. The contract multiplier cancels (both the
     * move and the basis carry it), so it's {@code (mark − cost)/|cost|} signed by the qty
     * sign. Package-visible + pure for exact-value testing (finance-math rule).
     *
     * <p>Price-quoted classes only: a SWAP is quoted as a par RATE (a 0.8% move on a 4%
     * rate is ~3bp — a different scale), so a pct stop/TP would be incoherent; swaps are
     * managed by book de-risk and opposite signals until a bp-denominated rates stop exists.
     */
    static Optional<String> priceExitReason(PositionRisk p, BigDecimal stop, BigDecimal takeProfit) {
        if (!p.hasMark() || p.avgCost().signum() == 0 || "SWAP".equals(p.assetClass())
                || (stop == null && takeProfit == null)) {
            return Optional.empty();
        }
        BigDecimal ret = p.mark().subtract(p.avgCost())
                .divide(p.avgCost().abs(), 8, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(p.quantity().signum()));
        if (stop != null && ret.compareTo(stop.negate()) <= 0) {
            return Optional.of("stop-loss " + pct(ret));
        }
        if (takeProfit != null && ret.compareTo(takeProfit) >= 0) {
            return Optional.of("take-profit " + pct(ret));
        }
        return Optional.empty();
    }

    /** Submits a risk-reducing MARKET order that flattens the position, throttled by a short
     *  in-flight window so the async fill isn't double-closed. @return true if submitted. */
    private boolean closePosition(PositionRisk p, String reason, long now) {
        String key = p.bookId() + "|" + p.instrumentId();
        Long last = lastExit.get(key);
        if (last != null && now - last < EXIT_COOLDOWN_MILLIS) {
            return false; // a close is already in flight for this position
        }
        Side side = p.quantity().signum() > 0 ? Side.SELL : Side.BUY; // opposite side reduces
        BigDecimal qty = p.quantity().abs();
        try {
            var command = new NewOrder("exit:" + p.instrumentId() + ":" + UUID.randomUUID(),
                    p.bookId(), p.instrumentId(), side, OrderType.MARKET, qty, null);
            var order = orderService.submit(command);
            lastExit.put(key, now);
            recordActivity(new StrategyActivity(now, StrategyActivity.EXIT, p.instrumentId(), p.bookId(),
                    side.name(), qty.toPlainString(), reason, String.valueOf(order.status())));
            log.info("exit {} {} {} ({}) → {} on {} ({})",
                    side, qty.toPlainString(), p.instrumentId(), reason, order.status(), p.bookId(), order.orderId());
            return true;
        } catch (Exception e) {
            log.warn("exit of {} on {} failed: {}", p.instrumentId(), p.bookId(), e.getMessage());
            return false;
        }
    }

    /** Records a strategy action (newest first, capped) for the UI's quant-actions view. */
    private void recordActivity(StrategyActivity a) {
        synchronized (activity) {
            activity.addFirst(a);
            while (activity.size() > ACTIVITY_CAP) {
                activity.removeLast();
            }
        }
    }

    /** Why the book is (or isn't) trading right now — last cycle's snapshot (ADR-0052). */
    public Diag diagnostics() {
        return lastDiag;
    }

    /** Recent deterministic-strategy actions (entries + exits with reasons), newest first. */
    public java.util.List<StrategyActivity> recentActivity() {
        synchronized (activity) {
            return java.util.List.copyOf(activity);
        }
    }

    /** The books the strategy routes into (default + per-class) — the only ones it manages. */
    private Set<String> managedBooks() {
        Set<String> books = new HashSet<>();
        if (props.book() != null) {
            books.add(props.book());
        }
        if (props.bookByClass() != null) {
            books.addAll(props.bookByClass().values());
        }
        return books;
    }

    private static String pct(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private AttentionFeed.AttentionItem toItem(TradeSignal s, String book, BigDecimal qty, long now, boolean executed) {
        String action = s.side() + " " + qty.toPlainString() + " " + s.instrumentId();
        String title = (executed ? "Auto-traded: " : "Signal: ") + action;
        String tail = executed
                ? "Auto-submitted a SIMULATED order (ADR-0019)."
                : "Pre-trade risk check passed — review on the Orders ticket.";
        String body = (executed ? "Auto-" + s.side() + " " : "Suggested " + s.side() + " ")
                + qty.toPlainString() + " " + s.instrumentId()
                + " @ " + s.price().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " → " + book + ". " + s.rationale() + ". " + tail;
        return new AttentionFeed.AttentionItem("signal:" + s.instrumentId(), now,
                AttentionFeed.Severity.INFO, "strategy-signal", title, body, "/orders.html");
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}

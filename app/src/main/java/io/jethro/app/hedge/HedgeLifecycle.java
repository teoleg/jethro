package io.jethro.app.hedge;

import io.jethro.app.risk.TradingHaltSwitch;
import io.jethro.app.risk.VarService;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.messaging.FeedMode;
import io.jethro.messaging.Provenance;
import io.jethro.order.LastPriceCache;
import io.jethro.order.NewOrder;
import io.jethro.order.OrderService;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * AUTO hedging (ADR-0039): when the advisor is in AUTO and an axis carries net exposure above its
 * rebalance floor, this submits the sized hedge-to-flat as a simulated MARKET order through the
 * ordinary order path —
 * the pre-trade gate and ADV slicer apply, and it is hard-gated to {@code feedMode == SIM} (ADR-0019)
 * and suspended while the firm halt switch is tripped. A per-axis cooldown stops a re-hedge before
 * the fill has repriced the book. OFF/ADVISE do nothing here (the panel still shows the proposal).
 */
public final class HedgeLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HedgeLifecycle.class);

    private final HedgeAdvisor advisor;
    private final ObjectProvider<VarService> varService;
    private final ObjectProvider<InstrumentRefSource> refs;
    private final ObjectProvider<LastPriceCache> prices;
    private final ObjectProvider<OrderService> orderService;
    private final ObjectProvider<TradingHaltSwitch> haltSwitch;
    private final ObjectProvider<io.jethro.trading.riskpnl.RiskProjection> projection;
    private final String hedgeBook;
    private final long cooldownMillis;
    private final long intervalSeconds;
    private final Map<String, Long> lastHedge = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;

    public HedgeLifecycle(HedgeAdvisor advisor, ObjectProvider<VarService> varService,
                          ObjectProvider<InstrumentRefSource> refs, ObjectProvider<LastPriceCache> prices,
                          ObjectProvider<OrderService> orderService, ObjectProvider<TradingHaltSwitch> haltSwitch,
                          ObjectProvider<io.jethro.trading.riskpnl.RiskProjection> projection,
                          String hedgeBook, long cooldownSeconds, long intervalSeconds) {
        this.advisor = advisor;
        this.varService = varService;
        this.refs = refs;
        this.prices = prices;
        this.orderService = orderService;
        this.haltSwitch = haltSwitch;
        this.projection = projection;
        this.hedgeBook = hedgeBook;
        this.cooldownMillis = cooldownSeconds * 1_000;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hedge-auto");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("AUTO-HEDGE lifecycle started (mode read live; SIM-gated; book={}, cooldown={}s)",
                hedgeBook, cooldownMillis / 1000);
    }

    private void runOnce() {
        try {
            if (advisor.mode() != HedgeAdvisor.Mode.AUTO) {
                return; // OFF/ADVISE: the panel still shows proposals, but nothing auto-trades
            }
            if (Provenance.mode() != FeedMode.SIM) {
                return; // ADR-0019: auto-execution is sim-only
            }
            OrderService os = orderService.getIfAvailable();
            VarService vs = varService.getIfAvailable();
            if (os == null || vs == null) {
                return;
            }
            TradingHaltSwitch halt = haltSwitch.getIfAvailable();
            if (halt != null && halt.isHalted()) {
                return; // firm breaker tripped — no new auto risk (manual still open)
            }
            InstrumentRefSource rf = refs.getIfAvailable();
            LastPriceCache pc = prices.getIfAvailable();
            Predicate<String> isEquity = id -> rf != null
                    && rf.find(id).map(r -> "EQUITY".equalsIgnoreCase(r.assetClass())).orElse(false);
            Function<String, Optional<BigDecimal>> priceOf = id ->
                    pc != null ? pc.lastPrice(new InstrumentId(id)) : Optional.empty();
            Function<String, Optional<BigDecimal>> betaOf = id -> rf == null ? Optional.empty()
                    : rf.find(id).map(io.jethro.trading.riskpnl.InstrumentRef::hedgeBeta).filter(b -> b != null);
            // The held hedge (ADR-0039's h): the HEDGE book's proxy position. Without this
            // feedback the loop re-submits the full hedge every cooldown (review P1-1) — so if
            // the projection isn't available, we cannot know what we hold and must not trade.
            var proj = projection.getIfAvailable();
            if (proj == null) {
                return;
            }
            BigDecimal held = proj.positionQuantity(hedgeBook, advisor.equityProxyId());

            HedgeAdvisor.Snapshot snap = advisor.evaluate(
                    vs.covarianceSnapshot(), vs.exposuresUsd(), isEquity, priceOf, betaOf, held);
            long now = System.currentTimeMillis();
            for (HedgeAdvisor.Axis axis : snap.axes()) {
                if (!axis.hedging() || !axis.hedgeRecommended() || axis.hedgeQuantity() == null
                        || axis.hedgeQuantity().signum() <= 0) {
                    continue;
                }
                Long last = lastHedge.get(axis.axis());
                if (last != null && now - last < cooldownMillis) {
                    continue; // cooling down — let the last hedge fill and reprice first
                }
                Side side = "SELL".equals(axis.hedgeSide()) ? Side.SELL : Side.BUY;
                NewOrder cmd = new NewOrder("hedge:" + axis.axis() + ":" + UUID.randomUUID(),
                        hedgeBook, axis.proxyId(), side, OrderType.MARKET, axis.hedgeQuantity(), null);
                var order = os.submit(cmd);
                lastHedge.put(axis.axis(), now);
                log.info("AUTO-HEDGE {}: {} {} {} → {} on {} ({}) [ρ²={}]", axis.axis(), side,
                        axis.hedgeQuantity().toPlainString(), axis.proxyId(), order.status(), hedgeBook,
                        order.orderId(), axis.effectiveness());
            }
        } catch (Exception e) {
            log.warn("auto-hedge cycle failed: {}", e.getMessage());
        }
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }
}

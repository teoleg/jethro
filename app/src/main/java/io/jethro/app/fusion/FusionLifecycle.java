package io.jethro.app.fusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ADR-0055 fusion loop, running in SHADOW MODE (phase 4): on a cadence it reads every source's fresh
 * forecast, combines them into one target per instrument, and computes the netted order delta — the
 * whole "combine all sources before any order decision" pipeline — but it does NOT place orders. The
 * target book is surfaced so it can be watched against the live sources and the placeholder dials tuned
 * before any rerouting is switched on (per ADR-0019 that switch is sim-only, and gated on Oleg setting
 * the dials). Live routing (phase 4b/5) will send each delta through the existing ADR-0049/envelope
 * gates; until then {@code routeOrders} only logs intent. Observational — no order, no risk number
 * (ADR-0016 / invariant 7).
 */
public final class FusionLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FusionLifecycle.class);

    /** The latest fused target book — routing is always false in phase 4 (shadow). */
    public record TargetBook(long atMillis, boolean routing, int instruments, List<FusionPlanner.Target> targets) {
        static TargetBook empty() {
            return new TargetBook(0, false, 0, List.of());
        }
    }

    private final ForecastRegistry registry;
    private final Function<String, BigDecimal> priceFor;
    private final Supplier<Map<String, BigDecimal>> positionsSupplier;
    private final FusionWeights weights;
    private final FusionPlanner.Params params;
    private final boolean routeOrders;
    private final FusionExecutor executor; // null ⇒ shadow only (no order path available)
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;

    private volatile TargetBook lastBook = TargetBook.empty();
    private Future<?> task;

    public FusionLifecycle(ForecastRegistry registry, Function<String, BigDecimal> priceFor,
                           Supplier<Map<String, BigDecimal>> positionsSupplier, FusionWeights weights,
                           FusionPlanner.Params params, boolean routeOrders, FusionExecutor executor,
                           ScheduledExecutorService scheduler, long intervalSeconds) {
        this.registry = registry;
        this.priceFor = priceFor;
        this.positionsSupplier = positionsSupplier;
        this.weights = weights;
        this.params = params;
        this.executor = executor;
        this.scheduler = scheduler;
        this.routeOrders = routeOrders && executor != null;
        this.intervalSeconds = Math.max(5, intervalSeconds);
    }

    /** True when this loop is actually placing orders (route-orders set AND an order path is wired). */
    public boolean live() {
        return routeOrders;
    }

    public void start() {
        task = scheduler.scheduleWithFixedDelay(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("fusion loop started (every {}s) — ADR-0055 {}", intervalSeconds,
                routeOrders ? "LIVE (sim-only): the sole order origin, gated by ADR-0049/guardrail/breaker"
                        : "SHADOW MODE (computes the target book, places NO orders)");
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            Map<String, List<Forecast>> forecasts = registry.byInstrument(now);
            Map<String, BigDecimal> positions = positionsSupplier.get();
            List<FusionPlanner.Target> targets = FusionPlanner.plan(forecasts, weights::weightFor, priceFor,
                    id -> positions.getOrDefault(id, BigDecimal.ZERO), params);
            lastBook = new TargetBook(now, routeOrders, targets.size(), targets);
            if (routeOrders) {
                int routed = 0;
                for (FusionPlanner.Target t : targets) {
                    if (t.deltaQty().signum() == 0) {
                        continue; // inside the no-trade band — nothing to do
                    }
                    if (executor.route(t.instrument(), t.deltaQty()).routed()) {
                        routed++;
                    }
                }
                if (routed > 0) {
                    log.info("fusion: routed {} sole-origin delta order(s) this cycle (sim)", routed);
                }
            }
        } catch (Exception e) {
            log.debug("fusion tick failed: {}", e.toString());
        }
    }

    public TargetBook book() {
        return lastBook;
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}

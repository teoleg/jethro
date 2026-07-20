package io.jethro.app.risk;

import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The firm max-drawdown circuit breaker (ADR-0027): deterministic, dumb on purpose. Every
 * cycle it tracks the peak firm total P&L and, when the drawdown from peak reaches
 * {@code jethro.risk.max-firm-drawdown}, trips the {@link TradingHaltSwitch} — halting ALL
 * auto-execution (strategy entries + AI autonomy; risk-REDUCING exits and manual orders stay
 * allowed) — and raises an ALERT on the attention feed (deterministic floor, ADR-0017).
 * Operator reset only; if the condition still holds after a reset it re-trips next cycle.
 * The peak seeds from persisted daily equity so a restart cannot forget the high-water mark.
 */
public final class FirmBreakerMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FirmBreakerMonitor.class);
    private static final long PERIOD_SECONDS = 10;
    static final String CARD_ID = "breaker:firm";

    private final RiskProjection projection;
    private final BigDecimal maxDrawdown; // nullable: breaker disabled
    private final TradingHaltSwitch halt;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;

    private volatile BigDecimal peak = BigDecimal.ZERO;
    private volatile ScheduledExecutorService scheduler;

    public FirmBreakerMonitor(RiskProjection projection, BigDecimal maxDrawdown,
                              TradingHaltSwitch halt, AttentionFeed feed, SseBroadcaster sse,
                              JdbcTemplate jdbcOrNull) {
        this.projection = projection;
        this.maxDrawdown = maxDrawdown;
        this.halt = halt;
        this.feed = feed;
        this.sse = sse;
        if (jdbcOrNull != null) {
            try {
                BigDecimal persisted = jdbcOrNull.queryForObject(
                        "select coalesce(max(total_pnl), 0) from firm_equity where feed_mode = ?",
                        BigDecimal.class, io.jethro.messaging.Provenance.mode().name());
                if (persisted != null && persisted.compareTo(peak) > 0) {
                    peak = persisted; // the high-water mark survives restarts
                }
            } catch (Exception e) {
                log.warn("could not seed firm-equity peak (starting from 0): {}", e.toString());
            }
        }
    }

    public void start() {
        if (maxDrawdown == null || maxDrawdown.signum() <= 0) {
            log.info("firm drawdown breaker DISABLED (jethro.risk.max-firm-drawdown unset)");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "firm-breaker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkOnce, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("firm drawdown breaker armed: halts all auto-execution at {} drawdown from peak",
                maxDrawdown.toPlainString());
    }

    void checkOnce() {
        try {
            long now = System.currentTimeMillis();
            // COMPREHENSIVE (actual money incl. FX translation) — a drawdown breaker must trip
            // on real book-value loss, not the clean trading figure (ADR-0037).
            BigDecimal total = projection.snapshot(now).total().comprehensivePnl();
            if (total.compareTo(peak) > 0) {
                peak = total;
            }
            BigDecimal drawdown = peak.subtract(total);
            if (drawdown.compareTo(maxDrawdown) >= 0) {
                if (!halt.isHalted()) {
                    String reason = "firm drawdown " + drawdown.setScale(2, java.math.RoundingMode.HALF_UP)
                            .toPlainString() + " from peak " + peak.setScale(2, java.math.RoundingMode.HALF_UP)
                            .toPlainString() + " breached the cap "
                            + maxDrawdown.toPlainString();
                    halt.trip(reason);
                    log.error("FIRM BREAKER TRIPPED: {} — all auto-execution halted; "
                            + "risk-reducing exits and manual orders remain allowed", reason);
                }
                feed.upsert(new AttentionFeed.AttentionItem(CARD_ID, now, AttentionFeed.Severity.ALERT,
                        "firm-breaker", "FIRM BREAKER: auto-trading halted",
                        halt.current() != null ? halt.current().reason() + ". Reset from the risk API "
                                + "(POST /api/breaker/reset) once the book is under control." : "",
                        "/books.html"));
                sse.broadcast("attention", feed.snapshot());
            } else if (!halt.isHalted()) {
                // Recovered AND reset: clear the card (a latched alarm would lie).
                feed.resolve(CARD_ID);
            }
        } catch (Exception e) {
            log.warn("firm breaker check failed (fails SAFE — no trip on error): {}", e.toString());
        }
    }

    @Override
    public void close() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }
}

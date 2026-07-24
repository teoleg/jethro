package io.jethro.app.risk;

import io.jethro.app.trading.TradingCoreLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Records the daily market history the measurement layer runs on (ADR-0027): on a cadence,
 * upserts the current session day's {@code daily_close} row per instrument with the latest
 * mark and its {@code firm_equity} row with the current total P&L. The day comes from the
 * session calendar (compressed sim days roll in minutes — the cadence is sized to sample
 * each day several times), and the EOD boundary writes the authoritative close at rollover;
 * this intra-day trail is the crash-safety net under it. Curve pseudo-instruments are
 * recorded too: that history enables rates VaR later. Off the tick path; a failed pass logs
 * and retries next cycle.
 */
public final class MarketHistoryRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarketHistoryRecorder.class);

    private final JdbcTemplate jdbc;
    private final TradingCoreLifecycle tradingCore;
    private final RiskProjectionSource risk;
    private final java.util.function.Supplier<LocalDate> sessionDay;
    private final long periodSeconds;
    private volatile ScheduledExecutorService scheduler;

    /** Narrow accessor so this class doesn't drag the whole projection surface. */
    public interface RiskProjectionSource {
        java.math.BigDecimal firmTotalPnl();
    }

    public MarketHistoryRecorder(JdbcTemplate jdbc, TradingCoreLifecycle tradingCore,
                                 RiskProjectionSource risk,
                                 java.util.function.Supplier<LocalDate> sessionDay, long periodSeconds) {
        this.jdbc = jdbc;
        this.tradingCore = tradingCore;
        this.risk = risk;
        this.sessionDay = sessionDay;
        this.periodSeconds = Math.max(1, periodSeconds);
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-history");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::recordOnce, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        log.info("market-history recorder started: daily closes + firm equity every {}s", periodSeconds);
    }

    void recordOnce() {
        try {
            var runtime = tradingCore != null ? tradingCore.runtime() : null;
            if (runtime == null) {
                return;
            }
            LocalDate today = sessionDay.get();
            for (var mark : runtime.markCache().snapshot()) {
                jdbc.update("""
                        insert into daily_close (day, instrument, close) values (?, ?, ?)
                        on conflict (day, instrument) do update set close = excluded.close
                        """, today, mark.instrumentId(), mark.price());
            }
            jdbc.update("""
                    insert into firm_equity (day, total_pnl, feed_mode) values (?, ?, ?)
                    on conflict (day, feed_mode) do update set total_pnl = excluded.total_pnl
                    """, today, risk.firmTotalPnl(), io.jethro.messaging.Provenance.mode().name());
        } catch (Exception e) {
            log.warn("market-history pass failed (retrying next cycle): {}", e.toString());
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

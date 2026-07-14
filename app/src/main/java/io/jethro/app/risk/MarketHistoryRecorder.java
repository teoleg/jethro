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
 * Records the daily market history the measurement layer runs on (ADR-0027): every minute,
 * upserts today's {@code daily_close} row per instrument with the latest mark (at day
 * rollover yesterday's row freezes as the close) and today's {@code firm_equity} row with
 * the current total P&L. Calendar-less day boundary (server-local date) on purpose — the
 * session calendar refines "close" later without touching this. Curve pseudo-instruments
 * are recorded too: that history enables rates VaR later. Off the tick path; a failed pass
 * logs and retries next minute.
 */
public final class MarketHistoryRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarketHistoryRecorder.class);
    private static final long PERIOD_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final TradingCoreLifecycle tradingCore;
    private final RiskProjectionSource risk;
    private volatile ScheduledExecutorService scheduler;

    /** Narrow accessor so this class doesn't drag the whole projection surface. */
    public interface RiskProjectionSource {
        java.math.BigDecimal firmTotalPnl();
    }

    public MarketHistoryRecorder(JdbcTemplate jdbc, TradingCoreLifecycle tradingCore,
                                 RiskProjectionSource risk) {
        this.jdbc = jdbc;
        this.tradingCore = tradingCore;
        this.risk = risk;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-history");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::recordOnce, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("market-history recorder started: daily closes + firm equity every {}s", PERIOD_SECONDS);
    }

    void recordOnce() {
        try {
            var runtime = tradingCore != null ? tradingCore.runtime() : null;
            if (runtime == null) {
                return;
            }
            LocalDate today = LocalDate.now();
            for (var mark : runtime.markCache().snapshot()) {
                jdbc.update("""
                        insert into daily_close (day, instrument, close) values (?, ?, ?)
                        on conflict (day, instrument) do update set close = excluded.close
                        """, today, mark.instrumentId(), mark.price());
            }
            jdbc.update("""
                    insert into firm_equity (day, total_pnl) values (?, ?)
                    on conflict (day) do update set total_pnl = excluded.total_pnl
                    """, today, risk.firmTotalPnl());
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

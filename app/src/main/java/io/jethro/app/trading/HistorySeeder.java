package io.jethro.app.trading;

import io.jethro.app.risk.HistoryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Seeds the hedger's return history from REAL market history via a {@link HistoryClient} (Tiingo by
 * default — the Yahoo channel is defunct) — even when the live feed is the sim — so the covariance is
 * grounded in real cross-asset relationships from tick one instead of warming for days (ADR-0038).
 * Runs once at boot on a background thread (network I/O, never on the boot path), non-fatal: a fetch
 * that fails just leaves the covariance to warm up from the live feed.
 *
 * <p>Index futures map to their ETF proxy (SPY→ES, QQQ→NQ) and FX to the provider's pair symbol; each
 * proxy's closes are RESCALED so the last one matches the instrument's configured level, keeping the
 * seeded history continuous with the sim tape (returns — the only thing the covariance uses — are
 * unchanged by the scaling). Idempotent and non-destructive: inserts only past-dated days
 * {@code on conflict do nothing}, never touching today's live close or any kept row. Dev/demo
 * capability (ADR-0023): unofficial, ToS-limited — never a production data path.
 */
public final class HistorySeeder {

    private static final Logger log = LoggerFactory.getLogger(HistorySeeder.class);

    private final JdbcTemplate jdbc;
    private final TradingCoreProperties props;
    private final io.jethro.trading.riskpnl.InstrumentRefSource refs;
    private final HistoryStatus status;
    private final HistoryClient client;
    private final int windowDays;
    private final long spacingMillis;
    private volatile Thread worker;

    public HistorySeeder(JdbcTemplate jdbc, TradingCoreProperties props,
                         io.jethro.trading.riskpnl.InstrumentRefSource refs, HistoryStatus status,
                         HistoryClient client, int windowDays, long spacingMillis) {
        this.jdbc = jdbc;
        this.props = props;
        this.refs = refs;
        this.status = status;
        this.client = client;
        this.windowDays = Math.max(25, windowDays);
        this.spacingMillis = Math.max(0, spacingMillis);
    }

    public void start() {
        Thread t = new Thread(this::run, "history-seed");
        t.setDaemon(true);
        this.worker = t;
        t.start(); // never blocks the boot path
    }

    private void run() {
        try {
            // Count the SEED block only (ADR-0073): a session's own accrued closes are its stream, not
            // the bootstrap prior, so they must not make the seeder think the prior is already on file.
            Long have = jdbc.queryForObject(
                    "select count(distinct day) from daily_close where feed_mode = ?", Long.class,
                    io.jethro.app.risk.DailyCloseSeries.SEED);
            if (have != null && have >= windowDays) {
                status.markExisting();
                log.info("history: {} days already on file (>= {} window) — no fetch needed", have, windowDays);
                return;
            }
            LocalDate today = LocalDate.now();
            int names = 0, rows = 0;
            for (String id : refs.instrumentIds()) { // the DYNAMIC refdata master (invariant 9), not a list
                String assetClass = refs.find(id)
                        .map(io.jethro.trading.riskpnl.InstrumentRef::assetClass).orElse(null);
                String sym = HistorySymbols.proxyFor(id, assetClass);
                if (sym == null) {
                    continue; // no history proxy for this one (e.g. Treasury futures) — skip
                }
                var hist = client.fetch(sym);
                if (hist.isEmpty()) {
                    log.warn("history: no data for {} ({}) — skipping (no token/rate-limited/offline?)", id, sym);
                    sleep();
                    continue;
                }
                rows += seedInstrument(id, hist.get(), today);
                names++;
                sleep(); // gentle spacing so the provider doesn't rate-limit the burst
            }
            if (names > 0 && rows > 0) {
                status.markSeeded("tiingo-history", names + " names, " + rows + " days from Tiingo");
                log.info("history: seeded {} names, {} daily_close rows from REAL Tiingo history — "
                        + "covariance grounded in real cross-asset relationships", names, rows);
            } else {
                status.markFailed("history provider returned no data (no token / rate-limited / offline)");
                log.warn("history: provider returned nothing usable — covariance will warm up from the live feed");
            }
        } catch (Exception e) {
            status.markFailed("history seed error: " + e.getMessage());
            log.warn("history seed failed ({}) — covariance will warm up from the live feed", e.toString());
        }
    }

    private int seedInstrument(String id, HistoryClient.History h, LocalDate today) {
        double[] closes = h.closes();
        long[] days = h.epochDays();
        double scale = scaleToLevel(closes, props.startPriceFor(id).doubleValue());
        int rows = 0;
        for (int i = 0; i < days.length; i++) {
            LocalDate day = LocalDate.ofEpochDay(days[i]);
            if (!day.isBefore(today)) {
                continue; // never seed today/future — the live feed owns today's close
            }
            BigDecimal close = BigDecimal.valueOf(closes[i] * scale).setScale(6, RoundingMode.HALF_UP);
            if (close.signum() <= 0) {
                continue;
            }
            // ADR-0073: the bootstrap prior is tagged SEED — reference history loaded once before any
            // session ran, admissible in every feed mode, and never confused with a session's own
            // closes (which carry their own mode and form their own return stream).
            jdbc.update("""
                    insert into daily_close (day, instrument, close, feed_mode) values (?, ?, ?, ?)
                    on conflict (day, instrument, feed_mode) do nothing
                    """, day, id, close, io.jethro.app.risk.DailyCloseSeries.SEED);
            rows++;
        }
        return rows;
    }

    /** Scale factor so the proxy's LAST close maps to {@code targetLevel} — keeps the seeded tape
     *  continuous with the instrument's own price level. Returns 1 when it can't (empty/degenerate).
     *  Returns unchanged over scale: correlations/betas (return-based) are invariant to it. */
    static double scaleToLevel(double[] closes, double targetLevel) {
        if (closes == null || closes.length == 0) {
            return 1.0;
        }
        double last = closes[closes.length - 1];
        return last > 0 && targetLevel > 0 ? targetLevel / last : 1.0;
    }

    private void sleep() {
        if (spacingMillis == 0) {
            return;
        }
        try {
            Thread.sleep(spacingMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}

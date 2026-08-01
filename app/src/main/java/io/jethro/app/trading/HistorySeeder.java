package io.jethro.app.trading;

import io.jethro.app.risk.HistoryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the hedger/VaR return history grounded in REAL market history via a {@link HistoryClient}
 * (Tiingo by default — the Yahoo channel is defunct), even when the live feed is the sim, so the
 * covariance and historical VaR run on real cross-asset relationships instead of warming for days
 * (ADR-0038). Seeds at boot AND refreshes PERIODICALLY (ADR-0128) so the `daily_close` SEED series
 * rolls forward to the latest close rather than freezing at the day it was first loaded — every
 * consumer that reads `daily_close` (historical + parametric VaR, vol-targeting, the hedge covariance)
 * therefore sees an up-to-date window with no per-consumer wiring. Off the boot path (network I/O on a
 * daemon scheduler); non-fatal — a fetch that fails just leaves the last-good history in place.
 *
 * <p>Index futures map to their ETF proxy (SPY→ES, QQQ→NQ) and FX to the provider's pair symbol; each
 * proxy's closes are RESCALED so the last one matches the instrument's configured level, keeping the
 * seeded history continuous with the sim tape (returns — the only thing the covariance/VaR use — are
 * unchanged by the uniform scaling). Each refresh rewrites the SEED rows with a single consistent scale
 * and appends the new tail ({@code on conflict … do update} on the SEED feed-mode ONLY, a separate PK
 * partition from the live-accumulated closes, so it never touches the session's own stream). A refresh
 * runs only when the SEED tail is STALE, so a same-day tick is a cheap no-op. Dev/demo capability
 * (ADR-0023): unofficial, ToS-limited — never a production data path.
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
    private final int refreshHours;
    private volatile ScheduledExecutorService scheduler;

    /** How stale the newest SEED close may be before a refresh re-fetches: a few calendar days so a
     *  weekend/holiday gap doesn't force a needless pull, but no longer. */
    private static final int STALE_DAYS = 4;

    public HistorySeeder(JdbcTemplate jdbc, TradingCoreProperties props,
                         io.jethro.trading.riskpnl.InstrumentRefSource refs, HistoryStatus status,
                         HistoryClient client, int windowDays, long spacingMillis, int refreshHours) {
        this.jdbc = jdbc;
        this.props = props;
        this.refs = refs;
        this.status = status;
        this.client = client;
        this.windowDays = Math.max(25, windowDays);
        this.spacingMillis = Math.max(0, spacingMillis);
        this.refreshHours = Math.max(1, refreshHours);
    }

    public void start() {
        // Daemon scheduler: seed immediately at boot, then refresh on a daily cadence so the SEED
        // history rolls forward instead of freezing at first load (ADR-0128). Network I/O runs here,
        // never on the boot path. A refresh whose SEED tail is already current is a cheap no-op.
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "history-seed");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = s;
        s.scheduleAtFixedRate(this::run, 0, refreshHours, TimeUnit.HOURS);
    }

    private void run() {
        try {
            // Count the SEED block only (ADR-0073): a session's own accrued closes are its stream, not
            // the bootstrap prior, so they must not make the seeder think the prior is already on file.
            Long have = jdbc.queryForObject(
                    "select count(distinct day) from daily_close where feed_mode = ?", Long.class,
                    io.jethro.app.risk.DailyCloseSeries.SEED);
            LocalDate latestSeed = jdbc.query(
                    "select max(day) as d from daily_close where feed_mode = ?",
                    rs -> rs.next() && rs.getDate("d") != null ? rs.getDate("d").toLocalDate() : null,
                    io.jethro.app.risk.DailyCloseSeries.SEED);
            boolean sufficient = have != null && have >= windowDays;
            boolean current = latestSeed != null && latestSeed.isAfter(LocalDate.now().minusDays(STALE_DAYS));
            if (sufficient && current) {
                // The SEED window is on file AND its tail reaches (near) today — nothing to fetch this
                // cycle. Cheap no-op on the periodic schedule until a new trading day makes it stale.
                status.markExisting();
                log.info("history: {} SEED days on file, latest {} — up to date, no fetch", have, latestSeed);
                return;
            }
            log.info("history: refreshing SEED history ({} days on file, latest {}) — rolling the window "
                    + "forward (ADR-0128)", have, latestSeed);
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
            // ADR-0073: the bootstrap prior is tagged SEED — reference history, admissible in every feed
            // mode, never confused with a session's own closes (which carry their own mode and form their
            // own return stream; the PK partitions on feed_mode, so this only ever touches SEED rows).
            // ADR-0128: DO UPDATE (not do-nothing) so a periodic refresh re-anchors the whole SEED series
            // to ONE consistent scale and extends the tail; returns — the only thing VaR/covariance use —
            // are invariant to the uniform re-scaling, and the live-accumulated stream is a different
            // feed_mode partition, untouched.
            jdbc.update("""
                    insert into daily_close (day, instrument, close, feed_mode) values (?, ?, ?, ?)
                    on conflict (day, instrument, feed_mode) do update set close = excluded.close
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
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }
}

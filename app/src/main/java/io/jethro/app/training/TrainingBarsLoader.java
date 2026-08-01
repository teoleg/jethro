package io.jethro.app.training;

import io.jethro.app.trading.HistorySymbols;
import io.jethro.app.trading.TiingoHistoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the ADR-0053 {@link TrainingBarsStore} current from a direct Tiingo EOD pull — REBUILD ON EMPTY
 * so a data wipe self-heals, and REFRESH PERIODICALLY (ADR-0128) so the training window rolls forward
 * instead of freezing at first load. Off the boot path (network I/O on a daemon scheduler), non-fatal:
 * a missing token or a failed fetch just leaves the last-good set in place.
 *
 * <p>Tiingo free tier is generous for our universe (50 req/hr, 1000/day) — a load is a request per name
 * with gentle spacing, and a refresh runs only when the store's tail is stale so a same-day tick is a
 * cheap no-op. INTERNAL USE ONLY per the Tiingo free licence (ADR-0023) — training/analytics only, never
 * redistributed, never a production feed, never a number into live sizing/risk (ADR-0016 / invariant 7).
 */
public final class TrainingBarsLoader {

    private static final Logger log = LoggerFactory.getLogger(TrainingBarsLoader.class);
    private static final int STALE_DAYS = 4;

    private final TrainingBarsStore store;
    private final TiingoHistoryClient client;
    private final io.jethro.trading.riskpnl.InstrumentRefSource refs;
    private final long spacingMillis;
    private final int refreshHours;
    private volatile ScheduledExecutorService scheduler;

    public TrainingBarsLoader(TrainingBarsStore store, TiingoHistoryClient client,
                              io.jethro.trading.riskpnl.InstrumentRefSource refs, long spacingMillis,
                              int refreshHours) {
        this.store = store;
        this.client = client;
        this.refs = refs;
        this.spacingMillis = Math.max(0, spacingMillis);
        this.refreshHours = Math.max(1, refreshHours);
    }

    public void start() {
        // Daemon scheduler: load at boot, then refresh daily so the training window rolls forward
        // (ADR-0128). A refresh whose tail is already current is a cheap no-op.
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "training-bars-load");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = s;
        s.scheduleAtFixedRate(this::run, 0, refreshHours, TimeUnit.HOURS);
    }

    private void run() {
        try {
            if (!store.isEmpty()) {
                var s = store.status();
                LocalDate last = s.lastDay() == null ? null : LocalDate.parse(s.lastDay());
                boolean current = last != null && last.isAfter(LocalDate.now().minusDays(STALE_DAYS));
                if (current) {
                    log.info("training bars current ({} rows, {} instruments, {}..{}) — no fetch (ADR-0053)",
                            s.rows(), s.instruments(), s.firstDay(), s.lastDay());
                    return;
                }
                log.info("training bars stale (latest {}) — refreshing the window forward (ADR-0128)",
                        s.lastDay());
            }
            if (!client.configured()) {
                log.info("training bars: no Tiingo token — skipping load (set TIINGO_API_TOKEN); "
                        + "the learned-signal training set stays empty until then (ADR-0053)");
                return;
            }
            int names = 0;
            int rows = 0;
            for (String id : refs.instrumentIds()) { // the DYNAMIC refdata master (invariant 9), not a list
                String assetClass = refs.find(id)
                        .map(io.jethro.trading.riskpnl.InstrumentRef::assetClass).orElse(null);
                String sym = HistorySymbols.proxyFor(id, assetClass);
                if (sym == null) {
                    continue; // no free proxy (Treasury futures / swaps) — skip
                }
                var hist = client.fetch(sym);
                if (hist.isEmpty()) {
                    log.warn("training bars: no data for {} ({}) — skipping", id, sym);
                    sleep();
                    continue;
                }
                rows += store.upsert(id, hist.get(), "tiingo");
                names++;
                sleep(); // gentle spacing — stay well under Tiingo's 50 req/hr
            }
            if (rows > 0) {
                var s = store.status();
                log.info("training bars loaded (ADR-0053): {} names, {} rows, {}..{} from REAL Tiingo history",
                        names, rows, s.firstDay(), s.lastDay());
            } else {
                log.warn("training bars: Tiingo returned nothing usable — training set stays empty");
            }
        } catch (Exception e) {
            log.warn("training-bars load failed ({}) — training set stays empty", e.toString());
        }
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

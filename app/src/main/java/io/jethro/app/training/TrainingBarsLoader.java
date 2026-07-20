package io.jethro.app.training;

import io.jethro.app.trading.HistorySymbols;
import io.jethro.app.trading.TiingoHistoryClient;
import io.jethro.app.trading.TradingCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Populates the ADR-0053 {@link TrainingBarsStore} from a direct Tiingo EOD pull at boot — REBUILD ON
 * EMPTY, so a data wipe on restart self-heals and the training set is always real (never sim). Runs
 * once on a background thread (network I/O, never on the boot path), non-fatal: a missing token or a
 * failed fetch just leaves the store empty (logged) and the model layer has nothing to train on yet.
 *
 * <p>Tiingo free tier is generous for our ~12-symbol universe (limits are 50 req/hr, 1000/day, 500
 * unique symbols/month) — a full load is ~12 requests with gentle spacing. INTERNAL USE ONLY per the
 * Tiingo free licence (ADR-0023) — training/analytics only, never redistributed, never a production
 * feed, never a number into live sizing/risk (ADR-0016 / invariant 7).
 */
public final class TrainingBarsLoader {

    private static final Logger log = LoggerFactory.getLogger(TrainingBarsLoader.class);

    private final TrainingBarsStore store;
    private final TiingoHistoryClient client;
    private final TradingCoreProperties props;
    private final long spacingMillis;
    private volatile Thread worker;

    public TrainingBarsLoader(TrainingBarsStore store, TiingoHistoryClient client,
                              TradingCoreProperties props, long spacingMillis) {
        this.store = store;
        this.client = client;
        this.props = props;
        this.spacingMillis = Math.max(0, spacingMillis);
    }

    public void start() {
        Thread t = new Thread(this::run, "training-bars-load");
        t.setDaemon(true);
        this.worker = t;
        t.start(); // never blocks the boot path
    }

    private void run() {
        try {
            if (!store.isEmpty()) {
                var s = store.status();
                log.info("training bars present ({} rows, {} instruments, {}..{}) — no load needed (ADR-0053)",
                        s.rows(), s.instruments(), s.firstDay(), s.lastDay());
                return;
            }
            if (!client.configured()) {
                log.info("training bars: no Tiingo token — skipping load (set TIINGO_API_TOKEN); "
                        + "the learned-signal training set stays empty until then (ADR-0053)");
                return;
            }
            int names = 0;
            int rows = 0;
            for (String id : props.simInstruments()) {
                String sym = HistorySymbols.PROXY.get(id);
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
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}

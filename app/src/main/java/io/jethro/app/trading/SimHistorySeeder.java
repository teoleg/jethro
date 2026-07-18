package io.jethro.app.trading;

import io.jethro.app.risk.HistoryStatus;
import io.jethro.domain.Decimals;
import io.jethro.messaging.FeedMode;
import io.jethro.messaging.Provenance;
import io.jethro.trading.marketdata.sim.CorrelatedFactorSimulator;
import io.jethro.trading.marketdata.sim.FactorModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the hedger's return history at boot so the covariance is ready from tick one instead of
 * waiting real-time days to accumulate (ADR-0038 warm-up). SIM-only and deterministic: it runs the
 * SAME calibrated factor model the live sim uses, but with one trading day per tick, back-fills
 * {@code daily_close} for the trailing window, and stops. Because the calibration is grounded in
 * real market history (scripts/calibrate_sim.py), the seeded covariance carries the real cross-asset
 * relationships — it is not a made-up number.
 *
 * <p>Idempotent and non-destructive: it only runs when fewer than a full window of days exists, and
 * inserts {@code on conflict do nothing}, so it never overwrites real accumulated or kept history.
 * In LIVE mode it does nothing — real history there comes from the feed (a provider back-fill is the
 * next increment).
 */
public final class SimHistorySeeder {

    private static final Logger log = LoggerFactory.getLogger(SimHistorySeeder.class);
    private static final long SEED_SALT = 0x5EEDL; // distinct stream from the live tape

    private final JdbcTemplate jdbc;
    private final TradingCoreProperties props;
    private final HistoryStatus status;
    private final int windowDays;

    public SimHistorySeeder(JdbcTemplate jdbc, TradingCoreProperties props, HistoryStatus status, int windowDays) {
        this.jdbc = jdbc;
        this.props = props;
        this.status = status;
        this.windowDays = Math.max(25, windowDays);
    }

    public void start() {
        try {
            if (Provenance.mode() != FeedMode.SIM) {
                return; // seed only the sim; live history accrues from the real feed
            }
            Long have = jdbc.queryForObject("select count(distinct day) from daily_close", Long.class);
            if (have != null && have >= windowDays) {
                status.markExisting();
                return; // already enough real/kept history — never re-seed or overwrite
            }
            seed();
        } catch (Exception e) {
            log.warn("history seed skipped ({}) — the covariance will warm up live instead", e.toString());
        }
    }

    private void seed() throws Exception {
        FactorModelConfig cfg = SimCalibrationLoader.load(props.simCalibrationPathOrNull());
        List<String> active = props.simInstruments();
        List<String> ids = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        for (FactorModelConfig.InstrumentSpec spec : cfg.instruments()) {
            if (active.contains(spec.id())) {
                ids.add(spec.id());
                starts.add(Decimals.toScaledLong(props.startPriceFor(spec.id()), Decimals.PRICE_SCALE));
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        int days = windowDays + 5; // a little margin over the window
        long[][] closes = simulateDailyCloses(cfg, ids, starts.stream().mapToLong(Long::longValue).toArray(),
                days, props.simSeed() ^ SEED_SALT);

        LocalDate today = LocalDate.now();
        int rows = 0;
        for (int d = 0; d < days; d++) {
            LocalDate day = today.minusDays(days - d); // trailing window ending yesterday
            for (int i = 0; i < ids.size(); i++) {
                jdbc.update("""
                        insert into daily_close (day, instrument, close) values (?, ?, ?)
                        on conflict (day, instrument) do nothing
                        """, day, ids.get(i), new BigDecimal(closes[d][i]).movePointLeft(Decimals.PRICE_SCALE));
                rows++;
            }
        }
        status.markSeeded(days, ids.size());
        log.info("SIM history seed: {} days × {} instruments ({} daily_close rows) — covariance ready at boot",
                days, ids.size(), rows);
    }

    /**
     * Deterministic daily closes (scaled long, 1e-6) from the calibrated factor model, one trading
     * day per step (tickSeconds == simSecondsPerDay ⇒ dtDays = 1). Pure and unit-testable.
     */
    static long[][] simulateDailyCloses(FactorModelConfig cfg, List<String> ids, long[] startScaled,
                                        int days, long seed) {
        var sim = new CorrelatedFactorSimulator(seed, cfg, ids, startScaled, 1.0, 1.0);
        long[][] closes = new long[days][ids.size()];
        for (int d = 0; d < days; d++) {
            sim.nextTick();
            for (int i = 0; i < ids.size(); i++) {
                closes[d][i] = sim.priceScaled(i);
            }
        }
        return closes;
    }
}

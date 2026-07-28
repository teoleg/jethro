package io.jethro.app.risk;

import io.jethro.trading.riskpnl.VolMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Measured daily vol per instrument from the recorded {@code daily_close} history — the
 * SAME data the VaR window uses, so sizing and risk measure the same world. EWMA (λ=0.94,
 * {@link VolMath}) over consecutive-session returns; at least {@value #MIN_OBSERVATIONS}
 * observations before an instrument is considered measured (with the compressed sim
 * calendar that's ~{@value #MIN_OBSERVATIONS} sim days ≈ 20 wall minutes at 120s/day).
 * Refreshes the whole map at most every {@value #REFRESH_MILLIS}ms — off the tick path.
 */
public final class InstrumentVolService implements InstrumentVolSource {

    private static final Logger log = LoggerFactory.getLogger(InstrumentVolService.class);
    static final int MIN_OBSERVATIONS = 10;
    static final int WINDOW_DAYS = 60;
    private static final long REFRESH_MILLIS = 30_000;

    private final JdbcTemplate jdbc;
    private final AtomicReference<Map.Entry<Long, Map<String, BigDecimal>>> cache = new AtomicReference<>();

    public InstrumentVolService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> dailyVol(String instrumentId) {
        return Optional.ofNullable(vols().get(instrumentId));
    }

    private Map<String, BigDecimal> vols() {
        long now = System.currentTimeMillis();
        var entry = cache.get();
        if (entry != null && now - entry.getKey() <= REFRESH_MILLIS) {
            return entry.getValue();
        }
        Map<String, BigDecimal> fresh = compute();
        cache.set(Map.entry(now, fresh));
        return fresh;
    }

    private Map<String, BigDecimal> compute() {
        try {
            // instrument → (day → close), days ascending, over the trailing window. Restricted to the
            // streams this session may measure and carrying the stream tag, so a handover between the
            // seed and the session — or between two feed modes — never becomes a return (ADR-0073).
            List<String> modes = DailyCloseSeries.admissibleModes();
            Map<String, TreeMap<LocalDate, DailyCloseSeries.Close>> closes = new LinkedHashMap<>();
            jdbc.query("""
                    select day, instrument, close, feed_mode from daily_close
                    where feed_mode in (?, ?)
                      and day >= (select coalesce(max(day), current_date) from daily_close
                                  where feed_mode in (?, ?)) - ?::int
                    order by day
                    """, rs -> {
                LocalDate day = rs.getObject("day", LocalDate.class);
                var byDay = closes.computeIfAbsent(rs.getString("instrument"), i -> new TreeMap<>());
                var candidate = new DailyCloseSeries.Close(day, rs.getString("feed_mode"),
                        rs.getBigDecimal("close").doubleValue());
                var held = byDay.get(day);
                if (held == null || DailyCloseSeries.preferOver(held.feedMode(), candidate.feedMode())) {
                    byDay.put(day, candidate);
                }
            }, modes.get(0), modes.get(1), modes.get(0), modes.get(1), WINDOW_DAYS + 30);

            Map<String, BigDecimal> out = new LinkedHashMap<>();
            closes.forEach((instrument, byDay) -> {
                double[] returns = DailyCloseSeries.returns(new ArrayList<>(byDay.values()));
                VolMath.ewmaDailyVol(returns, MIN_OBSERVATIONS)
                        .ifPresent(v -> out.put(instrument, BigDecimal.valueOf(v)));
            });
            return out;
        } catch (Exception e) {
            log.warn("vol estimation pass failed (unmeasured this cycle): {}", e.toString());
            return Map.of();
        }
    }
}

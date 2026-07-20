package io.jethro.app.session;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.order.OrderService;
import io.jethro.trading.riskpnl.RiskProjection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Session-calendar + EOD wiring (ADR-0027). The calendar matches the tape: a pure sim run
 * gets the compressed calendar (a trading day per {@code sim-seconds-per-day} wall seconds,
 * so daily history — VaR window, day P&L — accrues at sim speed); a real provider gets
 * wall-clock dates in the configured zone. Present only when trading runs — without a tape
 * there are no sessions to close.
 */
@Configuration
public class SessionConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.trading", name = "enabled", havingValue = "true", matchIfMissing = true)
    TradingCalendar tradingCalendar(TradingCoreProperties properties, ObjectProvider<JdbcTemplate> jdbc,
                                    ObjectProvider<TradingCoreLifecycle> tradingCore) {
        // Provider choice is config-time: a finnhub/yahoo run that falls back to the sim feed at
        // runtime (missing token) keeps the wall-clock calendar — real time still passes.
        if (!"sim".equals(properties.providerOrDefault())) {
            return new WallClockSessionCalendar(properties.sessionZoneOrDefault(),
                    properties.sessionRollHourOrDefault());
        }
        // Key the sim calendar to the TAPE's own day counter (set once the correlated adapter
        // starts) so session closes land on the same boundary as the overnight gaps; negative
        // (not started / legacy engine) falls back to wall time inside the calendar.
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        java.util.function.LongSupplier tapeDays = () -> {
            var src = core != null ? core.simDayIndexSource() : null;
            return src != null ? src.getAsLong() : -1;
        };
        return new SimSessionCalendar(anchorPastHistory(jdbc.getIfAvailable()),
                properties.simSecondsPerDayOrDefault(), System::currentTimeMillis, tapeDays);
    }

    /** Synthetic sim days must stay monotonic across restarts: anchor the first day after the
     *  last persisted session so a restart never rewrites a closed day. */
    private static LocalDate anchorPastHistory(JdbcTemplate jdbc) {
        LocalDate today = LocalDate.now();
        if (jdbc == null) {
            return today;
        }
        try {
            LocalDate last = jdbc.query("select max(day) as day from firm_equity where feed_mode = ?",
                    rs -> rs.next() ? rs.getObject("day", LocalDate.class) : null,
                    io.jethro.messaging.Provenance.mode().name());
            return last != null && !last.plusDays(1).isBefore(today) ? last.plusDays(1) : today;
        } catch (Exception e) {
            return today; // table missing/unreachable — anchor at today, upserts stay consistent
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.trading", name = "enabled", havingValue = "true", matchIfMissing = true)
    EodService eodService(TradingCalendar calendar, ObjectProvider<JdbcTemplate> jdbc,
                          RiskProjection projection,
                          ObjectProvider<TradingCoreLifecycle> tradingCore,
                          ObjectProvider<OrderService> orderService) {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        EodService.MarkSource marks = () -> {
            var runtime = core != null ? core.runtime() : null;
            if (runtime == null) {
                return List.of();
            }
            return runtime.markCache().snapshot().stream()
                    .map(m -> new EodService.MarkSource.Mark(m.instrumentId(), m.price()))
                    .toList();
        };
        OrderService orders = orderService.getIfAvailable();
        EodService service = new EodService(calendar, jdbc.getIfAvailable(),
                () -> projection.snapshot(System.currentTimeMillis()), marks,
                orders != null ? orders::expireDayOrders : null);
        service.start();
        return service;
    }
}

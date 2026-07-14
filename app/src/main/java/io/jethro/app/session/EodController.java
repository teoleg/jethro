package io.jethro.app.session;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * The session/EOD surface (ADR-0027): which trading day it is, today's P&L measured from
 * the previous session's persisted close, and the recent daily firm history. Decimals as
 * strings (invariant 1).
 */
@RestController
public final class EodController {

    public record DayDto(String day, String totalPnl, String dayPnl) {
    }

    public record EodDto(String sessionDay, String calendar, String todayPnl,
                         String previousCloseTotal, List<DayDto> days, String note) {
    }

    private final ObjectProvider<EodService> eod;

    public EodController(ObjectProvider<EodService> eod) {
        this.eod = eod;
    }

    @GetMapping("/api/eod")
    public EodDto eod() {
        EodService service = this.eod.getIfAvailable();
        if (service == null) {
            return new EodDto(null, null, null, null, List.of(),
                    "session calendar off (trading disabled)");
        }
        List<DayDto> days = service.recentDays(15).stream()
                .map(d -> new DayDto(d.day().toString(), plain(d.totalPnl()), plain(d.dayPnl())))
                .toList();
        return new EodDto(service.sessionDay().toString(), service.calendarDescription(),
                plain(service.todayPnl()), plain(service.previousCloseTotal()), days, null);
    }

    private static String plain(BigDecimal v) {
        return v != null ? v.toPlainString() : null;
    }
}

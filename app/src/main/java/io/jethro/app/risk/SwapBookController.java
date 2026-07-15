package io.jethro.app.risk;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * The trade-dated swap book (ADR-0020): each swap fill priced as a SEASONED trade on the
 * live curve — entry par as the fixed leg, remaining schedule from its own trade day, so
 * roll-down is real. Decimals as strings (invariant 1). Empty when persistence is off.
 */
@RestController
public final class SwapBookController {

    public record TradeDto(String fillId, String instrument, String book, String side,
                           String lots, String entryPar, String tradeDay, String maturity,
                           String presentValue, String dv01, boolean matured) {
    }

    public record BookDto(List<TradeDto> trades, String totalPv, String totalDv01,
                          String valuationDay, String note) {
    }

    private final ObjectProvider<SwapBookService> book;

    public SwapBookController(ObjectProvider<SwapBookService> book) {
        this.book = book;
    }

    @GetMapping("/api/swaps/book")
    public BookDto book() {
        SwapBookService service = book.getIfAvailable();
        if (service == null) {
            return new BookDto(List.of(), null, null, null, "persistence off — no trade registry");
        }
        var v = service.value();
        List<TradeDto> trades = v.trades().stream().map(t -> new TradeDto(
                t.fillId(), t.instrument(), t.book(), t.side(),
                plain(t.lots()), plain(t.entryPar()), t.tradeDay().toString(), t.maturity().toString(),
                plain(t.presentValue()), plain(t.dv01()), t.matured())).toList();
        return new BookDto(trades, plain(v.totalPv()), plain(v.totalDv01()), v.valuationDay(), v.note());
    }

    private static String plain(BigDecimal x) {
        return x != null ? x.toPlainString() : null;
    }
}

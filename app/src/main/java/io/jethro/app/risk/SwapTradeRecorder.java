package io.jethro.app.risk;

import io.jethro.domain.Fill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Records every SWAP fill as a dated trade in {@code swap_trades} (V23) — the input for
 * seasoned-swap valuation (roll-down). Idempotent on fill_id: the risk consumer replays
 * fills from the beginning on every boot (invariant 6), and replayed fills keep their
 * ORIGINAL trade day (conflict-do-nothing never rewrites a row). trade_day is the session
 * day at first sight — a fill first seen long after execution gets the current session
 * (bootstrap-only imprecision, disclosed). A failed write logs; the projection is
 * unaffected (this registry is a rates VIEW input, not the position source of truth).
 */
public final class SwapTradeRecorder {

    private static final Logger log = LoggerFactory.getLogger(SwapTradeRecorder.class);

    private final JdbcTemplate jdbc;
    private final io.jethro.trading.riskpnl.InstrumentRefSource refs;
    private final Supplier<LocalDate> sessionDay;

    public SwapTradeRecorder(JdbcTemplate jdbc, io.jethro.trading.riskpnl.InstrumentRefSource refs,
                             Supplier<LocalDate> sessionDay) {
        this.jdbc = jdbc;
        this.refs = refs;
        this.sessionDay = sessionDay;
    }

    /** Fill tap: no-op for non-swaps; idempotent dated-trade insert for SWAP fills. */
    public void onFill(Fill fill) {
        try {
            boolean swap = refs.find(fill.instrumentId().value())
                    .map(r -> "SWAP".equals(r.assetClass())).orElse(false);
            if (!swap) {
                return;
            }
            jdbc.update("""
                    insert into swap_trades (fill_id, instrument, book, side, lots, entry_par, trade_day)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict (fill_id) do nothing
                    """,
                    fill.fillId(), fill.instrumentId().value(), fill.bookId().value(),
                    fill.side().name(), fill.quantity(), fill.price(), sessionDay.get());
        } catch (Exception e) {
            log.warn("swap trade record failed for {} (rates view only; positions unaffected): {}",
                    fill.fillId(), e.toString());
        }
    }
}

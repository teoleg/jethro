package io.jethro.app.risk;

import io.jethro.messaging.Provenance;
import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P&amp;L attribution that separates <b>strategy alpha</b> from the <b>hedge</b> and surfaces the
 * cumulative <b>transaction cost</b> — so a hedge that happens to win on a directional day can never
 * mask a bleeding strategy book (Grinold &amp; Kahn, <i>Active Portfolio Management</i>, 2000, ch. 17).
 * The 2026-07-24 live post-mortem showed exactly that failure: strategy book −$8.8k, hedge +$7.5k,
 * firm ≈ flat. This endpoint makes the split impossible to miss.
 *
 * <p>Read-only. Strategy books = every book that is NOT the hedge book ({@code jethro.hedge.book});
 * fees are the realised cash already inside P&amp;L, shown separately as the cost drag. Scoped to the
 * running feed mode (invariant 8) so sim and live fees never mix.
 */
@RestController
public final class AttributionController {

    private final RiskProjection projection;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final String hedgeBook;

    public AttributionController(RiskProjection projection, ObjectProvider<JdbcTemplate> jdbc,
                                @Value("${jethro.hedge.book:HEDGE}") String hedgeBook) {
        this.projection = projection;
        this.jdbc = jdbc;
        this.hedgeBook = hedgeBook;
    }

    public record BookLine(String book, String role, String totalPnl, String realizedPnl,
                           String unrealizedPnl, String feesPaid) {
    }

    /**
     * @param strategyAlpha  Σ totalPnl of the strategy books — the number that actually reflects skill
     * @param hedgePnl       the hedge book's P&amp;L (offsets direction; not alpha)
     * @param firmTotal      strategyAlpha + hedgePnl (what the headline shows)
     * @param totalFees      cumulative fees paid this feed mode (transaction-cost drag, already in P&amp;L)
     * @param hedgeMasking   true when the hedge and the strategy alpha have OPPOSITE signs — i.e. the
     *                       flat-looking headline is the hedge covering a losing (or winning) book
     */
    public record Attribution(boolean available, String feedMode, String strategyAlpha, String hedgePnl,
                              String firmTotal, String totalFees, boolean hedgeMasking, List<BookLine> books) {
    }

    @GetMapping("/api/attribution")
    public Attribution attribution() {
        ConsolidatedRisk r = projection.snapshot(System.currentTimeMillis());
        String mode = Provenance.mode().name();
        Map<String, BigDecimal> feesByBook = feesByBook(mode);

        BigDecimal strategyAlpha = BigDecimal.ZERO;
        BigDecimal hedgePnl = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        List<BookLine> lines = new java.util.ArrayList<>();

        for (ConsolidatedRisk.Group g : r.byBook()) {
            boolean isHedge = hedgeBook.equals(g.key());
            BigDecimal fees = feesByBook.getOrDefault(g.key(), BigDecimal.ZERO);
            totalFees = totalFees.add(fees);
            if (isHedge) {
                hedgePnl = hedgePnl.add(g.totalPnl());
            } else {
                strategyAlpha = strategyAlpha.add(g.totalPnl());
            }
            lines.add(new BookLine(g.key(), isHedge ? "hedge" : "strategy", g.totalPnl().toPlainString(),
                    g.realizedPnl().toPlainString(), g.unrealizedPnl().toPlainString(), fees.toPlainString()));
        }
        BigDecimal firmTotal = strategyAlpha.add(hedgePnl);
        // The hedge is "masking" when it and the strategy alpha pull in opposite directions: the headline
        // firm P&L then reflects the hedge offsetting the book, not the strategy's skill.
        boolean masking = strategyAlpha.signum() != 0 && hedgePnl.signum() != 0
                && strategyAlpha.signum() != hedgePnl.signum();

        return new Attribution(true, mode, strategyAlpha.toPlainString(), hedgePnl.toPlainString(),
                firmTotal.toPlainString(), totalFees.toPlainString(), masking, lines);
    }

    /** Cumulative fees per book for the running feed mode; empty when persistence is off. */
    private Map<String, BigDecimal> feesByBook(String feedMode) {
        JdbcTemplate t = jdbc.getIfAvailable();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (t == null) {
            return out;
        }
        try {
            t.query("select book_id, coalesce(sum(fee), 0) fees from fills where feed_mode = ? group by book_id",
                    (rs, i) -> out.put(rs.getString("book_id"), rs.getBigDecimal("fees")), feedMode);
        } catch (Exception e) {
            // best-effort: attribution P&L still renders without the fee column
        }
        return out;
    }
}

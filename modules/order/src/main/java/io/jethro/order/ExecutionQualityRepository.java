package io.jethro.order;

import io.jethro.domain.Fill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Persists and serves execution quality (ADR-0025 TCA): one row per fill with its
 * implementation-shortfall slippage vs the arrival price. Insert is idempotent on
 * order_id (at-least-once safe, invariant 6 — one fill per order in this venue model)
 * and never breaks the fill path: a failed write logs and is dropped from TCA only
 * (the fill itself is already the source of truth).
 */
public final class ExecutionQualityRepository implements TcaRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExecutionQualityRepository.class);

    private final JdbcTemplate jdbc;
    private final ExecutionCostSource costs; // rate-quoted flag — units of the slippage

    public ExecutionQualityRepository(JdbcTemplate jdbc, ExecutionCostSource costs) {
        this.jdbc = jdbc;
        this.costs = costs;
    }

    @Override
    public void record(Fill fill, BigDecimal arrivalPrice) {
        try {
            boolean rateQuoted = costs.costFor(fill.instrumentId().value()).rateQuoted();
            BigDecimal slippage = Tca.slippageBps(fill.side(), arrivalPrice, fill.price(), rateQuoted);
            jdbc.update("""
                    insert into execution_quality
                        (order_id, instrument, side, quantity, arrival_price, fill_price,
                         slippage_bps, rate_quoted, fee, filled_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (order_id) do nothing
                    """,
                    fill.orderId(), fill.instrumentId().value(), fill.side().name(), fill.quantity(),
                    arrivalPrice, fill.price(), slippage, rateQuoted, fill.fee(),
                    java.sql.Timestamp.from(fill.executedAt() != null ? fill.executedAt() : Instant.now()));
        } catch (Exception e) {
            log.warn("TCA record failed for {} (fill stands, TCA row lost): {}", fill.orderId(), e.toString());
        }
    }

    /** One fill's quality row. {@code slippageBps} is price-based (spread + delay + impact);
     *  {@code fee} is the separate cash commission — the two never mix. */
    public record Row(String orderId, String instrument, String side, BigDecimal quantity,
                      BigDecimal arrivalPrice, BigDecimal fillPrice, BigDecimal slippageBps,
                      boolean rateQuoted, BigDecimal fee, Instant filledAt) {
    }

    /** Per-instrument aggregate: fills, average and worst slippage (same-unit rows only). */
    public record Aggregate(String instrument, boolean rateQuoted, long fills,
                            BigDecimal avgSlippageBps, BigDecimal worstSlippageBps) {
    }

    public List<Row> recent(int limit) {
        return jdbc.query("""
                select order_id, instrument, side, quantity, arrival_price, fill_price,
                       slippage_bps, rate_quoted, fee, filled_at
                from execution_quality order by filled_at desc limit ?
                """, (rs, i) -> new Row(
                rs.getString("order_id"), rs.getString("instrument"), rs.getString("side"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("arrival_price"),
                rs.getBigDecimal("fill_price"), rs.getBigDecimal("slippage_bps"),
                rs.getBoolean("rate_quoted"), rs.getBigDecimal("fee"),
                rs.getTimestamp("filled_at").toInstant()), limit);
    }

    public List<Aggregate> aggregates() {
        return jdbc.query("""
                select instrument, rate_quoted, count(*) as fills,
                       avg(slippage_bps) as avg_bps, max(slippage_bps) as worst_bps
                from execution_quality group by instrument, rate_quoted
                order by avg_bps desc
                """, (rs, i) -> new Aggregate(
                rs.getString("instrument"), rs.getBoolean("rate_quoted"), rs.getLong("fills"),
                rs.getBigDecimal("avg_bps"), rs.getBigDecimal("worst_bps")));
    }
}

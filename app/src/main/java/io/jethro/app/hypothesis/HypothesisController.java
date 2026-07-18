package io.jethro.app.hypothesis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the AI hypothesis layer (ADR-0022/0027): the event ledger (every distinct
 * thesis with its quant verdict and, once scored, its outcome), the persisted executed records,
 * and the measured hit-rate per conviction. Decimals as strings (invariant 1). Empty when the
 * hypothesis layer is disabled or the model is unavailable.
 */
@RestController
public final class HypothesisController {

    /** One ledger event: a distinct thesis, retained (not just the current cycle). Newest first. */
    public record HypothesisDto(long timestampMillis, String instrumentId, String direction, String conviction,
                                String thesis, String verdict, boolean autonomous,
                                Boolean backtestSupports, String backtestPnl, Integer backtestTrades,
                                String note, String autonomyReason, String outcome, String outcomePnl) {
    }

    /** A persisted, executed hypothesis (led to an order) with its outcome once scored. */
    public record ExecutedDto(long timestampMillis, String instrumentId, String direction,
                              String horizon, String conviction, String thesis, String book,
                              String quantity, Boolean backtestSupported, String orderId, String orderStatus,
                              String entryPrice, long expiresAtMillis,
                              String outcome, String outcomePnl, String exitPrice) {
    }

    /** Measured performance of the model's conviction labels (ADR-0027). */
    public record StatsDto(String conviction, int total, int open, int wins, int losses, int flat,
                           double hitRate, String outcomePnl) {
    }

    /** The recent directional skew of live calls (ADR-0036) — descriptive, not a risk limit. */
    public record BalanceDto(int longs, int shorts, String skew) {
    }

    private final ObjectProvider<HypothesisLifecycle> lifecycle;

    public HypothesisController(ObjectProvider<HypothesisLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** Executed hypotheses (persisted): the ones the autonomy envelope acted on, newest first. */
    @GetMapping("/api/hypotheses/executed")
    public List<ExecutedDto> executed() {
        HypothesisLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        return live.executed().stream().map(r -> new ExecutedDto(
                r.timestampMillis(), r.instrumentId(), r.direction(), r.horizon(), r.conviction(),
                r.thesis(), r.book(), r.quantity() != null ? r.quantity().toPlainString() : null,
                r.backtestSupported(), r.orderId(), r.orderStatus(),
                r.entryPrice() != null ? r.entryPrice().toPlainString() : null,
                r.expiresAtMillis(), r.outcome(),
                r.outcomePnl() != null ? r.outcomePnl().toPlainString() : null,
                r.exitPrice() != null ? r.exitPrice().toPlainString() : null)).toList();
    }

    /** Hit-rate/expectancy by conviction — what the model's labels are measurably worth. */
    @GetMapping("/api/hypotheses/stats")
    public List<StatsDto> stats() {
        HypothesisLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        return live.outcomeStats().stream().map(s -> {
            int scored = s.wins() + s.losses() + s.flat();
            double hitRate = scored == 0 ? 0 : (double) s.wins() / scored;
            return new StatsDto(s.conviction(), s.total(), s.open(), s.wins(), s.losses(), s.flat(),
                    Math.round(hitRate * 1000) / 1000.0, s.outcomePnl().toPlainString());
        }).toList();
    }

    /** The live long/short balance of proposed calls (ADR-0036) — surfaces the model's skew. */
    @GetMapping("/api/hypotheses/balance")
    public BalanceDto balance() {
        HypothesisLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return new BalanceDto(0, 0, "balanced");
        }
        var b = live.directionBalance();
        return new BalanceDto(b.longs(), b.shorts(), b.skew());
    }

    @GetMapping("/api/hypotheses")
    public List<HypothesisDto> hypotheses() {
        HypothesisLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        return live.ledger().stream().map(e -> new HypothesisDto(
                e.timestampMillis(), e.instrumentId(), e.direction(), e.conviction(), e.thesis(),
                e.verdict(), e.autoTraded(), e.backtestSupports(), e.backtestPnl(), e.backtestTrades(),
                e.note(), e.autonomyReason(), e.outcome(), e.outcomePnl())).toList();
    }
}

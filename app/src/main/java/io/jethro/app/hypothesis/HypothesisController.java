package io.jethro.app.hypothesis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Read-only view of the latest LLM hypotheses and the quant layer's verdict on each
 * (ADR-0022): the model's thesis alongside the deterministic sizing/gate decision — so the
 * reasoning is visible, not just the admissible ones on the feed. Decimals as strings
 * (invariant 1). Empty when the hypothesis layer is disabled or the model is unavailable.
 */
@RestController
public final class HypothesisController {

    /** One ledger event: a distinct thesis, retained (not just the current cycle). Newest first. */
    public record HypothesisDto(long timestampMillis, String instrumentId, String direction, String conviction,
                                String thesis, String verdict, boolean autonomous,
                                Boolean backtestSupports, String backtestPnl, Integer backtestTrades,
                                String note, String autonomyReason) {
    }

    /** A persisted, executed hypothesis (led to an order) — sticky across cycles and restarts. */
    public record ExecutedDto(long timestampMillis, String instrumentId, String direction,
                              String horizon, String conviction, String thesis, String book,
                              String quantity, Boolean backtestSupported, String orderId, String orderStatus) {
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
                r.backtestSupported(), r.orderId(), r.orderStatus())).toList();
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
                e.note(), e.autonomyReason())).toList();
    }
}

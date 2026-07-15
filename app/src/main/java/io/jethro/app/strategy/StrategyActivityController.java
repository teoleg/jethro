package io.jethro.app.strategy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the deterministic (momentum) strategy's recent actions — entries and
 * risk-reducing exits, each with its reason — so the algo side is visible next to the AI
 * hypothesis side. Empty when the strategy isn't auto-executing (no orders to show).
 */
@RestController
public final class StrategyActivityController {

    public record ActivityDto(long timestampMillis, String kind, String instrumentId, String book,
                              String side, String quantity, String reason, String orderStatus) {
    }

    private final ObjectProvider<StrategyLifecycle> lifecycle;

    public StrategyActivityController(ObjectProvider<StrategyLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @GetMapping("/api/strategy/actions")
    public List<ActivityDto> actions() {
        StrategyLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        return live.recentActivity().stream().map(a -> new ActivityDto(
                a.timestampMillis(), a.kind(), a.instrumentId(), a.book(),
                a.side(), a.quantity(), a.reason(), a.orderStatus())).toList();
    }
}

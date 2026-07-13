package io.jethro.app.hypothesis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the latest LLM hypotheses and the quant layer's verdict on each
 * (ADR-0022): the model's thesis alongside the deterministic sizing/gate decision — so the
 * reasoning is visible, not just the admissible ones on the feed. Decimals as strings
 * (invariant 1). Empty when the hypothesis layer is disabled or the model is unavailable.
 */
@RestController
public final class HypothesisController {

    public record HypothesisDto(String instrumentId, String direction, String horizon, String conviction,
                                String thesis, List<String> sources, String verdict, String book,
                                String quantity, String price, String note) {
    }

    private final ObjectProvider<HypothesisLifecycle> lifecycle;

    public HypothesisController(ObjectProvider<HypothesisLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @GetMapping("/api/hypotheses")
    public List<HypothesisDto> hypotheses() {
        HypothesisLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        return live.latest().stream().map(e -> new HypothesisDto(
                e.hypothesis().instrumentId(),
                e.hypothesis().direction().name(),
                e.hypothesis().horizon().name(),
                e.hypothesis().conviction().name(),
                e.hypothesis().thesis(),
                e.hypothesis().sources(),
                e.verdict().name(),
                e.book(),
                e.quantity() != null ? e.quantity().toPlainString() : null,
                e.price() != null ? e.price().toPlainString() : null,
                e.note())).toList();
    }
}

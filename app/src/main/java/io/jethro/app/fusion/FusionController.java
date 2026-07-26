package io.jethro.app.fusion;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ADR-0055 fused target book (phase 4, shadow): the combined forecast, target, current position and
 * netted delta per instrument, with each source's contribution — so "combine all sources before any
 * order" is visible before it is ever switched on. Read-only; no number here places an order (ADR-0016).
 */
@RestController
public final class FusionController {

    private final ObjectProvider<FusionLifecycle> fusion;

    public FusionController(ObjectProvider<FusionLifecycle> fusion) {
        this.fusion = fusion;
    }

    @GetMapping("/api/fusion/targets")
    public FusionLifecycle.TargetBook targets() {
        FusionLifecycle f = fusion.getIfAvailable();
        return f == null
                ? new FusionLifecycle.TargetBook(0, false, 0, java.util.Map.of(), java.util.List.of(), null, 1.0, 0, 0, 1.0, 1.0)
                : f.book();
    }
}

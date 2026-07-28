package io.jethro.app.signal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only per-signal health (ADR-0055 phase 1): rolling hit-rate + average return per source, so the
 * evidence behind future fusion weights is visible. Measurement only — no number here sizes or gates.
 */
@RestController
public final class SignalTelemetryController {

    private final ObjectProvider<SignalTelemetry> telemetry;

    public SignalTelemetryController(ObjectProvider<SignalTelemetry> telemetry) {
        this.telemetry = telemetry;
    }

    @GetMapping("/api/signals/telemetry")
    public List<SignalScoring.Stats> telemetry() {
        SignalTelemetry t = telemetry.getIfAvailable();
        return t == null ? List.of() : t.stats();
    }

    /**
     * Observations the bad-print exclusion kept out of the expectancy above (ADR-0109) — evidence
     * removed from the gate that governs exposure, counted rather than dropped in silence. Empty is the
     * healthy state; a rising count says the mark stream changed, not the alpha.
     */
    @GetMapping("/api/signals/discards")
    public List<SignalTelemetry.Discard> discards() {
        SignalTelemetry t = telemetry.getIfAvailable();
        return t == null ? List.of() : t.discards();
    }
}

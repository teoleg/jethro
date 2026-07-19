package io.jethro.app.strategy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST surface for the live strategy-tuning panel (ADR-0052). Reads the effective dials, sets/resets
 * overrides (validated + audited in {@link StrategyControl}), and exposes the change history. Unlike
 * the sim panel (ADR-0031) this is NOT feed-mode gated — tuning is meaningful in any mode and cannot
 * route a real-broker order (auto-exec stays sim-gated, ADR-0019). A bad value returns 400.
 */
@RestController
public final class StrategyControlController {

    private final StrategyControl control;

    public StrategyControlController(StrategyControl control) {
        this.control = control;
    }

    /** Full panel state: every dial with its config default, effective value, and override flag. */
    public record PanelState(List<StrategyControl.DialState> dials, int overrides) {
    }

    @GetMapping("/api/strategy/control")
    public PanelState state() {
        return panel(control.state());
    }

    public record SetRequest(String param, String value, String actor, String note) {
    }

    @PostMapping("/api/strategy/control/set")
    public PanelState set(@RequestBody SetRequest req) {
        if (req == null || req.param() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "param is required");
        }
        try {
            return panel(control.set(req.param(), req.value(), req.actor(), req.note()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    public record ResetRequest(String param, String actor) {
    }

    @PostMapping("/api/strategy/control/reset")
    public PanelState reset(@RequestBody ResetRequest req) {
        if (req == null || req.param() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "param is required");
        }
        try {
            return panel(control.reset(req.param(), req.actor()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/api/strategy/control/reset-all")
    public PanelState resetAll(@RequestBody(required = false) ResetRequest req) {
        return panel(control.resetAll(req == null ? null : req.actor()));
    }

    @GetMapping("/api/strategy/control/history")
    public List<StrategyOverrideStore.Change> history(@RequestParam(defaultValue = "50") int limit) {
        return control.recentChanges(Math.max(1, Math.min(500, limit)));
    }

    private static PanelState panel(List<StrategyControl.DialState> dials) {
        int overrides = (int) dials.stream().filter(StrategyControl.DialState::overridden).count();
        return new PanelState(dials, overrides);
    }
}

package io.jethro.app.trading;

import io.jethro.messaging.FeedMode;
import io.jethro.messaging.Provenance;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.marketdata.sim.MarketRegime;
import io.jethro.trading.marketdata.sim.SimControl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * The sim control panel's REST surface (ADR-0031) — live dials over the correlated simulator
 * for staging scenarios and watching the models react. <b>Hard-gated to {@code feedMode ==
 * SIM}</b> (ADR-0029): every endpoint 403s unless the running feed is the sim, so no dial can
 * ever perturb a live or replay tape (the gate is the tested invariant, not a comment). All
 * values here are statistical scenario parameters, never money.
 */
@RestController
public final class SimControlController {

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;
    private final ObjectProvider<RefDataRepository> refData;

    public SimControlController(ObjectProvider<TradingCoreLifecycle> tradingCore,
                                ObjectProvider<RefDataRepository> refData) {
        this.tradingCore = tradingCore;
        this.refData = refData;
    }

    // ---- read ----

    public record InstrumentDialsDto(String id, String name, double driftBias,
                                     double volMultiplier, double volumeScale) {
    }

    public record ControlStateDto(boolean enabled, boolean active, double speedMultiplier,
                                  boolean paused, String regimeOverride, List<String> regimes,
                                  List<InstrumentDialsDto> instruments) {
    }

    @GetMapping("/api/sim/control")
    public ControlStateDto state() {
        SimControl control = control();
        Map<String, String> names = instrumentNames();
        List<InstrumentDialsDto> instruments = control.controllableIds().stream()
                .map(id -> new InstrumentDialsDto(id, names.getOrDefault(id, id),
                        control.driftBiasFor(id), control.volMultiplierFor(id),
                        control.volumeScaleFor(id)))
                .toList();
        MarketRegime override = control.regimeOverride();
        return new ControlStateDto(true, control.anyDialActive(), control.speedMultiplier(),
                control.paused(), override == null ? "AUTO" : override.name(),
                regimeNames(), instruments);
    }

    // ---- global dials ----

    public record SpeedRequest(double multiplier) {
    }

    @PostMapping("/api/sim/control/speed")
    public ControlStateDto speed(@RequestBody SpeedRequest req) {
        control().setSpeedMultiplier(req.multiplier());
        return state();
    }

    public record PauseRequest(boolean paused) {
    }

    @PostMapping("/api/sim/control/pause")
    public ControlStateDto pause(@RequestBody PauseRequest req) {
        control().setPaused(req.paused());
        return state();
    }

    public record RegimeRequest(String regime) {
    }

    @PostMapping("/api/sim/control/regime")
    public ControlStateDto regime(@RequestBody RegimeRequest req) {
        String value = req.regime();
        if (value == null || value.isBlank() || "AUTO".equalsIgnoreCase(value)) {
            control().overrideRegime(null);
        } else {
            control().overrideRegime(parseRegime(value));
        }
        return state();
    }

    public record ReseedRequest(Long seed) {
    }

    @PostMapping("/api/sim/control/reseed")
    public ControlStateDto reseed(@RequestBody(required = false) ReseedRequest req) {
        long seed = req != null && req.seed() != null ? req.seed() : System.nanoTime();
        control().reseed(seed);
        return state();
    }

    @PostMapping("/api/sim/control/reset")
    public ControlStateDto reset() {
        control().resetAll();
        return state();
    }

    // ---- per-instrument dials ----

    public record ValueRequest(double value) {
    }

    @PostMapping("/api/sim/instrument/{id}/nudge")
    public ControlStateDto nudge(@PathVariable String id, @RequestBody ValueRequest req) {
        guardInstrument(control(), id).nudge(id, req.value());
        return state();
    }

    @PostMapping("/api/sim/instrument/{id}/drift")
    public ControlStateDto drift(@PathVariable String id, @RequestBody ValueRequest req) {
        guardInstrument(control(), id).setDriftBias(id, req.value());
        return state();
    }

    @PostMapping("/api/sim/instrument/{id}/vol")
    public ControlStateDto vol(@PathVariable String id, @RequestBody ValueRequest req) {
        guardInstrument(control(), id).setVolMultiplier(id, req.value());
        return state();
    }

    @PostMapping("/api/sim/instrument/{id}/volume")
    public ControlStateDto volume(@PathVariable String id, @RequestBody ValueRequest req) {
        guardInstrument(control(), id).setVolumeScale(id, req.value());
        return state();
    }

    // ---- gating + helpers ----

    /** The live control, or 403 when the feed isn't the correlated sim under SIM mode. The
     *  two-part gate (mode AND presence) is deliberate: {@code feedMode} is the invariant-8
     *  firewall, and a null control means even in SIM the feed is the legacy/non-correlated sim. */
    private SimControl control() {
        if (Provenance.mode() != FeedMode.SIM) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "sim control panel is available only in SIM feed mode");
        }
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        SimControl control = core != null ? core.simControl() : null;
        if (control == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "sim control panel requires the correlated sim engine");
        }
        return control;
    }

    private static SimControl guardInstrument(SimControl control, String id) {
        if (!control.controllableIds().contains(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "not a controllable sim instrument: " + id);
        }
        return control;
    }

    private MarketRegime parseRegime(String value) {
        try {
            return MarketRegime.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown regime: " + value);
        }
    }

    private static List<String> regimeNames() {
        return java.util.Arrays.stream(MarketRegime.values()).map(Enum::name).toList();
    }

    private Map<String, String> instrumentNames() {
        RefDataRepository rd = refData.getIfAvailable();
        return rd != null ? rd.instrumentAttribute("name") : Map.of();
    }
}

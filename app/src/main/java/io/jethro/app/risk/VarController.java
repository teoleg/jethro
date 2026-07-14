package io.jethro.app.risk;

import io.jethro.trading.riskpnl.VarMath;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The distributional-risk surface (ADR-0027): historical-simulation VaR/ES and the firm
 * circuit breaker's state + operator reset. Decimals as strings (invariant 1). Honest when
 * it can't measure: insufficient history or persistence-off return a note, never a fake 0.
 */
@RestController
public final class VarController {

    public record VarDto(String var95, String es95, String var99, int observations,
                         String coveredExposure, String skippedExposure, String note) {
    }

    public record BreakerDto(boolean halted, String reason, Long trippedAtMillis) {
    }

    private final ObjectProvider<VarService> varService;
    private final TradingHaltSwitch halt;

    public VarController(ObjectProvider<VarService> varService, TradingHaltSwitch halt) {
        this.varService = varService;
        this.halt = halt;
    }

    @GetMapping("/api/var")
    public VarDto var() {
        VarService service = varService.getIfAvailable();
        if (service == null) {
            return new VarDto("0.00", "0.00", "0.00", 0, "0.00", "0.00",
                    "persistence off — no daily-close history to measure against");
        }
        VarMath.VarResult r = service.compute();
        return new VarDto(r.var95().toPlainString(), r.es95().toPlainString(), r.var99().toPlainString(),
                r.observations(), r.coveredExposure().toPlainString(),
                r.skippedExposure().toPlainString(), r.note());
    }

    @GetMapping("/api/breaker")
    public BreakerDto breaker() {
        var current = halt.current();
        return new BreakerDto(halt.isHalted(),
                current != null ? current.reason() : null,
                current != null ? current.trippedAtMillis() : null);
    }

    /** Operator reset (ADR-0027). If the drawdown condition still holds, the monitor re-trips
     *  within one cycle — a reset is an acknowledgement, not an override of the math. */
    @PostMapping("/api/breaker/reset")
    public BreakerDto reset() {
        boolean cleared = halt.reset();
        if (cleared) {
            org.slf4j.LoggerFactory.getLogger(VarController.class)
                    .warn("FIRM BREAKER RESET by operator — auto-execution re-enabled");
        }
        return breaker();
    }
}

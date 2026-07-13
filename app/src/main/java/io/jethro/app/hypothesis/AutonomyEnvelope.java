package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.Hypothesis;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The deterministic risk envelope for bounded autonomy (ADR-0022): decides whether an
 * already-admissible, quant-sized hypothesis may auto-execute (simulated, ADR-0019) or must
 * stay a human-review card. Pure and code-only — the model never influences this, and it can
 * only ever be MORE restrictive than the pre-trade guardrail that already passed. Cooldown and
 * the enabled/sim-broker gates are handled by the lifecycle; this checks the static envelope.
 *
 * <p>Admits only if ALL hold: verdict ADMISSIBLE, the backtest supports the instrument,
 * conviction ≥ the configured minimum, order notional ≤ the (tight) autonomy cap, and the
 * instrument is whitelisted (empty whitelist = all). Any miss returns a reason (not admitted).
 */
public final class AutonomyEnvelope {

    private final HypothesisProperties.Autonomy cfg;

    public AutonomyEnvelope(HypothesisProperties.Autonomy cfg) {
        this.cfg = cfg;
    }

    /** @return empty if the order may auto-execute, else the reason it stays human-in-loop. */
    public Optional<String> rejectionReason(HypothesisEvaluator.Evaluated e, BigDecimal multiplier) {
        if (e.verdict() != HypothesisEvaluator.Verdict.ADMISSIBLE) {
            return Optional.of("not admissible");
        }
        if (e.backtest() == null || !e.backtest().supports()) {
            return Optional.of("backtest does not support the thesis");
        }
        if (!convictionMeetsMin(e.hypothesis().conviction())) {
            return Optional.of("conviction " + e.hypothesis().conviction() + " below minimum " + minConviction());
        }
        BigDecimal notional = e.quantity().multiply(e.price()).multiply(multiplier);
        if (notional.compareTo(cfg.maxOrderNotionalOrDefault()) > 0) {
            return Optional.of("order notional " + notional.toPlainString()
                    + " exceeds autonomy cap " + cfg.maxOrderNotionalOrDefault().toPlainString());
        }
        var whitelist = cfg.whitelistOrEmpty();
        if (!whitelist.isEmpty() && !whitelist.contains(e.hypothesis().instrumentId())) {
            return Optional.of(e.hypothesis().instrumentId() + " not on the autonomy whitelist");
        }
        return Optional.empty();
    }

    private boolean convictionMeetsMin(Hypothesis.Conviction conviction) {
        return conviction.ordinal() >= minConviction().ordinal();
    }

    /** Parses the configured minimum; an unknown value falls back to the safest (HIGH). */
    private Hypothesis.Conviction minConviction() {
        try {
            return Hypothesis.Conviction.valueOf(cfg.minConvictionOrDefault().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Hypothesis.Conviction.HIGH;
        }
    }
}

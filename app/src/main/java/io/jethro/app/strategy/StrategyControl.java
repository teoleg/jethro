package io.jethro.app.strategy;

import io.jethro.trading.algo.strategy.SignalParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Live, DB-persisted strategy tuning (ADR-0052). Resolves the <em>effective</em> value of each
 * {@code jethro.strategy.*} dial as: the runtime override if one is set, else the
 * {@code application.properties} value. The live strategy reads through this bean every cycle, so a
 * sweep of e.g. {@code threshold-sigmas} takes effect immediately and the book's response is visible
 * without a restart.
 *
 * <p>Two guarantees hold regardless of any override:
 * <ul>
 *   <li>the OOS backtest/selector never see this bean — they build detectors from static config, so
 *       edge measurement stays seeded and reproducible (ADR-0027);</li>
 *   <li>auto-execution stays sim-gated (ADR-0019) — no dial can route a real-broker order.</li>
 * </ul>
 *
 * <p>Implements {@link SignalParams} so it can be handed straight to the live detector for the four
 * signal knobs. Writes validate bounds and append an audit row (the provenance a live-set risk dial
 * requires); the in-memory snapshot is authoritative for reads even if the durable write fails.
 */
public final class StrategyControl implements SignalParams {

    private static final Logger log = LoggerFactory.getLogger(StrategyControl.class);

    /** Value kinds, for parse + bounds validation on write. */
    public enum Kind { NUMBER, INT, BOOL }

    /**
     * A tunable dial: its config key, a UI label + group, its kind and (for numbers) inclusive
     * bounds, whether it gates money/risk (surfaced so the UI can warn), and how to read its config
     * default. {@code min}/{@code max} are ignored for BOOL.
     */
    public enum Dial {
        THRESHOLD_SIGMAS("threshold-sigmas", "Signal threshold (σ)", "Sensitivity", Kind.NUMBER, 0.1, 10, false,
                p -> fmt(p.thresholdSigmasOrDefault())),
        MIN_SIGNAL_BPS("min-signal-bps", "Dust floor (bps)", "Sensitivity", Kind.NUMBER, 0, 100, false,
                p -> p.minSignalBpsOrDefault().toPlainString()),
        LOOKBACK("lookback", "Window (returns)", "Sensitivity", Kind.INT, 2, 500, false,
                p -> Integer.toString(p.lookback())),
        VOLUME_CONFIRM_MIN("volume-confirm-min", "Volume confirm (×ADV)", "Sensitivity", Kind.NUMBER, 0, 5, false,
                p -> fmt(p.volumeConfirmMinOrDefault())),
        AUTO_COOLDOWN_SECONDS("auto-cooldown-seconds", "Re-entry cooldown (s)", "Turnover", Kind.INT, 0, 86_400, false,
                p -> Long.toString(p.autoCooldownSeconds())),
        TARGET_NOTIONAL("target-notional", "Target notional ($)", "Sizing", Kind.NUMBER, 0, 1e9, true,
                p -> p.targetNotional().toPlainString()),
        RISK_BUDGET_DAILY("risk-budget-daily", "Daily risk budget ($/day)", "Sizing", Kind.NUMBER, 0, 1e9, true,
                p -> p.riskBudgetDailyOrDefault().toPlainString()),
        REGIME_VOLATILE_SCALE("regime-volatile-scale", "Risk-off entry scale (×)", "Sizing", Kind.NUMBER, 0, 5, true,
                p -> p.regimeVolatileScaleOrDefault().toPlainString()),
        VOL_REFERENCE_BPS("vol-reference-bps", "Vol reference (bps)", "Sizing", Kind.NUMBER, 0.1, 1000, false,
                p -> fmt(p.volReferenceBpsOrDefault())),
        LIQUIDITY_CAP_ADV_FRACTION("liquidity-cap-adv-fraction", "Liquidity cap (frac ADV)", "Sizing", Kind.NUMBER, 0, 1, false,
                p -> fmt(p.liquidityCapAdvFractionOrDefault())),
        MAX_POSITION_NOTIONAL("max-position-notional", "Max position notional ($)", "Exposure", Kind.NUMBER, 0, 1e9, true,
                p -> p.maxPositionNotionalOrDefault().toPlainString()),
        MAX_ORDER_NOTIONAL("max-order-notional", "Max order notional ($, default)", "Exposure", Kind.NUMBER, 0, 1e9, true,
                p -> p.maxOrderNotionalOrDefault().toPlainString()),
        STOP_LOSS_PCT("stop-loss-pct", "Stop-loss (fraction)", "Exits", Kind.NUMBER, 0, 1, true,
                p -> nullable(p.stopLossPctOrNull())),
        TAKE_PROFIT_PCT("take-profit-pct", "Take-profit (fraction)", "Exits", Kind.NUMBER, 0, 1, true,
                p -> nullable(p.takeProfitPctOrNull())),
        ALLOW_SHORT("allow-short", "Allow shorts", "Behaviour", Kind.BOOL, 0, 1, true,
                p -> Boolean.toString(p.allowShortOrDefault())),
        DERISK_ON_LOSS_CAP("derisk-on-loss-cap", "De-risk at loss cap", "Behaviour", Kind.BOOL, 0, 1, true,
                p -> Boolean.toString(p.deriskOnLossCapOrDefault())),
        AUTO_EXECUTE("auto-execute", "Auto-execute (SIM only)", "Behaviour", Kind.BOOL, 0, 1, true,
                p -> Boolean.toString(p.autoExecute()));

        final String key;
        final String label;
        final String group;
        final Kind kind;
        final double min;
        final double max;
        final boolean gatesRisk;
        final Function<StrategyProperties, String> defaultFn;

        Dial(String key, String label, String group, Kind kind, double min, double max,
             boolean gatesRisk, Function<StrategyProperties, String> defaultFn) {
            this.key = key;
            this.label = label;
            this.group = group;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.gatesRisk = gatesRisk;
            this.defaultFn = defaultFn;
        }

        static Dial byKey(String key) {
            for (Dial d : values()) {
                if (d.key.equals(key)) {
                    return d;
                }
            }
            throw new IllegalArgumentException("unknown strategy dial: " + key);
        }
    }

    /** One dial as shown to the UI: config default, effective value, and whether it's overridden. */
    public record DialState(String key, String label, String group, String kind, double min, double max,
                            boolean gatesRisk, String defaultValue, String effective, boolean overridden) {
    }

    private final StrategyProperties defaults;
    private final StrategyOverrideStore store;
    private volatile Map<String, String> overrides;

    public StrategyControl(StrategyProperties defaults, StrategyOverrideStore store) {
        this.defaults = defaults;
        this.store = store != null ? store : StrategyOverrideStore.NONE;
        this.overrides = new LinkedHashMap<>(this.store.load());
        if (!overrides.isEmpty()) {
            log.info("strategy control (ADR-0052): {} live override(s) loaded — {}", overrides.size(), overrides);
        }
    }

    // ---- raw override access + parse fallbacks --------------------------------------------------

    private String raw(Dial d) {
        return overrides.get(d.key);
    }

    private double numberOr(Dial d, double fallback) {
        String v = raw(d);
        if (v == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(v.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException e) {
            return fallback; // a corrupt override never wedges a read — config default stands
        }
    }

    private BigDecimal decimalOr(Dial d, BigDecimal fallback) {
        String v = raw(d);
        if (v == null) {
            return fallback;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private boolean boolOr(Dial d, boolean fallback) {
        String v = raw(d);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    // ---- effective typed getters (the live strategy reads these) --------------------------------

    @Override
    public int lookback() {
        return (int) Math.round(numberOr(Dial.LOOKBACK, defaults.lookback()));
    }

    @Override
    public double thresholdSigmas() {
        return numberOr(Dial.THRESHOLD_SIGMAS, defaults.thresholdSigmasOrDefault());
    }

    @Override
    public BigDecimal minSignalBps() {
        return decimalOr(Dial.MIN_SIGNAL_BPS, defaults.minSignalBpsOrDefault());
    }

    @Override
    public double volumeConfirmMin() {
        return numberOr(Dial.VOLUME_CONFIRM_MIN, defaults.volumeConfirmMinOrDefault());
    }

    public long autoCooldownSeconds() {
        return (long) Math.max(0, numberOr(Dial.AUTO_COOLDOWN_SECONDS, defaults.autoCooldownSeconds()));
    }

    public BigDecimal targetNotional() {
        return decimalOr(Dial.TARGET_NOTIONAL, defaults.targetNotional());
    }

    public BigDecimal riskBudgetDaily() {
        return decimalOr(Dial.RISK_BUDGET_DAILY, defaults.riskBudgetDailyOrDefault());
    }

    public BigDecimal regimeVolatileScale() {
        return decimalOr(Dial.REGIME_VOLATILE_SCALE, defaults.regimeVolatileScaleOrDefault());
    }

    public double volReferenceBps() {
        return numberOr(Dial.VOL_REFERENCE_BPS, defaults.volReferenceBpsOrDefault());
    }

    public double liquidityCapAdvFraction() {
        return numberOr(Dial.LIQUIDITY_CAP_ADV_FRACTION, defaults.liquidityCapAdvFractionOrDefault());
    }

    public BigDecimal maxPositionNotional() {
        return decimalOr(Dial.MAX_POSITION_NOTIONAL, defaults.maxPositionNotionalOrDefault());
    }

    /** Per-class order cap: a static class override in config wins; otherwise the live scalar cap. */
    public BigDecimal maxOrderNotionalFor(String assetClass) {
        Map<String, BigDecimal> byClass = defaults.maxOrderNotionalByClass();
        if (assetClass != null && byClass != null && byClass.get(assetClass) != null) {
            return byClass.get(assetClass); // per-class caps stay config-only (structural, ADR-0052)
        }
        return decimalOr(Dial.MAX_ORDER_NOTIONAL, defaults.maxOrderNotionalOrDefault());
    }

    /** Stop-loss as a positive fraction, or null when disabled (override 0 disables). */
    public BigDecimal stopLossPctOrNull() {
        BigDecimal v = decimalOr(Dial.STOP_LOSS_PCT, defaults.stopLossPctOrNull());
        return v != null && v.signum() > 0 ? v : null;
    }

    public BigDecimal takeProfitPctOrNull() {
        BigDecimal v = decimalOr(Dial.TAKE_PROFIT_PCT, defaults.takeProfitPctOrNull());
        return v != null && v.signum() > 0 ? v : null;
    }

    public boolean allowShort() {
        return boolOr(Dial.ALLOW_SHORT, defaults.allowShortOrDefault());
    }

    public boolean deriskOnLossCap() {
        return boolOr(Dial.DERISK_ON_LOSS_CAP, defaults.deriskOnLossCapOrDefault());
    }

    public boolean autoExecute() {
        return boolOr(Dial.AUTO_EXECUTE, defaults.autoExecute());
    }

    // ---- write path (validated + audited) -------------------------------------------------------

    /**
     * Set an override after validating kind + bounds. Persists (best-effort) and appends an audit
     * row — the change record IS the provenance for a live-set risk dial. Returns the new state.
     */
    public synchronized List<DialState> set(String key, String value, String actor, String note) {
        Dial d = Dial.byKey(key);
        String normalized = validate(d, value);
        String old = effectiveString(d);
        Map<String, String> next = new LinkedHashMap<>(overrides);
        next.put(d.key, normalized);
        overrides = next;
        store.save(d.key, normalized, actor == null || actor.isBlank() ? "ui" : actor.trim(), note, old);
        log.info("strategy dial {} set {} → {} by {} ({})", d.key, old, normalized,
                actor == null ? "ui" : actor, note == null ? "" : note);
        return state();
    }

    /** Clear an override, reverting the dial to its config default. */
    public synchronized List<DialState> reset(String key, String actor) {
        Dial d = Dial.byKey(key);
        String old = effectiveString(d);
        Map<String, String> next = new LinkedHashMap<>(overrides);
        boolean had = next.remove(d.key) != null;
        overrides = next;
        if (had) {
            store.delete(d.key, actor == null || actor.isBlank() ? "ui" : actor.trim(), old);
            log.info("strategy dial {} reset to config default {} by {}", d.key, d.defaultFn.apply(defaults), actor);
        }
        return state();
    }

    /** Clear every override (reset the whole panel to config). */
    public synchronized List<DialState> resetAll(String actor) {
        for (String key : List.copyOf(overrides.keySet())) {
            reset(key, actor);
        }
        return state();
    }

    /** Validate a candidate value against the dial's kind + bounds; returns the normalized string. */
    private String validate(Dial d, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(d.key + " requires a value");
        }
        String v = value.trim();
        switch (d.kind) {
            case BOOL -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(d.key + " must be true or false");
                }
                return v.toLowerCase(Locale.ROOT);
            }
            case INT -> {
                long n;
                try {
                    n = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(d.key + " must be an integer");
                }
                bounds(d, n);
                return Long.toString(n);
            }
            default -> {
                BigDecimal n;
                try {
                    n = new BigDecimal(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(d.key + " must be a number");
                }
                bounds(d, n.doubleValue());
                return n.toPlainString();
            }
        }
    }

    private void bounds(Dial d, double n) {
        if (n < d.min || n > d.max) {
            throw new IllegalArgumentException(
                    d.key + " out of range [" + fmt(d.min) + ", " + fmt(d.max) + "]: " + fmt(n));
        }
    }

    // ---- state for the UI -----------------------------------------------------------------------

    private String effectiveString(Dial d) {
        String v = raw(d);
        return v != null ? v : d.defaultFn.apply(defaults);
    }

    public List<DialState> state() {
        List<DialState> out = new ArrayList<>();
        for (Dial d : Dial.values()) {
            String def = d.defaultFn.apply(defaults);
            String eff = effectiveString(d);
            out.add(new DialState(d.key, d.label, d.group, d.kind.name(), d.min, d.max, d.gatesRisk,
                    def, eff, overrides.containsKey(d.key)));
        }
        return out;
    }

    public List<StrategyOverrideStore.Change> recentChanges(int limit) {
        return store.recentChanges(limit);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String nullable(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }
}

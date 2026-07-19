package io.jethro.trading.algo.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Per-instrument strategy selection. Wraps the concrete algos (momentum, mean-reversion) and, for
 * each instrument, forwards only the signals of the algo chosen for it — trading <b>nothing</b> for
 * a name whose choice is {@link #NO_TRADE}.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>ADR-0043 (OOS-only):</b> a pure {@code Function<instrumentId, algo>} chooser reads the
 *       out-of-sample medians and picks each name's better algo (or NO_TRADE). Regime-blind.</li>
 *   <li><b>ADR-0044 (regime-aware):</b> a price-derived {@link TrendDetector} picks the algo — TREND
 *       → momentum, CHOP → mean-reversion, inferred from observable prices only (invariant 8, no sim
 *       oracle) — and an OOS <i>edge gate</i> may VETO that pick to NO_TRADE when the trend-matched
 *       algo has a measured non-positive median (a detector false-positive can't force a losing
 *       trade). The detector decides <i>which</i> algo and <i>when to switch</i>; the gate decides
 *       <i>whether there's edge at all</i>.</li>
 * </ul>
 *
 * <p>Every wrapped algo is evaluated each cycle regardless of the choice, so all of them keep their
 * rolling windows warm — a later switch for an instrument is instant and correct, not cold-started.
 * An instrument with no opinion yet falls back to {@code defaultAlgo}, so behaviour is exactly the
 * old single-algo strategy until the evidence/window arrives. This class stays a dependency-free
 * {@link Strategy}; the OOS chooser/gate are pure functions supplied by the app layer.
 */
public final class SelectingStrategy implements Strategy {

    /** The sentinel for "measured, but no algo has an edge — do not trade this name". */
    public static final String NO_TRADE = "none";

    private final Map<String, Strategy> byAlgo;      // e.g. {"momentum": …, "mean-reversion": …}
    private final String defaultAlgo;

    // ADR-0043 mode (detector == null): OOS chooser decides the algo directly.
    private final Function<String, String> oosChooser;

    // ADR-0044 mode (detector != null): the detector picks trend/chop → algo, the gate may veto.
    private final TrendDetector detector;
    private final String trendAlgo;
    private final String chopAlgo;
    private final BiFunction<String, String, String> edgeGate; // (id, candidateAlgo) → candidate | NO_TRADE

    /** ADR-0043 constructor: OOS-only per-instrument selection (regime-blind). */
    public SelectingStrategy(Map<String, Strategy> byAlgo, Function<String, String> chooserFor,
                             String defaultAlgo) {
        this.byAlgo = Map.copyOf(byAlgo);
        this.oosChooser = chooserFor;
        this.defaultAlgo = defaultAlgo;
        this.detector = null;
        this.trendAlgo = null;
        this.chopAlgo = null;
        this.edgeGate = null;
    }

    /** ADR-0044 constructor: a price-derived detector picks the algo, an OOS edge gate may veto it. */
    public SelectingStrategy(Map<String, Strategy> byAlgo, TrendDetector detector,
                             String trendAlgo, String chopAlgo, String defaultAlgo,
                             BiFunction<String, String, String> edgeGate) {
        this.byAlgo = Map.copyOf(byAlgo);
        this.detector = detector;
        this.trendAlgo = trendAlgo;
        this.chopAlgo = chopAlgo;
        this.defaultAlgo = defaultAlgo;
        this.edgeGate = edgeGate;
        this.oosChooser = null;
    }

    @Override
    public List<TradeSignal> evaluate(List<Observation> observations) {
        if (detector != null) {
            detector.update(observations); // price-derived regime, from the same marks the algos see
        }
        List<TradeSignal> out = new ArrayList<>();
        // Deterministic order: evaluate every algo (keeps all windows warm), keep only the signals
        // whose instrument selected THAT algo.
        for (Map.Entry<String, Strategy> e : byAlgo.entrySet()) {
            String algo = e.getKey();
            for (TradeSignal s : e.getValue().evaluate(observations)) {
                if (algo.equals(chosenFor(s.instrumentId()))) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** The algo actually run for an instrument this cycle: NO_TRADE suppresses all; null/blank/unknown
     *  → {@code defaultAlgo} (an unknown algo name never silently disables trading). */
    private String chosenFor(String instrumentId) {
        String chosen = detector != null ? regimeChoice(instrumentId) : oosChooser.apply(instrumentId);
        if (chosen == null || chosen.isBlank()) {
            return defaultAlgo;
        }
        if (NO_TRADE.equals(chosen) || byAlgo.containsKey(chosen)) {
            return chosen;
        }
        return defaultAlgo;
    }

    /** ADR-0044: detector regime → candidate algo, then the OOS edge gate may veto to NO_TRADE. */
    private String regimeChoice(String instrumentId) {
        String candidate = switch (detector.regimeFor(instrumentId)) {
            case TREND -> trendAlgo;
            case CHOP -> chopAlgo;
            case UNKNOWN -> defaultAlgo; // window still warming → the configured default trades
        };
        return edgeGate != null ? edgeGate.apply(instrumentId, candidate) : candidate;
    }

    /** The price-derived detector when in regime-aware mode, else null (for the API/UI). */
    public TrendDetector detector() {
        return detector;
    }

    @Override
    public String name() {
        return "selecting";
    }
}

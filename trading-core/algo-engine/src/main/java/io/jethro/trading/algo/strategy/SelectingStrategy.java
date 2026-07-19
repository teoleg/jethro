package io.jethro.trading.algo.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-instrument strategy selection (ADR-0043): wraps the concrete algos (momentum,
 * mean-reversion) and, for each instrument, forwards only the signals of the algo the
 * out-of-sample harness picked for it — trading <b>nothing</b> for an instrument whose chosen algo
 * is {@link #NO_TRADE} (neither had a measured edge). An instrument with no selection yet (before
 * the first OOS run completes) falls back to {@code defaultAlgo}, so behaviour is exactly the old
 * single-algo strategy until the evidence arrives.
 *
 * <p>Every wrapped algo is evaluated each cycle regardless of the choice, so all of them keep their
 * rolling windows warm — a later switch to a different algo for an instrument is instant and
 * correct, not cold-started. The chooser is a pure function supplied by the app layer (the selector
 * reads the OOS medians); this class stays a dependency-free {@link Strategy}.
 */
public final class SelectingStrategy implements Strategy {

    /** The chooser's sentinel for "measured, but no algo has an edge — do not trade this name". */
    public static final String NO_TRADE = "none";

    private final Map<String, Strategy> byAlgo;      // e.g. {"momentum": …, "mean-reversion": …}
    private final Function<String, String> chooserFor; // instrumentId → algo name | NO_TRADE
    private final String defaultAlgo;

    public SelectingStrategy(Map<String, Strategy> byAlgo, Function<String, String> chooserFor,
                             String defaultAlgo) {
        this.byAlgo = Map.copyOf(byAlgo);
        this.chooserFor = chooserFor;
        this.defaultAlgo = defaultAlgo;
    }

    @Override
    public List<TradeSignal> evaluate(List<Observation> observations) {
        List<TradeSignal> out = new ArrayList<>();
        // Deterministic order: evaluate every algo (keeps all windows warm), keep only the signals
        // whose instrument selected THAT algo.
        for (Map.Entry<String, Strategy> e : byAlgo.entrySet()) {
            String algo = e.getKey();
            for (TradeSignal s : e.getValue().evaluate(observations)) {
                String chosen = chosenFor(s.instrumentId());
                if (algo.equals(chosen)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** The algo selected for an instrument: the chooser's answer, or {@code defaultAlgo} when it
     *  has no opinion yet (null/blank/unknown-algo). NO_TRADE is returned as-is (suppresses all). */
    private String chosenFor(String instrumentId) {
        String chosen = chooserFor.apply(instrumentId);
        if (chosen == null || chosen.isBlank()) {
            return defaultAlgo;
        }
        if (NO_TRADE.equals(chosen) || byAlgo.containsKey(chosen)) {
            return chosen;
        }
        return defaultAlgo; // an unknown algo name never silently disables trading
    }

    @Override
    public String name() {
        return "selecting";
    }
}

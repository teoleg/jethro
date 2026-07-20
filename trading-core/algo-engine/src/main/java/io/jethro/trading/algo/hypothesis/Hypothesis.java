package io.jethro.trading.algo.hypothesis;

import io.jethro.domain.Side;

import java.util.List;

/**
 * A structured, <b>number-free</b> trading thesis proposed by the LLM layer (ADR-0022).
 * This is the hard boundary that keeps invariant 7 / ADR-0016 intact: the model emits a
 * direction and an ordinal conviction — never a size, price, expected return, or any number
 * that feeds a position, PnL, or risk figure. The deterministic quant layer
 * ({@code HypothesisEvaluator}) turns an admissible hypothesis into a sized, guardrailed
 * candidate; {@code conviction} is used only to prioritise, never as a multiplier in sizing.
 *
 * @param instrumentId the instrument the thesis is about (validated against the master).
 * @param direction    BUY = bullish/long view, SELL = bearish/short view.
 * @param horizon       expected holding horizon (ordinal label, not a number).
 * @param conviction    ordinal strength — for ordering/prioritisation only.
 * @param thesis        one-sentence rationale, in the model's words.
 * @param sources       ids of the narrative items that informed it (audit trail).
 * @param eventKey      the rare market EVENT this call reacts to (ADR-0054), classified by the model
 *                      into a categorical descriptor for deterministic dedup. May be null on legacy paths.
 */
public record Hypothesis(String hypothesisId, String instrumentId, Side direction,
                         Horizon horizon, Conviction conviction, String thesis, List<String> sources,
                         EventKey eventKey) {

    public enum Horizon { INTRADAY, SWING, POSITION }

    public enum Conviction { LOW, MEDIUM, HIGH }

    /**
     * The rare market EVENT a hypothesis reacts to (ADR-0054). One event spawns many reworded
     * headlines, so identity belongs to the event, not the prose: the model CLASSIFIES it into a
     * small categorical descriptor — a catalyst type, the entity it concerns, and its date — never
     * prose or a number (ADR-0016 / invariant 7). The deterministic dedup guard keys on {@link #token()}
     * so one event fires ONCE however the thesis is worded. Unclassifiable ⇒ {@code OTHER}, and the
     * guard falls back to the news-id/thesis key — degrade the guard, never remove it.
     */
    public record EventKey(Catalyst catalyst, String entity, java.time.LocalDate eventDate) {

        public enum Catalyst { FOMC, EARNINGS, MERGER_ACQUISITION, GUIDANCE, RATING, MACRO_PRINT, PRICE_ACTION, OTHER }

        /** True when the model named a real, discrete catalyst — the guard can dedup on the event. */
        public boolean classified() {
            return catalyst != null && catalyst != Catalyst.OTHER;
        }

        /** Canonical dedup token {@code CATALYST|entity|date}; entity defaults to MARKET, date optional. */
        public String token() {
            String cat = (catalyst == null ? Catalyst.OTHER : catalyst).name();
            String ent = entity == null || entity.isBlank() ? "MARKET" : entity.trim();
            String day = eventDate == null ? "" : eventDate.toString();
            return cat + "|" + ent + "|" + day;
        }
    }
}

package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.util.Map;

/**
 * ADR-0140 — durable home for the ADR-0080 aim (the desk's intended position per name).
 *
 * <p>Derived state, never a source of truth: every value here is recomputable from the planner's own
 * target sequence, and {@code fills} remains the source of truth for actual positions (invariant 3).
 * A store that fails must never stop the desk trading — every implementation is best-effort and falls
 * back to the in-memory path, which is exactly the pre-ADR-0140 behaviour.
 */
public interface AimStore {

    /** Every persisted aim for the current feed mode. Empty when there is nothing to restore. */
    Map<String, BigDecimal> load();

    /** Replace the persisted aims for the current feed mode with {@code aims}. */
    void save(Map<String, BigDecimal> aims);
}

package io.jethro.app.discovery;

/**
 * The write side of the ADR-0060 promotion audit log — the one method the promotion service needs.
 * Kept a narrow interface (implemented by {@link UniversePromotionRepository}) so the service is unit
 * tested against an in-memory recorder, with no JDBC.
 */
public interface PromotionAudit {

    /** Append one gate decision row (PROPOSED / PROMOTED / EVICTED / REJECTED). */
    void record(long atMillis, String sessionEpoch, String feedMode, String instrumentId, String action,
                String outcome, Double score, Integer distinctDays, String sources, String reason, boolean dryRun);
}

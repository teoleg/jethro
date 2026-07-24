package io.jethro.app.trading;

import java.util.Optional;

/**
 * A daily-history provider for the boot history seed (ADR-0038): grounds the hedger's covariance in
 * real cross-asset relationships from tick one. Dev/demo capability (ADR-0023) — a free, ToS-limited
 * source used to CAPTURE real history, never a production data path. Implementations fetch one
 * symbol's cleaned daily series; they never fabricate data (empty on any error, disclosed).
 */
public interface HistoryClient {

    /** One symbol's cleaned daily series: dates (epoch-day), closes (real units), volumes (0 if none). */
    record History(long[] epochDays, double[] closes, long[] volumes) {
    }

    /** Fetches and parses one symbol's daily history; empty on any error (never fabricates data). */
    Optional<History> fetch(String symbol);
}

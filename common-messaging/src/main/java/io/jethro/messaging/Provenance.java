package io.jethro.messaging;

/**
 * The current data provenance stamped onto every event's {@link EventMeta} (ADR-0029): which
 * feed mode the process is running (SIM/LIVE/REPLAY) and the session epoch. One mode per session;
 * a sim&lt;-&gt;live switch calls {@link #configure} with a new epoch so downstream state never
 * aggregates across modes. Configured once at startup (see the app's ProvenanceConfig).
 *
 * <p>Static holder rather than injected because {@link EventMeta} is built in the hot path across
 * modules; unconfigured it reports SIM with an empty epoch (correct default for offline sim/tests).
 */
public final class Provenance {

    private static volatile FeedMode mode = FeedMode.SIM;
    private static volatile String epoch = "";

    private Provenance() {
    }

    public static void configure(FeedMode feedMode, String sessionEpoch) {
        mode = feedMode == null ? FeedMode.SIM : feedMode;
        epoch = sessionEpoch == null ? "" : sessionEpoch;
    }

    public static FeedMode mode() {
        return mode;
    }

    public static String epoch() {
        return epoch;
    }
}

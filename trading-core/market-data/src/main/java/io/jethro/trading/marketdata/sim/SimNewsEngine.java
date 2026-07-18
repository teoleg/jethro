package io.jethro.trading.marketdata.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates the sim's own news (ADR-0034) so news is the CAUSE of a move, not a coincidence.
 * Driven by the sim's tick loop: each tick it may fire a news event for an instrument (a sentiment
 * sign + a magnitude), applying a correlated shock through {@link SimControl} — a repricing jump,
 * a decaying momentum drift, and a volume surge — and recording a templated headline the narrative
 * feed surfaces to the model. Seedable: a fixed seed ⇒ the same news, shocks and tape (ADR-0009).
 *
 * <p>SIM-only by construction — it exists only inside the sim adapters, which run only under
 * {@code feedMode == SIM}; it can never touch a live tape.
 */
public final class SimNewsEngine {

    /** A generated news event: a stable id, the instrument it moved, its sign, and a headline. */
    public record SimNewsEvent(String id, String instrumentId, int sign, String headline, long tick) {
    }

    private static final int MAX_RECENT = 24;
    private static final double MIN_MAGNITUDE = 0.005; // 0.5% repricing jump
    private static final double MAX_MAGNITUDE = 0.030; // 3.0%

    private static final String[] BULLISH = {
            "rallies on upbeat guidance", "jumps after a broker upgrade", "climbs on a strong earnings beat",
            "gains as the demand outlook brightens", "rises on a positive analyst note"};
    private static final String[] BEARISH = {
            "slides on a guidance cut", "drops after a downgrade", "slumps as results disappoint",
            "falls on demand worries", "sells off on a profit warning"};

    private final String[] ids;
    private final SimControl control;
    private final SplittableRandom rnd;
    private final double perTickProbability;
    private final int horizonTicks;
    private final Deque<SimNewsEvent> recent = new ArrayDeque<>();
    private long seq;

    public SimNewsEngine(long seed, List<String> instrumentIds, SimControl control,
                         double perTickProbability, int horizonTicks) {
        this.ids = instrumentIds.toArray(String[]::new);
        this.control = control;
        this.rnd = new SplittableRandom(seed);
        this.perTickProbability = perTickProbability;
        this.horizonTicks = Math.max(1, horizonTicks);
    }

    /** May fire a news event this tick — applies the shock and records the headline. Called on the
     *  sim-feed thread; synchronized so the narrative feed can drain {@link #recent()} concurrently. */
    public synchronized void maybeFire(long tick) {
        if (ids.length == 0 || rnd.nextDouble() >= perTickProbability) {
            return;
        }
        int i = rnd.nextInt(ids.length);
        int sign = rnd.nextBoolean() ? 1 : -1;
        double magnitude = MIN_MAGNITUDE + (MAX_MAGNITUDE - MIN_MAGNITUDE) * rnd.nextDouble();
        control.fireNewsShock(ids[i], sign, magnitude, horizonTicks);
        SimNewsEvent event = new SimNewsEvent("sim-news-" + (seq++), ids[i], sign,
                headline(ids[i], sign), tick);
        recent.addLast(event);
        while (recent.size() > MAX_RECENT) {
            recent.removeFirst();
        }
    }

    /** Recent generated events, oldest first (the narrative feed maps these to its items). */
    public synchronized List<SimNewsEvent> recent() {
        return new ArrayList<>(recent);
    }

    private String headline(String instrumentId, int sign) {
        String[] templates = sign >= 0 ? BULLISH : BEARISH;
        return instrumentId + " " + templates[rnd.nextInt(templates.length)];
    }
}

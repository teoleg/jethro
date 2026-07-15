package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.NarrativeItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A seedable, offline stand-in for a real news/econ/earnings feed (ADR-0022 defers a real
 * provider; ADR-0009 spirit — local dev never depends on a paid feed). Headlines correlate
 * with the market {@code regime} the sim is in, so the qualitative story the LLM reads
 * lines up with the price action it sees: a TREND_UP regime prints bullish macro, a
 * VOLATILE/shock regime prints a "surprise" headline. Clearly fixtures — labelled sim, and
 * every item carries a sentiment tag, never a number (invariant 1 doesn't apply; nothing
 * here feeds money math — the LLM only reads it).
 *
 * <p>Deterministic: same seed + same regime sequence → same headlines (ADR-0009), so a
 * replay reproduces the narrative the hypotheses were built on.
 */
public final class SimNarrativeFeed implements NarrativeFeed {

    private static final int MAX_RECENT = 8;

    private static final String[] MACRO_BULLISH = {
            "CPI comes in below expectations — soft-landing hopes lift risk",
            "Fed signals patience; rate-cut odds tick up",
            "Jobs report beats but wages cool — goldilocks read",
            "Strong Treasury auction; yields ease"};
    private static final String[] MACRO_BEARISH = {
            "Hot CPI print reignites rate-hike fears",
            "Fed official strikes a hawkish tone on inflation",
            "Weak auction; long-end yields jump",
            "Sticky services inflation dents rate-cut hopes"};
    private static final String[] MACRO_NEUTRAL = {
            "Markets quiet ahead of key data",
            "Range-bound trade into the close",
            "Mixed signals leave rates little changed"};
    private static final String[] MACRO_SURPRISE = {
            "SURPRISE: inflation shock jolts rates and equities",
            "Unexpected policy headline roils markets",
            "Risk-off spikes as a tail headline hits the tape"};
    private static final String[] NAME_BULLISH = {
            "%s beats on earnings and guides higher",
            "Analysts upgrade %s on improving demand",
            "%s wins a large contract; shares in focus"};
    private static final String[] NAME_BEARISH = {
            "%s misses estimates and cuts guidance",
            "%s downgraded on margin concerns",
            "%s slides after a cautious outlook"};

    private final SplittableRandom rng;
    private final Deque<NarrativeItem> recent = new ArrayDeque<>();
    private long counter;

    public SimNarrativeFeed(long seed) {
        this.rng = new SplittableRandom(seed);
    }

    /**
     * Advances the feed one step under the current regime and returns the recent items
     * (newest last). {@code singleNameInstruments} are the ids eligible for earnings/news
     * (equities/futures); may be empty.
     */
    @Override
    public synchronized List<NarrativeItem> poll(String regime, List<String> singleNameInstruments, long nowMillis) {
        double emitChance = switch (regime) {
            case "VOLATILE", "RISK_OFF", "INFLATION_SHOCK" -> 1.0; // shock regimes make headlines
            case "TREND_UP", "TREND_DOWN" -> 0.7;
            default -> 0.3; // CALM and anything else
        };
        if (rng.nextDouble() < emitChance) {
            add(newItem(regime, singleNameInstruments, nowMillis));
        }
        return List.copyOf(recent);
    }

    private NarrativeItem newItem(String regime, List<String> names, long nowMillis) {
        NarrativeItem.Sentiment bias = switch (regime) {
            case "TREND_UP" -> NarrativeItem.Sentiment.BULLISH;
            case "TREND_DOWN", "RISK_OFF", "INFLATION_SHOCK" -> NarrativeItem.Sentiment.BEARISH;
            case "VOLATILE" -> rng.nextBoolean() ? NarrativeItem.Sentiment.BULLISH : NarrativeItem.Sentiment.BEARISH;
            default -> NarrativeItem.Sentiment.NEUTRAL;
        };
        boolean shockRegime = "VOLATILE".equals(regime) || "RISK_OFF".equals(regime)
                || "INFLATION_SHOCK".equals(regime);
        String id = "sim-news-" + (counter++);
        // A shock regime is a macro surprise; otherwise ~half the items are single-name.
        boolean singleName = !names.isEmpty() && bias != NarrativeItem.Sentiment.NEUTRAL
                && !shockRegime && rng.nextBoolean();
        if (singleName) {
            String instrument = names.get(rng.nextInt(names.size()));
            String[] pool = bias == NarrativeItem.Sentiment.BULLISH ? NAME_BULLISH : NAME_BEARISH;
            String headline = String.format(pool[rng.nextInt(pool.length)], instrument);
            NarrativeItem.Category cat = headline.contains("earnings") || headline.contains("guides")
                    ? NarrativeItem.Category.EARNINGS : NarrativeItem.Category.NEWS;
            return new NarrativeItem(id, nowMillis, cat, instrument, bias, headline);
        }
        String[] pool = switch (bias) {
            case BULLISH -> shockRegime ? MACRO_SURPRISE : MACRO_BULLISH;
            case BEARISH -> shockRegime ? MACRO_SURPRISE : MACRO_BEARISH;
            case NEUTRAL -> MACRO_NEUTRAL;
        };
        return new NarrativeItem(id, nowMillis, NarrativeItem.Category.MACRO, null, bias, pool[rng.nextInt(pool.length)]);
    }

    private void add(NarrativeItem item) {
        if (recent.size() == MAX_RECENT) {
            recent.removeFirst();
        }
        recent.addLast(item);
    }
}

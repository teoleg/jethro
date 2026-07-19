package io.jethro.app.social;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Corroboration gate (ADR-0050 §3) — the core adversarial control. A subject is PROMOTED to an
 * advisory signal only when ≥ {@code k} DISTINCT CREDIBLE channels mention it in the window; a single
 * post, a burst from one source, or a crowd of low-credibility throwaways never moves conviction. A
 * high-mention subject that fails corroboration and is dominated by low-credibility posts is flagged
 * {@code manipulationSuspected} — a pump-and-dump tell that is surfaced but NEVER traded (a
 * social-derived subject can never originate an order anyway, ADR-0049). Direction is the majority of
 * a deterministic bullish/bearish lexicon. Pure and static for exact-value testing.
 */
public final class CorroborationGate {

    private static final Set<String> BULLISH = Set.of(
            "buy", "long", "moon", "breakout", "rocket", "calls", "bullish", "up", "squeeze", "rip", "rally");
    private static final Set<String> BEARISH = Set.of(
            "sell", "short", "dump", "crash", "puts", "bearish", "down", "tank", "rug", "collapse", "fade");

    private CorroborationGate() {
    }

    /**
     * @param kept        posts that survived the spam filter (credible AND low-credibility)
     * @param universe    valid instrument ids
     * @param sectorOf    instrument id → GICS sector ({@code hedge_group}), or "—"
     * @param channels    the credibility registry
     * @param k           distinct credible channels required to promote a subject
     * @param burstThreshold mentions at/above which an uncorroborated, low-credibility-dominated
     *                       subject is flagged as suspected manipulation
     */
    public static List<SocialSignal> evaluate(List<SocialPost> kept, Set<String> universe,
                                              Function<String, String> sectorOf, SocialChannels channels,
                                              int k, int burstThreshold) {
        Map<String, List<SocialPost>> byInstrument = new LinkedHashMap<>();
        for (SocialPost p : kept) {
            for (String id : Cashtags.extract(p.text(), universe)) {
                byInstrument.computeIfAbsent(id, x -> new ArrayList<>()).add(p);
            }
        }
        List<SocialSignal> out = new ArrayList<>();
        byInstrument.forEach((id, posts) -> {
            Set<String> credibleChannels = new LinkedHashSet<>();
            Set<String> allChannels = new LinkedHashSet<>();
            int bull = 0;
            int bear = 0;
            for (SocialPost p : posts) {
                allChannels.add(p.channel());
                if (channels.isCredible(p)) {
                    credibleChannels.add(p.channel());
                }
                int s = sentiment(p.text());
                bull += s > 0 ? 1 : 0;
                bear += s < 0 ? 1 : 0;
            }
            boolean corroborated = credibleChannels.size() >= k;
            // Suspected manipulation: a real burst that did NOT corroborate and is mostly low-cred
            // (fewer than half the channels are credible) — the pump-and-dump profile.
            boolean manipulation = !corroborated && posts.size() >= burstThreshold
                    && credibleChannels.size() * 2 < allChannels.size();
            if (!corroborated && !manipulation) {
                return; // just watching — not enough independent credible corroboration yet
            }
            String direction = bull == bear ? "NEUTRAL" : (bull > bear ? "BULLISH" : "BEARISH");
            out.add(new SocialSignal(id, sectorOf.apply(id), direction, credibleChannels.size(),
                    posts.size(), manipulation, posts.get(0).text()));
        });
        return out;
    }

    /** +1 per bullish word, −1 per bearish word; the sign is the post's coarse direction. */
    static int sentiment(String text) {
        if (text == null) {
            return 0;
        }
        int score = 0;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (BULLISH.contains(w)) {
                score++;
            } else if (BEARISH.contains(w)) {
                score--;
            }
        }
        return score;
    }
}

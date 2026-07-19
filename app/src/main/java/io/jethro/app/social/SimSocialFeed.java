package io.jethro.app.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seedable synthetic social feed (ADR-0050, ADR-0034 spirit) — exercises the WHOLE pipeline offline,
 * incl. the adversarial paths, so the filters and the corroboration gate are visibly working in sim
 * exactly as they would in prod. Each poll emits a deterministic-by-seed mix:
 * <ul>
 *   <li>a <b>corroborated event</b> — the same ticker from 2–3 TRUSTED/STANDARD credible channels
 *       (this is what SHOULD promote);</li>
 *   <li>a <b>pump</b> — a burst of UNTRUSTED throwaway accounts (unverified, few followers, new)
 *       shilling one ticker with varied copypasta (should be FLAGGED, never promoted);</li>
 *   <li><b>noise</b> — a single credible post (not enough to corroborate alone), an exact-duplicate
 *       pair (dropped as copypasta), and a multi-cashtag shill (dropped as cashtag-spam).</li>
 * </ul>
 * Channel names match the curated registry seed. Posts carry only text + credibility signals — no
 * sim-attached sector/subject tag (invariant 8: the classifier reads text, not the sim's answer).
 */
public final class SimSocialFeed implements SocialFeed {

    private static final String[] CREDIBLE = {"wire:Reuters", "wire:Bloomberg", "st:AnalystJane", "st:MacroMike"};
    private static final String[] BULL = {"breakout", "buy", "rally", "squeeze", "calls"};
    private static final String[] BEAR = {"dump", "short", "fade", "puts", "tank"};

    private final Random rnd;
    private long seq;

    public SimSocialFeed(long seed) {
        this.rnd = new Random(seed);
    }

    @Override
    public synchronized List<SocialPost> poll(List<String> universe, long now) {
        List<SocialPost> out = new ArrayList<>();
        if (universe == null || universe.isEmpty()) {
            return out;
        }
        // 1) A corroborated legit event: 2–3 credible channels, same ticker, same direction.
        String t1 = pick(universe);
        boolean bull = rnd.nextBoolean();
        int credibleN = 2 + rnd.nextInt(2); // 2 or 3
        for (int i = 0; i < credibleN; i++) {
            String ch = CREDIBLE[i % CREDIBLE.length];
            out.add(credible(ch, "$" + t1 + " " + word(bull) + " — desk sees follow-through", now));
        }
        // 2) A pump on a DIFFERENT ticker: many untrusted throwaways, varied copypasta, bullish.
        String t2 = pick(universe);
        if (t2.equals(t1)) {
            t2 = pick(universe);
        }
        int pumpN = 5 + rnd.nextInt(4);
        for (int i = 0; i < pumpN; i++) {
            String rockets = "🚀".repeat(1 + rnd.nextInt(4)); // varied so it's a burst, not exact dup
            out.add(throwaway("tg:pump" + i, "$" + t2 + " to the moon " + rockets + " buy now!!", now));
        }
        // 3) Noise: one lone credible post (cannot corroborate alone).
        out.add(credible(CREDIBLE[rnd.nextInt(CREDIBLE.length)],
                "$" + pick(universe) + " " + word(rnd.nextBoolean()) + " watching", now));
        // 4) Exact-duplicate copypasta pair (second is dropped).
        String dupTicker = pick(universe);
        String dup = "$" + dupTicker + " easy money buy buy buy";
        out.add(throwaway("tg:spamA", dup, now));
        out.add(throwaway("tg:spamB", dup, now));
        // 5) Multi-cashtag shill (dropped as cashtag-spam).
        StringBuilder shill = new StringBuilder("hot list: ");
        for (int i = 0; i < 6; i++) {
            shill.append('$').append(pick(universe)).append(' ');
        }
        out.add(throwaway("tg:spamC", shill.toString(), now));
        return out;
    }

    private SocialPost credible(String channel, String text, long now) {
        // Trusted wires need no author floors; STANDARD (st:*) authors are verified with a real record.
        boolean standard = channel.startsWith("st:");
        return new SocialPost("sim-" + (seq++), "sim", channel, channel,
                standard ? 40_000 + rnd.nextInt(60_000) : 500_000, standard, 900 + rnd.nextInt(1200), now, text);
    }

    private SocialPost throwaway(String channel, String text, long now) {
        return new SocialPost("sim-" + (seq++), "sim", channel, "anon" + rnd.nextInt(9999),
                rnd.nextInt(50), false, rnd.nextInt(20), now, text); // unverified, few followers, new
    }

    private String pick(List<String> universe) {
        return universe.get(rnd.nextInt(universe.size()));
    }

    private String word(boolean bull) {
        return bull ? BULL[rnd.nextInt(BULL.length)] : BEAR[rnd.nextInt(BEAR.length)];
    }
}

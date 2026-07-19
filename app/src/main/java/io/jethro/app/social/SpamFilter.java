package io.jethro.app.social;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic spam/bot pre-filter (ADR-0050 §1): the queue edge, before the corroboration gate and
 * before any (future) SLM. Drops structural garbage — exact/near-duplicate copypasta and cashtag-spam
 * (a post shilling many tickers) — so the scarce model and the advisory layer never see it. Every drop
 * is counted with a reason (data-path rule: never silent). Credibility is NOT dropped here — a
 * low-credibility post still flows to the corroboration gate, which needs it to detect a pump-and-dump
 * burst (many low-credibility mentions that never corroborate). Pure.
 */
public final class SpamFilter {

    public enum Drop { DUPLICATE, CASHTAG_SPAM }

    public record Result(List<SocialPost> kept, Map<Drop, Integer> dropped) {
    }

    private final int maxCashtags;

    public SpamFilter(int maxCashtags) {
        this.maxCashtags = maxCashtags;
    }

    /**
     * @param seenTextHashes normalized-text fingerprints already seen (cross-batch memory); newly-kept
     *                       fingerprints are ADDED, so a later identical post is a duplicate.
     */
    public Result filter(List<SocialPost> posts, Set<String> seenTextHashes) {
        List<SocialPost> kept = new ArrayList<>();
        Map<Drop, Integer> dropped = new EnumMap<>(Drop.class);
        for (SocialPost p : posts) {
            String fp = fingerprint(p.text());
            if (seenTextHashes.contains(fp)) {
                dropped.merge(Drop.DUPLICATE, 1, Integer::sum);
                continue;
            }
            // "Too many tickers" = shill, counted over ALL cashtags (not just configured ones).
            if (Cashtags.extractCashtags(p.text()).size() > maxCashtags) {
                dropped.merge(Drop.CASHTAG_SPAM, 1, Integer::sum);
                continue;
            }
            seenTextHashes.add(fp);
            kept.add(p);
        }
        return new Result(kept, dropped);
    }

    /** Normalized fingerprint so identical copypasta collides: lowercased, punctuation stripped,
     *  whitespace collapsed. A cheap near-dup; semantic dedup (ADR-0035) is a later layer. */
    static String fingerprint(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9$ ]", "").replaceAll("\\s+", " ").trim();
    }
}

package io.jethro.app.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Fans one poll across several {@link SocialFeed} sources and merges their posts (ADR-0050 §multi-
 * source, ADR-0045 §3 — "N sources from day one"). One source failing never sinks the cycle: its
 * error is logged and the others still contribute. The merged stream flows through the same spam
 * filter + corroboration gate, so a subject seen on two DIFFERENT sources naturally corroborates.
 */
public final class CompositeSocialFeed implements SocialFeed {

    private static final Logger log = LoggerFactory.getLogger(CompositeSocialFeed.class);

    private final List<SocialFeed> sources;

    public CompositeSocialFeed(List<SocialFeed> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public List<SocialPost> poll(List<String> equityUniverse, long nowMillis) {
        List<SocialPost> merged = new ArrayList<>();
        for (SocialFeed source : sources) {
            try {
                merged.addAll(source.poll(equityUniverse, nowMillis));
            } catch (Exception e) {
                log.warn("social source {} failed this cycle: {}", source.getClass().getSimpleName(), e.toString());
            }
        }
        return merged;
    }

    @Override
    public List<SocialSourceStatus> health() {
        List<SocialSourceStatus> all = new ArrayList<>();
        for (SocialFeed source : sources) {
            all.addAll(source.health());
        }
        return all;
    }
}

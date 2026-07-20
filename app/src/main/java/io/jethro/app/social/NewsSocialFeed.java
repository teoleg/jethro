package io.jethro.app.social;

import io.jethro.app.discovery.DiscoveryLifecycle;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bridges RSS news into the ADR-0050 social pipeline (dual-purpose RSS): the SAME headlines discovery
 * already fetched are re-emitted as credible {@link SocialPost}s so news flows through the identical
 * spam → credibility → corroboration → signal machinery as social, and a name seen on news AND social
 * (or on two outlets) cross-corroborates into one advisory signal. Reuses discovery's retained
 * headlines — no second HTTP fetch — and appends each item's resolved tickers as {@code $cashtags} so
 * the gate's {@link Cashtags#extract} picks them up unchanged.
 *
 * <p>News outlets are inherently credible (unlike adversarial social), so posts carry
 * high synthetic author signals to clear the STANDARD floors; official vs media tiering is set in the
 * channel registry (Official=TRUSTED, media=STANDARD). Advisory only — never a number into risk, never
 * an order (ADR-0049 / invariant 7).
 */
public final class NewsSocialFeed implements SocialFeed {

    // Curated outlets, not adversarial accounts — give them author signals that clear the STANDARD
    // credibility floors so a media outlet counts toward corroboration (official outlets are TRUSTED
    // in the registry and count regardless). These are provenance markers, not numbers into risk.
    private static final int SYNTHETIC_FOLLOWERS = 5_000_000;
    private static final int SYNTHETIC_AGE_DAYS = 3650;

    private final ObjectProvider<DiscoveryLifecycle> discovery;

    public NewsSocialFeed(ObjectProvider<DiscoveryLifecycle> discovery) {
        this.discovery = discovery;
    }

    @Override
    public List<SocialPost> poll(List<String> equityUniverse, long nowMillis) {
        DiscoveryLifecycle live = discovery.getIfAvailable();
        if (live == null) {
            return List.of();
        }
        List<SocialPost> out = new ArrayList<>();
        for (DiscoveryLifecycle.Headline h : live.recentNews()) {
            // Append the already-resolved tickers as $cashtags so the corroboration gate extracts them
            // with no news-specific code; the headline text stays for the direction lexicon + display.
            String text = h.title();
            if (h.tickers() != null && !h.tickers().isEmpty()) {
                text = text + " " + h.tickers().stream().map(t -> "$" + t).collect(Collectors.joining(" "));
            }
            String key = h.url() == null || h.url().isBlank() ? h.title() : h.url();
            String id = "news-" + h.outlet() + "-" + Integer.toHexString(key.hashCode());
            // channel = the outlet, so distinct outlets are distinct corroborating channels.
            out.add(new SocialPost(id, "news", h.outlet(), h.outlet(),
                    SYNTHETIC_FOLLOWERS, true, SYNTHETIC_AGE_DAYS, h.atMillis(), text));
        }
        return out;
    }

    @Override
    public List<SocialSourceStatus> health() {
        DiscoveryLifecycle live = discovery.getIfAvailable();
        return live == null ? List.of() : List.of(live.newsStatus());
    }
}

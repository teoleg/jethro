package io.jethro.uigateway;

/**
 * Deterministic trigger floor, v0 (ADR-0017): plain-code rules that ALWAYS surface —
 * no model between a trigger and the screen. Domain modules bring their own rules
 * later (risk thresholds, budget burn); here: mark staleness.
 */
public final class AttentionRules {

    private final AttentionFeed feed;
    private final long staleAfterMillis;

    public AttentionRules(AttentionFeed feed, long staleAfterMillis) {
        this.feed = feed;
        this.staleAfterMillis = staleAfterMillis;
    }

    /** Evaluate against current marks; upserts stale-mark alerts, resolves recovered ones. */
    public void evaluate(java.util.List<MarkState.MarkDto> marks, long nowMillis) {
        for (var mark : marks) {
            String id = "stale:" + mark.instrumentId();
            if (mark.ageMillis() > staleAfterMillis) {
                feed.upsert(new AttentionFeed.AttentionItem(
                        id, nowMillis, AttentionFeed.Severity.WARN, "stale-mark",
                        mark.instrumentId() + " mark is stale",
                        "Last mark " + mark.price() + " is " + (mark.ageMillis() / 1000)
                                + "s old (source " + mark.source() + "). Feed problem or halted instrument.",
                        "/market"));
            } else {
                feed.resolve(id);
            }
        }
    }
}

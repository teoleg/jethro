package io.jethro.uigateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Last-value marks as seen via the md.marks topic — the UI's view of the market. */
public final class MarkState {

    /** Prices kept as strings: exact decimal at the boundary (invariant 1), no FP en route to
     *  JSON. {@code bid}/{@code ask} null when the feed carries no quote data (ADR-0025). */
    public record MarkDto(String instrumentId, String price, String bid, String ask, String source,
                          long providerTimestampMillis, long ageMillis) {
    }

    private record Entry(String price, String bid, String ask, String source,
                         long providerTimestampMillis, long receivedAtMillis) {
    }

    private final ConcurrentHashMap<String, Entry> marks = new ConcurrentHashMap<>();

    public void update(String instrumentId, String price, String source, long providerTimestampMillis) {
        update(instrumentId, price, null, null, source, providerTimestampMillis);
    }

    public void update(String instrumentId, String price, String bid, String ask,
                       String source, long providerTimestampMillis) {
        // receivedAtMillis is local receive time: FRESHNESS (is the feed alive), which is distinct
        // from the provider timestamp's DELAY. A ~15-min-delayed provider (Yahoo) is still fresh if
        // we just received it, so ageMillis must not treat delayed-but-live data as stale (ADR-0023).
        marks.put(instrumentId, new Entry(price, bid, ask, source, providerTimestampMillis,
                System.currentTimeMillis()));
    }

    public List<MarkDto> snapshot(long nowMillis) {
        List<MarkDto> result = new ArrayList<>(marks.size());
        marks.forEach((id, e) -> result.add(new MarkDto(
                id, e.price(), e.bid(), e.ask(), e.source(), e.providerTimestampMillis(),
                Math.max(0, nowMillis - e.receivedAtMillis())))); // age = since last received, not since source time
        result.sort((a, b) -> a.instrumentId().compareTo(b.instrumentId()));
        return result;
    }
}

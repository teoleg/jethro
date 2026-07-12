package io.jethro.uigateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Last-value marks as seen via the md.marks topic — the UI's view of the market. */
public final class MarkState {

    /** Price kept as string: exact decimal at the boundary (invariant 1), no FP en route to JSON. */
    public record MarkDto(String instrumentId, String price, String source,
                          long providerTimestampMillis, long ageMillis) {
    }

    private record Entry(String price, String source, long providerTimestampMillis) {
    }

    private final ConcurrentHashMap<String, Entry> marks = new ConcurrentHashMap<>();

    public void update(String instrumentId, String price, String source, long providerTimestampMillis) {
        marks.put(instrumentId, new Entry(price, source, providerTimestampMillis));
    }

    public List<MarkDto> snapshot(long nowMillis) {
        List<MarkDto> result = new ArrayList<>(marks.size());
        marks.forEach((id, e) -> result.add(new MarkDto(
                id, e.price(), e.source(), e.providerTimestampMillis(),
                Math.max(0, nowMillis - e.providerTimestampMillis()))));
        result.sort((a, b) -> a.instrumentId().compareTo(b.instrumentId()));
        return result;
    }
}

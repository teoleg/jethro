package io.jethro.uigateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The attention feed (ADR-0017). Items keyed by id: deterministic triggers use stable
 * ids (e.g. "stale:AAPL") so a persisting condition updates one card instead of
 * spamming; agent commentary uses unique ids. Models can add items; nothing here lets
 * them remove or reorder trigger items — the floor is code.
 */
public final class AttentionFeed {

    public enum Severity { INFO, WARN, ALERT }

    public record AttentionItem(String id, long timestampMillis, Severity severity,
                                String kind, String title, String body, String evidenceRef) {
    }

    private static final int MAX_ITEMS = 100;

    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final Map<String, AttentionItem> items = new HashMap<>();

    public synchronized void upsert(AttentionItem item) {
        if (!items.containsKey(item.id())) {
            if (order.size() == MAX_ITEMS) {
                items.remove(order.removeFirst());
            }
            order.addLast(item.id());
        }
        items.put(item.id(), item);
    }

    /** Resolves a trigger item when its condition clears (e.g. mark no longer stale). */
    public synchronized void resolve(String id) {
        if (items.remove(id) != null) {
            order.remove(id);
        }
    }

    /** Newest first, ALERT > WARN > INFO within the same recency bucket kept simple: newest first. */
    public synchronized List<AttentionItem> snapshot() {
        List<AttentionItem> result = new ArrayList<>(items.size());
        order.descendingIterator().forEachRemaining(id -> result.add(items.get(id)));
        return result;
    }
}

package io.jethro.trading.runtime;

import io.jethro.domain.Decimals;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-value top-of-book quote per instrument (ADR-0025), alongside {@link MarkCache}.
 * Quotes are CONTEXT, not archived ticks: they conflate straight to last-value on the feed
 * thread (a distinct structure from the mark cache, so there is exactly one writer per
 * structure) and ride out on md.marks as optional bid/ask fields. Scaled-long writes, one
 * holder allocation per instrument lifetime.
 */
public final class QuoteCache {

    /** Mutable per-instrument holder; volatile fields — written by feed thread, read anywhere. */
    public static final class QuoteHolder {
        private volatile long bidScaled;
        private volatile long askScaled;
        private volatile long bidSizeScaled;   // ADR-0033: qty at the touch (synthesized from volume)
        private volatile long askSizeScaled;
        private volatile long providerTimestampMillis;

        public long bidScaled() {
            return bidScaled;
        }

        public long askScaled() {
            return askScaled;
        }

        public long bidSizeScaled() {
            return bidSizeScaled;
        }

        public long askSizeScaled() {
            return askSizeScaled;
        }

        public long providerTimestampMillis() {
            return providerTimestampMillis;
        }

        public BigDecimal bid() {
            return Decimals.fromScaledLong(bidScaled, Decimals.PRICE_SCALE);
        }

        public BigDecimal ask() {
            return Decimals.fromScaledLong(askScaled, Decimals.PRICE_SCALE);
        }

        public BigDecimal bidSize() {
            return Decimals.fromScaledLong(bidSizeScaled, Decimals.QTY_SCALE);
        }

        public BigDecimal askSize() {
            return Decimals.fromScaledLong(askSizeScaled, Decimals.QTY_SCALE);
        }
    }

    private final ConcurrentHashMap<String, QuoteHolder> quotes = new ConcurrentHashMap<>();

    /** Hot path (feed thread): last-value quote update (no sizes — leaves depth at 0). */
    public void update(String instrumentId, long bidScaled, long askScaled, long providerTimestampMillis) {
        update(instrumentId, bidScaled, askScaled, 0L, 0L, providerTimestampMillis);
    }

    /** Hot path (feed thread): last-value quote update WITH touch sizes (ADR-0033). */
    public void update(String instrumentId, long bidScaled, long askScaled,
                       long bidSizeScaled, long askSizeScaled, long providerTimestampMillis) {
        QuoteHolder holder = quotes.computeIfAbsent(instrumentId, k -> new QuoteHolder());
        holder.bidScaled = bidScaled;
        holder.askScaled = askScaled;
        holder.bidSizeScaled = bidSizeScaled;
        holder.askSizeScaled = askSizeScaled;
        holder.providerTimestampMillis = providerTimestampMillis;
    }

    /** @return the holder, or null if this instrument has never quoted. */
    public QuoteHolder get(String instrumentId) {
        return quotes.get(instrumentId);
    }
}

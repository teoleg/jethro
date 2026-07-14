package io.jethro.trading.marketdata.yahoo;

import io.jethro.domain.Decimals;
import io.jethro.trading.marketdata.FeedStatus;
import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.MarketDataListener;
import io.jethro.trading.marketdata.sim.CurveMarkSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Yahoo Finance market-data adapter (ADR-0023): a free, ~15-min-delayed, dev/demo-only feed
 * behind the ADR-0009 port. Polls a last price per instrument on a cadence and normalises it
 * to the internal {@code instrumentId} — Yahoo symbols never cross this boundary (invariant 2).
 * Provider/ingest timestamps are both carried (invariant 5): the provider time is the (delayed)
 * source time, ingest is now, so the delay is visible downstream rather than hidden.
 *
 * <p>Hybrid by design: Yahoo covers the price-quoted names it's mapped to (equities, index
 * futures, FX); an optional {@link CurveFactorSimulator} keeps the SOFR curve, curve-linked
 * Treasury futures, and swaps alive so the Rates/Swaps views keep working (there is no free
 * SOFR zero curve on Yahoo). Never a production or real-money feed — unofficial and ToS-limited.
 */
public final class YahooMarketDataAdapter implements MarketDataAdapter {

    public static final String NAME = "yahoo";
    private static final long ERROR_BACKOFF_NANOS = 30L * 1_000_000_000; // extra pause after a bad cycle

    private final QuoteSource quotes;
    private final Map<String, String> instrumentToSymbol; // instrumentId → Yahoo symbol
    private final CurveMarkSource curveSim; // nullable: no rates marks when absent
    private final long requestSpacingNanos; // gap BETWEEN symbol requests — spread, don't burst
    private final long cycleBudgetMillis;   // ~time for one full pass over all symbols

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateMillis = new AtomicLong(0);
    private final AtomicLong lastProviderEpoch = new AtomicLong(0);
    private final AtomicLong drops = new AtomicLong(0);
    private volatile Thread feedThread;

    /**
     * @param requestSpacingMillis gap between individual symbol requests. Yahoo throttles
     *        bursts (all-at-once gets 429'd after ~1), so we fetch one symbol, wait, next —
     *        a steady trickle that mostly succeeds instead of a burst that mostly fails.
     */
    public YahooMarketDataAdapter(QuoteSource quotes, Map<String, String> instrumentToSymbol,
                                  CurveMarkSource curveSim, long requestSpacingMillis) {
        if (instrumentToSymbol.isEmpty()) {
            throw new IllegalArgumentException("at least one instrument→symbol mapping required");
        }
        this.quotes = quotes;
        this.instrumentToSymbol = Map.copyOf(instrumentToSymbol);
        this.curveSim = curveSim;
        long spacing = Math.max(1, requestSpacingMillis);
        this.requestSpacingNanos = spacing * 1_000_000;
        this.cycleBudgetMillis = spacing * this.instrumentToSymbol.size();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start(MarketDataListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("yahoo adapter already started");
        }
        Thread thread = new Thread(() -> run(listener), "yahoo-feed");
        thread.setDaemon(true);
        feedThread = thread;
        thread.start();
    }

    private void run(MarketDataListener listener) {
        while (running.get()) {
            int ok = 0;
            for (Map.Entry<String, String> entry : instrumentToSymbol.entrySet()) {
                if (!running.get()) {
                    break;
                }
                var quote = quotes.fetch(entry.getValue());
                long now = System.currentTimeMillis();
                if (quote.isPresent()) {
                    long priceScaled = Decimals.toScaledLong(quote.get().price(), Decimals.PRICE_SCALE);
                    long providerMillis = quote.get().epochSeconds() * 1000;
                    listener.onTrade(entry.getKey(), priceScaled, Decimals.toScaledLong(
                            java.math.BigDecimal.ONE, Decimals.QTY_SCALE), providerMillis, now);
                    lastUpdateMillis.set(now);
                    lastProviderEpoch.set(quote.get().epochSeconds());
                    ok++;
                } else {
                    drops.incrementAndGet(); // count, log, expose — never silently drop (data-path rule)
                }
                // Space every request so we trickle instead of burst (avoids Yahoo's 429s).
                LockSupport.parkNanos(requestSpacingNanos);
            }
            if (curveSim != null) {
                // Keep the SOFR curve, linked Treasury futures and swaps alive alongside Yahoo
                // (mirrors the sim adapter's curve block). Steps once per full pass.
                long now = System.currentTimeMillis();
                curveSim.step();
                for (int t = 0; t < CurveMarkSource.TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TENOR_IDS[t],
                            curveSim.rateScaledPercent(t), 1_000_000L, now, now);
                }
                // The DISTINCT US Treasury par curve (TSY = SOFR + swap spread; futures key off it).
                for (int t = 0; t < CurveMarkSource.TSY_TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TSY_TENOR_IDS[t],
                            curveSim.tsyRateScaledPercent(t), 1_000_000L, now, now);
                }
                for (int s = 0; s < CurveMarkSource.SWAP_IDS.length; s++) {
                    listener.onTrade(CurveMarkSource.SWAP_IDS[s],
                            curveSim.swapParScaledPercent(s), 1_000_000L, now, now);
                }
            }
            // If a whole pass failed (throttled), back off a while to let Yahoo cool down.
            if (ok == 0) {
                LockSupport.parkNanos(ERROR_BACKOFF_NANOS);
            }
        }
    }

    @Override
    public FeedStatus status() {
        long last = lastUpdateMillis.get();
        boolean connected = running.get() && last > 0
                && System.currentTimeMillis() - last < Math.max(60_000, cycleBudgetMillis * 3);
        long delaySeconds = lastProviderEpoch.get() == 0 ? 0
                : Math.max(0, System.currentTimeMillis() / 1000 - lastProviderEpoch.get());
        return new FeedStatus(NAME, connected, last, delaySeconds);
    }

    /** Count of failed fetches (rate limits/errors) — exposed for observability (data-path rule). */
    public long drops() {
        return drops.get();
    }

    @Override
    public void stop() {
        running.set(false);
        Thread thread = feedThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

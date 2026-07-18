package io.jethro.app.trading;

import io.jethro.trading.marketdata.sim.SimNewsEngine;
import io.jethro.trading.runtime.MarkCache;
import io.jethro.trading.runtime.VolumeStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Detects and timestamps market "spikes" so the sim page can show WHEN something happened and
 * the operator can correlate it to orders and logs. Runs off the market path (a slow poller,
 * never the tick hot path — invariant 7); each event carries a wall-clock time (the same clock
 * the logs and order timestamps use), the instrument, a type, and a magnitude:
 *
 * <ul>
 *   <li><b>PRICE</b> — a mark moved more than {@code priceThresholdBps} between polls (a gap /
 *       news jump); magnitude is the signed bp move.</li>
 *   <li><b>VOLUME</b> — relative volume crossed above {@code volHigh} (rising edge, with
 *       hysteresis at {@code volLow} so one surge is one event); magnitude is the ×-normal ratio.</li>
 *   <li><b>NEWS</b> — a sim news event fired (the CAUSE most price/volume spikes trace back to);
 *       magnitude is its sign, detail is the headline.</li>
 * </ul>
 *
 * Read-only and best-effort: a failed pass is logged and skipped, never fatal.
 */
public final class SpikeMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpikeMonitor.class);
    private static final long PERIOD_MILLIS = 750;
    private static final int MAX_EVENTS = 200;

    /** A detected spike, stamped in wall-clock millis (correlate with logs/orders). */
    public record SpikeEvent(long atMillis, String instrumentId, String type, double magnitude, String detail) {
    }

    private final TradingCoreLifecycle tradingCore;
    private final double priceThresholdBps;
    private final double volHigh;
    private final double volLow;

    private final Map<String, Double> lastPrice = new HashMap<>();
    private final Set<String> volElevated = new HashSet<>();
    private long lastNewsSeq = -1;
    private final Deque<SpikeEvent> events = new ArrayDeque<>();
    private volatile ScheduledExecutorService scheduler;

    public SpikeMonitor(TradingCoreLifecycle tradingCore, double priceThresholdBps,
                        double volHigh, double volLow) {
        this.tradingCore = tradingCore;
        this.priceThresholdBps = priceThresholdBps > 0 ? priceThresholdBps : 40.0;
        this.volHigh = volHigh > 1.0 ? volHigh : 2.0;
        this.volLow = volLow > 0 && volLow < this.volHigh ? volLow : 1.3;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spike-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkOnce, PERIOD_MILLIS, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    void checkOnce() {
        try {
            var rt = tradingCore.runtime();
            if (rt == null) {
                return;
            }
            long now = System.currentTimeMillis();
            VolumeStats stats = tradingCore.volumeStats();
            for (MarkCache.MarkSnapshot m : rt.markCache().snapshot()) {
                if (m.stale() || m.price() == null) {
                    continue;
                }
                String id = m.instrumentId();
                double px = m.price().doubleValue();
                // price spike: bp move vs the previous poll (a settled jump fires once, then the
                // poll-to-poll delta returns to ~0 — self-deduping).
                Double prev = lastPrice.put(id, px);
                if (prev != null && prev > 0 && px > 0) {
                    double bps = (px / prev - 1.0) * 10_000.0;
                    if (Math.abs(bps) >= priceThresholdBps) {
                        add(now, id, "PRICE", bps, String.format("%+.0fbps in ~%.1fs", bps, PERIOD_MILLIS / 1000.0));
                    }
                }
                // volume spike: rising edge over volHigh, cleared under volLow (hysteresis).
                if (stats != null) {
                    double rel = stats.relativeVolume(id);
                    if (rel >= volHigh) {
                        if (volElevated.add(id)) {
                            add(now, id, "VOLUME", rel, String.format("%.1f× normal volume", rel));
                        }
                    } else if (rel < volLow) {
                        volElevated.remove(id);
                    }
                }
            }
            // news events (the cause) — record each new one exactly once by sequence.
            for (SimNewsEngine.SimNewsEvent e : tradingCore.recentSimNews()) {
                long seq = seqOf(e.id());
                if (seq > lastNewsSeq) {
                    lastNewsSeq = seq;
                    add(now, e.instrumentId(), "NEWS", e.sign(), e.headline());
                }
            }
        } catch (Exception e) {
            log.warn("spike monitor pass failed: {}", e.toString());
        }
    }

    private static long seqOf(String newsId) {
        int dash = newsId.lastIndexOf('-');
        try {
            return dash >= 0 ? Long.parseLong(newsId.substring(dash + 1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private synchronized void add(long atMillis, String instrumentId, String type, double magnitude, String detail) {
        events.addLast(new SpikeEvent(atMillis, instrumentId, type, magnitude, detail));
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    /** Recent spikes, newest first. */
    public synchronized List<SpikeEvent> recent() {
        List<SpikeEvent> out = new ArrayList<>(events);
        java.util.Collections.reverse(out);
        return out;
    }

    @Override
    public void close() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }
}

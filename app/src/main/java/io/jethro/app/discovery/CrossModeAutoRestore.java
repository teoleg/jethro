package io.jethro.app.discovery;

import io.jethro.messaging.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.List;

/**
 * Hands-off boot healer for an over-aggressive cross-mode cleanup. If a LARGE pile of discovered names was
 * evicted for a foreign feed-mode tag and never re-promoted — the signature of an over-eviction, e.g. the
 * legacy Alpaca-as-SIM mislabel wiping real LIVE discoveries — it restores them automatically at boot,
 * re-tagged with the current feed mode. So the universe self-heals without anyone clicking "restore".
 *
 * <p><b>Runs BEFORE trading-core (phase &lt; 0).</b> The market-data feed copies its poll universe at
 * construction (phase 0), so the restore must land in refdata first for the restored names to be marked
 * THIS session rather than only after the next restart.
 *
 * <p><b>Only fires for an over-eviction ({@code >= minBatch}).</b> A small eviction is treated as a
 * deliberate prune and left alone, so an intentional removal of a genuine SIM name sticks. Idempotent: a
 * restored name carries a later PROMOTED row and drops out of the restorable set, so the next boot is a
 * no-op. Never rewrites historical audit rows (invariant 8) — restore only appends.
 */
public final class CrossModeAutoRestore implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CrossModeAutoRestore.class);

    private final UniversePromotionRepository repo; // nullable when persistence is off
    private final UniversePromotionService svc;     // nullable when the write path is unavailable
    private final boolean enabled;
    private final int minBatch;
    private volatile boolean running;

    public CrossModeAutoRestore(UniversePromotionRepository repo, UniversePromotionService svc,
                                boolean enabled, int minBatch) {
        this.repo = repo;
        this.svc = svc;
        this.enabled = enabled;
        this.minBatch = Math.max(1, minBatch);
    }

    @Override
    public void start() {
        running = true;
        if (!enabled || repo == null || svc == null) {
            return;
        }
        try {
            List<String> restorable = repo.foreignModeEvictedRestorable();
            if (restorable.size() < minBatch) {
                if (!restorable.isEmpty()) {
                    log.info("cross-mode auto-restore: {} evicted name(s) outstanding — below the over-eviction "
                                    + "threshold ({}), so leaving them (looks deliberate). Restore manually on the "
                                    + "discovery page if you want them back.",
                            restorable.size(), minBatch);
                }
                return;
            }
            String mode = Provenance.mode().name();
            List<String> restored = svc.restoreDiscovered(restorable, Provenance.epoch(), mode,
                    System.currentTimeMillis());
            log.warn("cross-mode AUTO-RESTORE ({} >= {}): {} name(s) were evicted for a foreign feed-mode tag and "
                            + "never re-promoted — restored and re-tagged {} so the live universe self-heals: {}",
                    restorable.size(), minBatch, restored.size(), mode, String.join(", ", restored));
        } catch (Exception e) {
            // Best-effort heal — never block boot on it.
            log.warn("cross-mode auto-restore skipped: {}", e.toString());
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before trading-core (phase 0) so restored names are in refdata when the feed copies its poll set. */
    @Override
    public int getPhase() {
        return -100;
    }
}

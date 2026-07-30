package io.jethro.app.discovery;

import io.jethro.messaging.Provenance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Read-only view of the ADR-0060 promotion gate. Surfaces, for every live discovery candidate, the gate's
 * verdict and the reason, plus the durable audit trail. {@code dryRun} reflects the ACTUAL mode
 * ({@code jethro.universe.dynamic.write}): false = the gate writes promotions to refdata; true = it only
 * proposes. This endpoint itself is read-only — it never writes.
 */
@RestController
public final class UniversePromotionController {

    private final ObjectProvider<UniversePromotionLifecycle> lifecycle;
    private final ObjectProvider<UniversePromotionRepository> repository;
    private final ObjectProvider<UniversePromotionService> service;
    private final DynamicUniverseProperties props;

    public UniversePromotionController(ObjectProvider<UniversePromotionLifecycle> lifecycle,
                                       ObjectProvider<UniversePromotionRepository> repository,
                                       ObjectProvider<UniversePromotionService> service,
                                       DynamicUniverseProperties props) {
        this.lifecycle = lifecycle;
        this.repository = repository;
        this.service = service;
        this.props = props;
    }

    /** One candidate's live gate result. {@code promote} = would be admitted; {@code outcome}/{@code reason}
     *  explain why (or why not). */
    public record ProposalView(String instrument, boolean promote, String outcome, String reason,
                               double score, int distinctDays, int sources, long lastSeenMillis) {
    }

    public record AuditView(long atMillis, String feedMode, String instrument, String action, String outcome,
                            Double score, Integer distinctDays, String reason, boolean dryRun) {
    }

    /** available=false when the dynamic universe is disabled (default) — the whole feature is dark. */
    public record ProposalsView(boolean available, boolean dryRun, int wouldPromote, List<ProposalView> proposals,
                                List<AuditView> recent) {
    }

    @GetMapping("/api/universe/proposals")
    public ProposalsView proposals() {
        boolean dryRun = !props.writeEnabled(); // reflect the REAL mode, not a hardcoded flag
        UniversePromotionLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return new ProposalsView(false, dryRun, 0, List.of(), recentAudit());
        }
        List<ProposalView> rows = live.evaluateNow().stream()
                .map(p -> new ProposalView(
                        p.candidate().instrumentId(),
                        p.verdict().promote(),
                        p.verdict().outcome().name(),
                        p.verdict().reason(),
                        p.candidate().score(),
                        p.candidate().distinctDays(),
                        p.candidate().sources().size(),
                        p.candidate().lastSeenMillis()))
                .toList();
        int wouldPromote = (int) rows.stream().filter(ProposalView::promote).count();
        return new ProposalsView(true, dryRun, wouldPromote, rows, recentAudit());
    }

    /** Result of a cross-mode cleanup. {@code evicted} = names removed; {@code kept} = names left in place
     *  (pinned or holding a position/tape — never orphaned). {@code mode} is the mode we cleaned FOR. */
    public record CleanupView(boolean available, String mode, List<String> evicted, String kept) {
    }

    /**
     * Evict discovered names that were promoted under a DIFFERENT feed mode than the one this process is
     * running, so the current mode's universe reflects the current mode's discovery only — the fix for a
     * SIM-era promotion persisting into a LIVE run (invariant 8 / ADR-0029). On-demand and idempotent:
     * running it again once clean evicts nothing. Never touches a pinned name or a name with fills; an
     * evicted name is re-promotable by discovery under the current mode if it still qualifies.
     *
     * <p>{@code POST /api/universe/cleanup-cross-mode}.
     */
    @PostMapping("/api/universe/cleanup-cross-mode")
    public CleanupView cleanupCrossMode() {
        String mode = Provenance.mode().name();
        UniversePromotionRepository repo = repository.getIfAvailable();
        UniversePromotionService svc = service.getIfAvailable();
        if (repo == null || svc == null) {
            return new CleanupView(false, mode, List.of(), "dynamic universe write path not available");
        }
        List<String> foreign = repo.promotedOnlyInForeignMode(mode);
        List<String> evicted = svc.evictForeignModePromotions(
                Set.copyOf(foreign), props.pinListOrEmpty(), Provenance.epoch(), mode, System.currentTimeMillis());
        List<String> kept = foreign.stream().filter(id -> !evicted.contains(id)).toList();
        return new CleanupView(true, mode, evicted,
                kept.isEmpty() ? "" : "pinned or has fills (kept to not orphan a position): " + String.join(", ", kept));
    }

    private List<AuditView> recentAudit() {
        UniversePromotionRepository repo = repository.getIfAvailable();
        if (repo == null) {
            return List.of();
        }
        return repo.recent(50).stream()
                .map(a -> new AuditView(a.atMillis(), a.feedMode(), a.instrumentId(), a.action(), a.outcome(),
                        a.score(), a.distinctDays(), a.reason(), a.dryRun()))
                .toList();
    }
}

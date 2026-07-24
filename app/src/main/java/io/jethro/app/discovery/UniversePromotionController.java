package io.jethro.app.discovery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the ADR-0060 promotion gate (Phase 1, dry-run). Surfaces, for every live discovery
 * candidate, the gate's verdict and the reason — so the gate can be watched working on real candidates
 * before any refdata write path is wired — plus the durable audit trail of past proposals. This endpoint
 * never promotes, evicts, or writes reference data.
 */
@RestController
public final class UniversePromotionController {

    private final ObjectProvider<UniversePromotionLifecycle> lifecycle;
    private final ObjectProvider<UniversePromotionRepository> repository;

    public UniversePromotionController(ObjectProvider<UniversePromotionLifecycle> lifecycle,
                                       ObjectProvider<UniversePromotionRepository> repository) {
        this.lifecycle = lifecycle;
        this.repository = repository;
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
        UniversePromotionLifecycle live = lifecycle.getIfAvailable();
        if (live == null) {
            return new ProposalsView(false, true, 0, List.of(), recentAudit());
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
        return new ProposalsView(true, true, wouldPromote, rows, recentAudit());
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

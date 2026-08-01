package io.jethro.app.trading;

import io.jethro.domain.AssetClass;
import io.jethro.refdata.RefDataRepository;
import io.jethro.uigateway.MarkHistory;
import io.jethro.uigateway.MarkState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only world-index market-TREND feed (ADR-0129): the major US / EU / Asia indices with their latest
 * (delayed) mark and a session change, grouped by region. Sourced entirely from the reference-data master
 * (the {@code INDEX} class) and the live mark state — no hardcoded list (invariant 9). Spot indices are a
 * trend/context reference, never tradable (gated out of the order path); this endpoint only reads.
 */
@RestController
public final class IndicesController {

    /** {@code price}/{@code changePct} are exact-decimal display strings (invariant 1) — a percentage for
     *  the eye, never a money/PnL/risk number. {@code hasMark} false when no mark has arrived yet. */
    public record IndexView(String instrumentId, String displayName, String region, String currency,
                            String price, String changePct, String source, long ageMillis, boolean hasMark) {
    }

    /** US first, then Europe, then Asia — how the desk scans the day westward. */
    private static final List<String> REGION_ORDER = List.of("US", "EU", "ASIA");

    // Optional deps: RefDataRepository is present only with persistence (ADR-0014); the mark beans are
    // always up, but stay behind providers so the endpoint degrades to empty instead of failing to wire.
    private final ObjectProvider<RefDataRepository> refData;
    private final ObjectProvider<MarkState> markState;
    private final ObjectProvider<MarkHistory> markHistory;

    public IndicesController(ObjectProvider<RefDataRepository> refData, ObjectProvider<MarkState> markState,
                             ObjectProvider<MarkHistory> markHistory) {
        this.refData = refData;
        this.markState = markState;
        this.markHistory = markHistory;
    }

    @GetMapping("/api/indices")
    public List<IndexView> indices() {
        RefDataRepository refs = refData.getIfAvailable();
        if (refs == null) {
            return List.of(); // no reference data (persistence off) — nothing to list
        }
        MarkState markSt = markState.getIfAvailable();
        MarkHistory markHist = markHistory.getIfAvailable();
        long now = System.currentTimeMillis();
        Map<String, MarkState.MarkDto> marks = new HashMap<>();
        if (markSt != null) {
            for (var m : markSt.snapshot(now)) {
                marks.put(m.instrumentId(), m);
            }
        }
        Map<String, Map<String, String>> attrs = refs.findAllAttributes();
        long sessionStart = now - 24L * 3_600_000L; // change over the last day of collected marks

        List<IndexView> out = new ArrayList<>();
        for (var inst : refs.findAllInstruments()) {
            if (inst.assetClass() != AssetClass.INDEX) {
                continue; // only the spot indices — the master is the single source (invariant 9)
            }
            String id = inst.id().value();
            Map<String, String> a = attrs.getOrDefault(id, Map.of());
            MarkState.MarkDto mark = marks.get(id);
            out.add(new IndexView(
                    id,
                    a.getOrDefault("display_name", id),
                    a.getOrDefault("region", "OTHER"),
                    inst.currency(),
                    mark == null ? null : mark.price(),
                    markHist == null ? null : changePct(markHist, id, sessionStart),
                    mark == null ? null : mark.source(),
                    mark == null ? -1L : mark.ageMillis(),
                    mark != null));
        }
        out.sort(Comparator
                .comparingInt((IndexView v) -> {
                    int i = REGION_ORDER.indexOf(v.region());
                    return i < 0 ? REGION_ORDER.size() : i;
                })
                .thenComparing(IndexView::displayName));
        return out;
    }

    /**
     * Session change % = (latest − first observed in the window) / first, exact via {@link BigDecimal} to
     * two places. A DISPLAY figure only (invariant 1 / 7) — never fed to positions/PnL/risk. Null when
     * fewer than two prints exist (nothing to compare) or a price won't parse. This is the SESSION move,
     * not the official previous-close day change (our Yahoo adapter carries price only) — the UI labels it
     * as such; upgrading to a true day change is a tracked follow-up (deferred register).
     */
    private String changePct(MarkHistory markHist, String id, long sinceMillis) {
        List<MarkHistory.Point> points = markHist.since(id, sinceMillis);
        if (points == null || points.size() < 2) {
            return null;
        }
        try {
            BigDecimal first = new BigDecimal(points.get(0).price());
            BigDecimal last = new BigDecimal(points.get(points.size() - 1).price());
            if (first.signum() == 0) {
                return null;
            }
            return last.subtract(first)
                    .divide(first, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

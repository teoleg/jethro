package io.jethro.app.risk;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the daily-return history status (ADR-0038) — the UI "history loaded / as-of" indicator.
 *  Component-scanned always; the status bean exists only when persistence is on, so read it via a
 *  provider and report empty otherwise. */
@RestController
public final class HistoryController {

    private final ObjectProvider<HistoryStatus> status;

    public HistoryController(ObjectProvider<HistoryStatus> status) {
        this.status = status;
    }

    @GetMapping("/api/history/status")
    public HistoryStatus.Snapshot status() {
        HistoryStatus s = status.getIfAvailable();
        return s != null ? s.snapshot()
                : new HistoryStatus.Snapshot(0, null, 0, false, "unavailable",
                        "persistence disabled — no history store", 0);
    }
}

package io.jethro.app.training;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only status of the ADR-0053 training-bars store — so the real, restart-proof training set is
 * visible (rows, instruments, date range). Training data only; never a number into live sizing/risk.
 */
@RestController
public final class TrainingBarsController {

    private final ObjectProvider<TrainingBarsStore> store;

    public TrainingBarsController(ObjectProvider<TrainingBarsStore> store) {
        this.store = store;
    }

    @GetMapping("/api/training/bars")
    public TrainingBarsStore.Status status() {
        TrainingBarsStore s = store.getIfAvailable();
        return s == null ? new TrainingBarsStore.Status(0, 0, null, null) : s.status();
    }
}

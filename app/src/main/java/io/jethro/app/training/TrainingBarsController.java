package io.jethro.app.training;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only status of the ADR-0053 training foundation — the real, restart-proof training set (rows,
 * instruments, date range) plus the derived feature/label matrix (label balance + a sample). Training
 * data only; never a number into live sizing/risk.
 */
@RestController
public final class TrainingBarsController {

    private final ObjectProvider<TrainingBarsStore> store;
    private final ObjectProvider<FeatureService> features;

    public TrainingBarsController(ObjectProvider<TrainingBarsStore> store, ObjectProvider<FeatureService> features) {
        this.store = store;
        this.features = features;
    }

    @GetMapping("/api/training/bars")
    public TrainingBarsStore.Status status() {
        TrainingBarsStore s = store.getIfAvailable();
        return s == null ? new TrainingBarsStore.Status(0, 0, null, null) : s.status();
    }

    /** Feature/label balance across the whole training set — usefulness before any model exists. */
    @GetMapping("/api/training/features")
    public FeatureService.Summary features() {
        FeatureService f = features.getIfAvailable();
        return f == null ? new FeatureService.Summary(0, 0, 0, 0, 0, 0, List.of()) : f.summary();
    }

    /** The last few feature rows for one instrument — a spot-check the point-in-time maths is sane. */
    @GetMapping("/api/training/features/sample")
    public List<FeatureBuilder.FeatureRow> sample(@RequestParam String instrument,
                                                  @RequestParam(defaultValue = "10") int limit) {
        FeatureService f = features.getIfAvailable();
        return f == null ? List.of() : f.sample(instrument, limit);
    }
}

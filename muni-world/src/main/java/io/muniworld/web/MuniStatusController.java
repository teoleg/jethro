package io.muniworld.web;

import io.muniworld.store.MuniLmdbStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The muni-world status feed its UI polls — enough to prove the independent service is up and to show
 * which shared backends it is wired to. Computes nothing about money/risk; it just reports wiring.
 */
@RestController
public final class MuniStatusController {

    private final MuniLmdbStore lmdb;
    private final boolean kafkaEnabled;
    private final boolean flywayEnabled;

    public MuniStatusController(MuniLmdbStore lmdb,
                                @Value("${muni.kafka.enabled}") boolean kafkaEnabled,
                                @Value("${spring.flyway.enabled}") boolean flywayEnabled) {
        this.lmdb = lmdb;
        this.kafkaEnabled = kafkaEnabled;
        this.flywayEnabled = flywayEnabled;
    }

    @GetMapping("/api/muni/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", "muni-world");
        out.put("status", "up");
        Map<String, Object> backends = new LinkedHashMap<>();
        backends.put("lmdb", lmdb.env() != null ? "open" : "off");
        backends.put("postgres", flywayEnabled ? "flyway-managed" : "configured (flyway off)");
        backends.put("kafka", kafkaEnabled ? "enabled" : "configured (off)");
        out.put("backends", backends);
        return out;
    }
}

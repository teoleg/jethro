package io.muniworld.web;

import io.muniworld.bond.MuniBondService;
import io.muniworld.store.MuniLmdbStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The muni-world status feed its UI polls — enough to prove the independent service is up and to show
 * which shared backends it is wired to. Computes nothing about money/risk; it just reports wiring.
 */
@RestController
public final class MuniStatusController {

    private final MuniLmdbStore lmdb;
    private final MuniBondService bonds;
    private final boolean kafkaEnabled;
    private final boolean flywayEnabled;

    public MuniStatusController(MuniLmdbStore lmdb, MuniBondService bonds,
                                @Value("${muni.kafka.enabled}") boolean kafkaEnabled,
                                @Value("${spring.flyway.enabled}") boolean flywayEnabled) {
        this.lmdb = lmdb;
        this.bonds = bonds;
        this.kafkaEnabled = kafkaEnabled;
        this.flywayEnabled = flywayEnabled;
    }

    @GetMapping("/api/muni/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", "muni-world");
        out.put("status", "up");

        // Row counts the UI shows so "results loaded in DB" is verifiable at a glance: the LMDB index count
        // (always available) and the Postgres system-of-record count (present only when the DB is reachable).
        long lmdbRows = bonds.indexCount();
        OptionalLong dbRows = bonds.dbCount();

        Map<String, Object> backends = new LinkedHashMap<>();
        backends.put("lmdb", lmdb.env() != null ? lmdbRows + " securities" : "off");
        backends.put("postgres", dbRows.isPresent() ? dbRows.getAsLong() + " securities"
                : flywayEnabled ? "unreachable" : "configured (flyway off)");
        backends.put("kafka", kafkaEnabled ? "enabled" : "configured (off)");
        out.put("backends", backends);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("lmdbSecurities", lmdbRows);
        counts.put("dbSecurities", dbRows.isPresent() ? dbRows.getAsLong() : null);
        out.put("counts", counts);
        return out;
    }
}

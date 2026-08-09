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
    private final io.muniworld.audio.AudioSourceCatalog audioSources;
    private final boolean kafkaEnabled;
    private final boolean flywayEnabled;
    private final boolean captureEnabled;
    private final String whisperModel;
    private final io.muniworld.audio.WhisperCliTranscriber whisper;

    public MuniStatusController(MuniLmdbStore lmdb, MuniBondService bonds,
                                io.muniworld.audio.AudioSourceCatalog audioSources,
                                io.muniworld.audio.WhisperCliTranscriber whisper,
                                @Value("${muni.kafka.enabled}") boolean kafkaEnabled,
                                @Value("${spring.flyway.enabled}") boolean flywayEnabled,
                                @Value("${muni.audio.capture.enabled:false}") boolean captureEnabled,
                                @Value("${muni.audio.whisper.model:}") String whisperModel) {
        this.whisper = whisper;
        this.lmdb = lmdb;
        this.bonds = bonds;
        this.audioSources = audioSources;
        this.kafkaEnabled = kafkaEnabled;
        this.flywayEnabled = flywayEnabled;
        this.captureEnabled = captureEnabled;
        this.whisperModel = whisperModel == null ? "" : whisperModel;
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

        // TV/audio capture (ADR-0014): report the three gates that must ALL be satisfied before a single
        // second of audio is captured, and which one is blocking. Reported, never inferred — the capture
        // loop is silent when idle, so without this the UI cannot say why nothing is happening.
        int capturable = audioSources.capturable().size();
        // The ASR gate is asked of the TRANSCRIBER, which checks the model file and binary actually exist.
        // A non-blank config value proves nothing: "tiny.en" is a model NAME, not a path, and reporting it
        // as "model: set" said green while every transcription failed with No such file or directory.
        String asrBlocker = whisper.blocker();
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("captureEnabled", captureEnabled);
        audio.put("feeds", audioSources.all().size());
        audio.put("capturableFeeds", capturable);
        audio.put("whisperModelSet", asrBlocker == null);
        audio.put("blocker", !captureEnabled
                ? "capture is OFF (MUNI_AUDIO_CAPTURE=false) — run `svc.sh start tv`"
                : capturable == 0
                  ? "no feed is enabled AND has a source — set one on the TV page (advanced)"
                  : asrBlocker);
        out.put("audio", audio);
        return out;
    }
}

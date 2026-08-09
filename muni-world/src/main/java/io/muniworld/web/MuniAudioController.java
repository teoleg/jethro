package io.muniworld.web;

import io.muniworld.audio.AudioSource;
import io.muniworld.audio.AudioSourceCatalog;
import io.muniworld.audio.RecentLeadsStore;
import io.muniworld.audio.RecentTranscriptsStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What the TV page reads (ADR-0014): the configured feeds, the raw transcripts the capture loop produced,
 * and the leads mined from them. Read-only — the loop is the only thing that captures, and a lead is a
 * pointer to <b>verify</b>, never a number that feeds risk/PnL.
 */
@RestController
public final class MuniAudioController {

    private final RecentLeadsStore recent;
    private final AudioSourceCatalog sources;
    private final RecentTranscriptsStore transcripts;

    public MuniAudioController(RecentLeadsStore recent, AudioSourceCatalog sources,
                               RecentTranscriptsStore transcripts) {
        this.recent = recent;
        this.sources = sources;
        this.transcripts = transcripts;
    }

    /** The configured feeds and whether each is capturable. */
    @GetMapping("/api/muni/audio/sources")
    public List<AudioSource> sources() {
        return sources.all();
    }

    /** The raw recent transcripts — "what did it hear", to eyeball against the broadcast. */
    @GetMapping("/api/muni/audio/transcripts/recent")
    public List<RecentTranscriptsStore.Entry> transcripts(@RequestParam(defaultValue = "10") int limit) {
        return transcripts.recent(limit);
    }

    /** The live tail of leads the capture loop has found. */
    @GetMapping("/api/muni/audio/leads/recent")
    public List<RecentLeadsStore.Entry> recent(@RequestParam(defaultValue = "50") int limit) {
        return recent.recent(limit);
    }
}

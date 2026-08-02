package io.muniworld.web;

import io.muniworld.audio.RecentLeadsStore;
import io.muniworld.audio.Transcript;
import io.muniworld.audio.TranscriptLeadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ADR-0014 audio-analysis seam. {@code POST /api/muni/audio/leads} takes a {@link Transcript} (whatever
 * produced it — the Pi's whisper.cpp stage, or a hand-supplied one) and returns the deterministic
 * {@link TranscriptLeadService.Leads}: issuer/keyword/CUSIP pointers to <b>verify</b>, never numbers.
 *
 * <p>This is the network-free seam (no audio device, no ASR binary needed), so the guardrail logic is fully
 * exercised offline. The capture → transcribe → leads chain runs on the Pi behind a scheduler.
 */
@RestController
public final class MuniAudioController {

    private final TranscriptLeadService leads;
    private final RecentLeadsStore recent;

    public MuniAudioController(TranscriptLeadService leads, RecentLeadsStore recent) {
        this.leads = leads;
        this.recent = recent;
    }

    /** Detect leads in a supplied transcript — a transcript is a lead source, never a source of numbers. */
    @PostMapping("/api/muni/audio/leads")
    public TranscriptLeadService.Leads leads(@RequestBody Transcript transcript) {
        return leads.detect(transcript);
    }

    /** The live tail of leads the capture loop has found (ADR-0014). Empty until capture is enabled on the Pi. */
    @GetMapping("/api/muni/audio/leads/recent")
    public List<RecentLeadsStore.Entry> recent(@RequestParam(defaultValue = "50") int limit) {
        return recent.recent(limit);
    }
}

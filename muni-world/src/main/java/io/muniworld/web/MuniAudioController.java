package io.muniworld.web;

import io.muniworld.audio.Transcript;
import io.muniworld.audio.TranscriptLeadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

    public MuniAudioController(TranscriptLeadService leads) {
        this.leads = leads;
    }

    /** Detect leads in a supplied transcript — a transcript is a lead source, never a source of numbers. */
    @PostMapping("/api/muni/audio/leads")
    public TranscriptLeadService.Leads leads(@RequestBody Transcript transcript) {
        return leads.detect(transcript);
    }
}

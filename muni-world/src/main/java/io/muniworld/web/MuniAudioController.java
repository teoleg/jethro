package io.muniworld.web;

import io.muniworld.audio.AudioSource;
import io.muniworld.audio.AudioSourceCatalog;
import io.muniworld.audio.RecentLeadsStore;
import io.muniworld.audio.RecentTranscriptsStore;
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
    private final AudioSourceCatalog sources;
    private final RecentTranscriptsStore transcripts;
    private final io.muniworld.audio.AudioDeviceScanner devices;
    private final io.muniworld.audio.Transcriber transcriber;
    private final String ffmpegBin;

    public MuniAudioController(TranscriptLeadService leads, RecentLeadsStore recent,
                              AudioSourceCatalog sources, RecentTranscriptsStore transcripts,
                              io.muniworld.audio.AudioDeviceScanner devices,
                              io.muniworld.audio.Transcriber transcriber,
                              @org.springframework.beans.factory.annotation.Value("${muni.audio.ffmpeg.bin:ffmpeg}")
                              String ffmpegBin) {
        this.leads = leads;
        this.recent = recent;
        this.sources = sources;
        this.transcripts = transcripts;
        this.devices = devices;
        this.transcriber = transcriber;
        this.ffmpegBin = ffmpegBin;
    }

    /** The audio inputs THIS host exposes, in the {@code <format>:<name>} form the registry takes. */
    @GetMapping("/api/muni/audio/devices")
    public java.util.Map<String, Object> devices() {
        return devices.asMap();
    }

    /**
     * Bind a feed to a host device and enable it — the registry edit, done from the UI. Persists to the same
     * host registry file an operator would edit by hand and reloads, so the next capture pass picks it up
     * with no restart.
     */
    @PostMapping("/api/muni/audio/sources/{feedId}/bind")
    public java.util.Map<String, Object> bind(
            @org.springframework.web.bind.annotation.PathVariable String feedId,
            @RequestParam String device,
            @RequestParam(defaultValue = "true") boolean enabled) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            sources.bind(feedId, device, enabled);
            out.put("ok", true);
            out.put("feed", feedId);
            out.put("device", device);
            out.put("capturableFeeds", sources.capturable().size());
            out.put("registry", sources.file());
        } catch (RuntimeException e) {
            out.put("ok", false);                     // a failed save must never read as saved
            out.put("error", e.getMessage());
        }
        return out;
    }

    /**
     * Capture a SHORT sample from a bound feed right now, transcribe it, and return what it heard — the
     * one-click "is this actually working?" check.
     *
     * <p>Feeds run on a 300-second chunk, so after binding a device the first scheduled transcript is five
     * minutes away and a misconfigured device is indistinguishable from silence for that whole time. This
     * takes the same path the loop takes (same ffmpeg device string, same transcriber) over a few seconds,
     * so a broken device or a missing model fails HERE, loudly, with the reason.
     *
     * <p>Blocks for roughly {@code seconds} plus transcription time. It stores nothing: this is a probe, not
     * a capture pass — the loop remains the only thing that records leads.
     */
    @PostMapping("/api/muni/audio/test-capture")
    public java.util.Map<String, Object> testCapture(
            @RequestParam String feed,
            @RequestParam(defaultValue = "8") int seconds) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("feed", feed);
        AudioSource src = sources.all().stream().filter(s -> s.id().equals(feed)).findFirst().orElse(null);
        if (src == null) {
            out.put("ok", false);
            out.put("error", "no feed '" + feed + "' in the registry");
            return out;
        }
        if (src.device() == null || src.device().isBlank()) {
            out.put("ok", false);
            out.put("error", "feed '" + feed + "' has no device bound — pick one and Bind first");
            return out;
        }
        int secs = Math.min(Math.max(seconds, 1), 30);      // a probe, not a recording session
        out.put("device", src.device());
        out.put("seconds", secs);
        try {
            var source = new io.muniworld.audio.FfmpegCaptureSource(ffmpegBin, src.device(), secs);
            var connector = new io.muniworld.audio.AudioCaptureConnector("audio-test:" + src.id(), source);
            var audio = connector.fetch().get(0);
            out.put("audioBytes", audio.size());
            Transcript t = transcriber.transcribe(audio);
            String text = t.fullText();
            out.put("segments", t.segments().size());
            out.put("heard", text);
            out.put("leads", leads.detect(t).leads());
            out.put("ok", true);
            if (text.isBlank()) {
                // Captured bytes but no words: the device is readable, it just carried no speech. Say which,
                // rather than leaving an empty string to be read as failure.
                out.put("note", "captured " + audio.size() + " bytes but recognised no speech — the device "
                        + "works; check the TV is actually playing and that this device carries ITS audio "
                        + "(a .monitor source captures what this machine plays).");
            }
        } catch (RuntimeException e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** The raw recent transcripts — "what did it hear", to eyeball against the TV (ADR-0014 validation). */
    @GetMapping("/api/muni/audio/transcripts/recent")
    public List<RecentTranscriptsStore.Entry> transcripts(@RequestParam(defaultValue = "10") int limit) {
        return transcripts.recent(limit);
    }

    /** The TV/audio source registry (ADR-0014) — every configured feed and whether it's capturable. */
    @GetMapping("/api/muni/audio/sources")
    public List<AudioSource> sources() {
        return sources.all();
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

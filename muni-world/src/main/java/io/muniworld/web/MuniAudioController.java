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

import java.io.IOException;
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
    private final io.muniworld.audio.ScreenFrameGrabber screen;
    private final io.muniworld.audio.AudioTranscoder transcoder;
    private final String ffmpegBin;

    public MuniAudioController(TranscriptLeadService leads, RecentLeadsStore recent,
                              AudioSourceCatalog sources, RecentTranscriptsStore transcripts,
                              io.muniworld.audio.AudioDeviceScanner devices,
                              io.muniworld.audio.Transcriber transcriber,
                              io.muniworld.audio.ScreenFrameGrabber screen,
                              io.muniworld.audio.AudioTranscoder transcoder,
                              @org.springframework.beans.factory.annotation.Value("${muni.audio.ffmpeg.bin:ffmpeg}")
                              String ffmpegBin) {
        this.screen = screen;
        this.transcoder = transcoder;
        this.leads = leads;
        this.recent = recent;
        this.sources = sources;
        this.transcripts = transcripts;
        this.devices = devices;
        this.transcriber = transcriber;
        this.ffmpegBin = ffmpegBin;
    }

    /**
     * Transcribe an audio clip recorded by the BROWSER (a shared TV tab via getDisplayMedia), so the text
     * can be checked against the video the operator is watching in the same page.
     *
     * <p>This bypasses the host audio device entirely: the clip comes from the tab itself, so there is no
     * PulseAudio sink to pick and no way to be listening to the wrong output. The browser sends WebM/Opus,
     * which whisper cannot read, so it is transcoded to 16 kHz mono WAV first ({@link AudioTranscoder}).
     * Nothing is stored — the clip is transcribed and dropped, like every other capture here.
     */
    @PostMapping("/api/muni/audio/transcribe-clip")
    public java.util.Map<String, Object> transcribeClip(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            byte[] raw = file.getBytes();
            out.put("clipBytes", raw.length);
            if (raw.length == 0) {
                out.put("ok", false);
                out.put("error", "empty clip — the shared tab carried no audio "
                        + "(re-share and tick 'Share tab audio')");
                return out;
            }
            byte[] wav = transcoder.toWav16kMono(raw);
            var level = io.muniworld.audio.AudioLevel.of(wav);
            out.put("level", level);
            var audio = io.muniworld.ingest.RawArtifact.of(
                    "browser-tab", file.getOriginalFilename(), "audio/wav", wav);
            Transcript t = transcriber.transcribe(audio);
            String text = t.fullText();
            out.put("segments", t.segments().size());
            out.put("heard", text);
            out.put("leads", leads.detect(t).leads());
            out.put("ok", true);
            if (text.isBlank()) {
                out.put("note", Boolean.TRUE.equals(level.get("silent"))
                        ? "the shared tab produced SILENCE — 'Share tab audio' was probably not ticked, or "
                          + "the tab itself is muted/paused."
                        : "audible sound (peak " + level.get("peakDbfs") + " dBFS) but no speech recognised "
                          + "— music or noise rather than speech in this clip.");
            }
        } catch (IOException | RuntimeException e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** Can this box show its screen, and from which device — so the UI can explain a missing picture. */
    @GetMapping("/api/muni/video/status")
    public java.util.Map<String, Object> screenStatus() {
        return screen.status();
    }

    /**
     * One still frame of this machine's screen — the visual half of the ADR-0014 check: see what is on the
     * TV next to what the ASR heard. A still, never a stream, and nothing is stored.
     */
    @GetMapping(value = "/api/muni/video/frame", produces = org.springframework.http.MediaType.IMAGE_JPEG_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> screenFrame() {
        try {
            return org.springframework.http.ResponseEntity.ok()
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(screen.grab());
        } catch (RuntimeException e) {
            // 503 + the reason as text: the <img> fails and the page reads the reason from /video/status.
            return org.springframework.http.ResponseEntity.status(503)
                    .header("X-Screen-Error", String.valueOf(e.getMessage()).replaceAll("[\\r\\n]+", " "))
                    .build();
        }
    }

    /** Raw audio environment as the muni-world PROCESS sees it — which PulseAudio daemon, which sinks,
     *  and what is playing into it. The one call that settles "playing on screen but silent in capture". */
    @GetMapping("/api/muni/audio/debug")
    public java.util.Map<String, Object> audioDebug() {
        return devices.debug();
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
            // Measure BEFORE transcribing: a silent capture is a device/routing fact, not an ASR verdict.
            var level = io.muniworld.audio.AudioLevel.of(audio.body());
            out.put("level", level);
            Transcript t = transcriber.transcribe(audio);
            String text = t.fullText();
            out.put("segments", t.segments().size());
            out.put("heard", text);
            out.put("leads", leads.detect(t).leads());
            out.put("ok", true);
            if (text.isBlank()) {
                // Distinguish "nothing to hear" from "heard sound, no words" — they have different fixes.
                out.put("note", Boolean.TRUE.equals(level.get("silent"))
                        ? String.valueOf(level.get("note"))
                        : "captured audible sound (peak " + level.get("peakDbfs") + " dBFS) but recognised no "
                          + "speech — the device is RIGHT; this was music/noise, or speech too faint or "
                          + "unclear for the model.");
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

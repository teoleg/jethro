package io.muniworld.audio;

import io.muniworld.ingest.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ADR-0014 capture loop, wired for a Linux host with audio (the Pi). Each pass: capture a fixed chunk off
 * the configured audio input (ffmpeg → landed {@link RawArtifact}), transcribe it locally (whisper.cpp), mine
 * the transcript for deterministic leads, and stash them in {@link RecentLeadsStore} for the UI/API. It is a
 * lead source, never a source of numbers.
 *
 * <p><b>OFF by default</b> ({@code muni.audio.capture.enabled=true} to turn on), so the jar boots the same in
 * the sandbox / CI (no audio device, no whisper binary) and only this bean is absent. Enable it on the Pi
 * once {@code muni.audio.whisper.model} and the capture device are configured. Runs single-threaded: a chunk
 * is captured, then transcribed, then the loop repeats — so on a slow host transcription simply paces it
 * (never overlaps), at the cost of falling behind real time. Use a small model (tiny/base) on a Pi.
 */
@Component
@ConditionalOnProperty(name = "muni.audio.capture.enabled", havingValue = "true")
public final class AudioCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(AudioCaptureScheduler.class);

    private final Transcriber transcriber;
    private final TranscriptLeadService leadService;
    private final RecentLeadsStore store;
    private final AudioCaptureConnector connector;
    private final String feed;

    public AudioCaptureScheduler(
            Transcriber transcriber,
            TranscriptLeadService leadService,
            RecentLeadsStore store,
            @Value("${muni.audio.ffmpeg.bin:ffmpeg}") String ffmpegBin,
            @Value("${muni.audio.capture.device:pulse:default.monitor}") String device,
            @Value("${muni.audio.capture.seconds:300}") int seconds,
            @Value("${muni.audio.capture.feed:live}") String feed) {
        this.transcriber = transcriber;
        this.leadService = leadService;
        this.store = store;
        this.feed = feed;
        FfmpegCaptureSource source = new FfmpegCaptureSource(ffmpegBin, device, seconds);
        this.connector = new AudioCaptureConnector("audio:" + feed, source);
        log.info("audio capture ENABLED: feed={} device={} chunk={}s", feed, device, seconds);
    }

    /** One capture→transcribe→leads pass. fixedDelay is the gap AFTER a pass; the capture itself blocks. */
    @Scheduled(fixedDelayString = "${muni.audio.capture.gap-ms:1000}")
    public void captureOnce() {
        try {
            RawArtifact audio = connector.fetch().get(0);
            Transcript t = transcriber.transcribe(audio);
            TranscriptLeadService.Leads leads = leadService.detect(t);
            store.add(feed, leads);
            log.info("audio pass: {} segments, {} leads", t.segments().size(), leads.leads().size());
        } catch (RuntimeException e) {
            // never let one bad pass kill the loop — log and try again next tick (device hiccup, ASR error)
            log.warn("audio capture pass failed: {}", e.toString());
        }
    }
}

package io.muniworld.audio;

import io.muniworld.ingest.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ADR-0014 capture loop, driven by the {@link AudioSourceCatalog} registry (not a single feed var). Each
 * pass iterates every <b>capturable</b> feed — enabled and device-bound — and for each: capture a chunk off
 * its host audio input (ffmpeg → landed {@link RawArtifact}), transcribe locally (whisper.cpp), mine the
 * transcript for deterministic leads, and stash them tagged with the feed's label. A transcript is a lead
 * source, never a source of numbers.
 *
 * <p><b>OFF by default</b> ({@code muni.audio.capture.enabled=true} — the master switch, flipped by
 * {@code svc.sh tv start/stop}). The jar boots identically in CI/sandbox (no audio device); only this bean
 * is absent. Single-threaded: feeds are captured then transcribed one after another, so a slow host simply
 * paces the loop (never overlapping) at the cost of lag. Use a small whisper model on a Pi.
 */
@Component
@ConditionalOnProperty(name = "muni.audio.capture.enabled", havingValue = "true")
public final class AudioCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(AudioCaptureScheduler.class);

    private final AudioSourceCatalog catalog;
    private final Transcriber transcriber;
    private final TranscriptLeadService leadService;
    private final RecentLeadsStore store;
    private final RecentTranscriptsStore transcripts;
    private final String ffmpegBin;
    private final String registryPath;
    private volatile boolean warnedNoFeeds;

    public AudioCaptureScheduler(
            AudioSourceCatalog catalog,
            Transcriber transcriber,
            TranscriptLeadService leadService,
            RecentLeadsStore store,
            RecentTranscriptsStore transcripts,
            @Value("${muni.audio.ffmpeg.bin:ffmpeg}") String ffmpegBin,
            @Value("${muni.audio.sources.file:}") String registryPath) {
        this.catalog = catalog;
        this.transcriber = transcriber;
        this.leadService = leadService;
        this.store = store;
        this.transcripts = transcripts;
        this.ffmpegBin = ffmpegBin;
        this.registryPath = registryPath == null ? "" : registryPath;
        log.info("audio capture ENABLED: {} capturable feed(s) in the registry", catalog.capturable().size());
    }

    /** One pass over every capturable feed. fixedDelay is the gap AFTER a pass; each capture itself blocks. */
    @Scheduled(fixedDelayString = "${muni.audio.capture.gap-ms:1000}")
    public void captureOnce() {
        var feeds = catalog.capturable();
        if (feeds.isEmpty()) {
            // Capture is ON but the registry offers nothing to capture — the loop would otherwise spin
            // silently forever, which from the outside is indistinguishable from "the TV feature is broken".
            // Say so ONCE, naming the actual blocker, and re-arm so a later regression is reported again.
            if (!warnedNoFeeds) {
                warnedNoFeeds = true;
                log.warn("audio capture is ENABLED but NO feed is capturable: of {} registered feed(s), none "
                        + "is both enabled=true AND bound to a host audio device. Edit the registry ({}), "
                        + "set a device (e.g. pulse:default.monitor or alsa:hw:1,0) and enabled=true on a "
                        + "row, then `svc.sh restart tv`. Nothing will be captured until then.",
                        catalog.all().size(), registryPath.isBlank() ? "classpath default" : registryPath);
            }
            return;
        }
        if (warnedNoFeeds) {
            warnedNoFeeds = false;
            log.info("audio capture: {} feed(s) now capturable — resuming", feeds.size());
        }
        for (AudioSource src : feeds) {
            try {
                FfmpegCaptureSource source = new FfmpegCaptureSource(ffmpegBin, src.device(), src.chunkSeconds());
                AudioCaptureConnector connector = new AudioCaptureConnector("audio:" + src.id(), source);
                RawArtifact audio = connector.fetch().get(0);
                Transcript t = transcriber.transcribe(audio);
                transcripts.add(src.label(), t);   // the raw "what did it hear" surface (validation)
                TranscriptLeadService.Leads leads = leadService.detect(t);
                store.add(src.label(), leads);
                log.info("audio pass [{}]: {} segments, {} leads", src.id(), t.segments().size(), leads.leads().size());
            } catch (RuntimeException e) {
                // one bad feed never kills the loop or the other feeds — log and continue
                log.warn("audio capture pass failed for {}: {}", src.id(), e.toString());
            }
        }
    }
}

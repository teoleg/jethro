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

    public AudioCaptureScheduler(
            AudioSourceCatalog catalog,
            Transcriber transcriber,
            TranscriptLeadService leadService,
            RecentLeadsStore store,
            RecentTranscriptsStore transcripts,
            @Value("${muni.audio.ffmpeg.bin:ffmpeg}") String ffmpegBin) {
        this.catalog = catalog;
        this.transcriber = transcriber;
        this.leadService = leadService;
        this.store = store;
        this.transcripts = transcripts;
        this.ffmpegBin = ffmpegBin;
        log.info("audio capture ENABLED: {} capturable feed(s) in the registry", catalog.capturable().size());
    }

    /** One pass over every capturable feed. fixedDelay is the gap AFTER a pass; each capture itself blocks. */
    @Scheduled(fixedDelayString = "${muni.audio.capture.gap-ms:1000}")
    public void captureOnce() {
        var feeds = catalog.capturable();
        if (feeds.isEmpty()) {
            return;   // nothing enabled+device-bound in the registry — nothing to do this pass
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

package io.muniworld.audio;

import io.muniworld.ingest.RawArtifact;
import io.muniworld.ingest.SourceConnector;

import java.util.List;

/**
 * The ADR-0014 audio-capture connector: records a chunk of a licensed broadcast feed and lands it as an
 * immutable {@link RawArtifact} (SHA-256 provenance, ADR-0005) — the audio analogue of the Socrata/EMMA
 * connectors. The actual capture is abstracted behind {@link CaptureSource}, so the platform never depends on
 * a specific device: the Pi wires an ffmpeg-backed source (line-in / loopback), tests supply a byte lambda,
 * and the sandbox — which has no audio device — simply never constructs a real one.
 */
public final class AudioCaptureConnector implements SourceConnector {

    /** Supplies one recorded audio chunk. Real = ffmpeg off a line-in/loopback device; test = fixed bytes. */
    public interface CaptureSource {
        /** Record and return one chunk of audio (blocks for the chunk duration on a real source). */
        byte[] captureChunk();

        /** MIME type of the returned bytes, e.g. {@code audio/wav} or {@code audio/ogg}. */
        String contentType();

        /** A stable descriptor of where this came from, e.g. {@code ffmpeg:alsa:hw:1,0}; used as the url. */
        String descriptor();
    }

    private final String sourceId;
    private final CaptureSource capture;

    public AudioCaptureConnector(String sourceId, CaptureSource capture) {
        this.sourceId = sourceId;
        this.capture = capture;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public List<RawArtifact> fetch() {
        byte[] audio = capture.captureChunk();
        return List.of(RawArtifact.of(sourceId, capture.descriptor(), capture.contentType(), audio));
    }
}

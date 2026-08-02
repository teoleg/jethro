package io.muniworld.audio;

import io.muniworld.ingest.RawArtifact;

/**
 * The ADR-0014 transcription SPI — turns a landed audio {@link RawArtifact} into a {@link Transcript}. The
 * real implementation shells out to a local ASR (whisper.cpp on the Pi); tests and the sandbox (no audio
 * device, no whisper binary) supply a stub. Keeping capture and ASR behind interfaces is what makes the
 * whole audio pipeline testable offline.
 */
public interface Transcriber {

    /** Transcribe a landed audio artifact. Local, ASR-derived — the result is a lead source, not numbers. */
    Transcript transcribe(RawArtifact audio);
}
